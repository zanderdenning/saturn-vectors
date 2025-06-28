package saturn.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import saturn.common._
import saturn.insns._

case class IntegerMultiplyFactory(depth: Int, segmented: Boolean) extends FunctionalUnitFactory {
  def wideningInsns = Seq(
    WMUL.VV, WMUL.VX, WMULU.VV, WMULU.VX,
    WMULSU.VV, WMULSU.VX,
    WMACC.VV, WMACC.VX, WMACCU.VV, WMACCU.VX,
    WMACCSU.VV , WMACCSU.VX, WMACCUS.VV, WMACCUS.VX,
  ).map(_.restrictSEW(0, 1, 2)).flatten

  def baseInsns = (wideningInsns ++ Seq(
    MUL.VV, MUL.VX, MULH.VV, MULH.VX,
    MULHU.VV, MULHU.VX, MULHSU.VV, MULHSU.VX,
    MACC.VV, MACC.VX, NMSAC.VV, NMSAC.VX,
    MADD.VV, MADD.VX, NMSUB.VV, NMSUB.VX,
    SMUL.VV, SMUL.VX)
  ).map(_.pipelined(depth))

  def insns = if (segmented) baseInsns else baseInsns.map(_.elementWise)
  def generate(implicit p: Parameters) = if (segmented) {
    new SegmentedMultiplyPipe(depth)(p)
  } else {
    new ElementwiseMultiplyPipe(depth)(p)
  }
}

class SegmentedMultiplyPipe(depth: Int)(implicit p: Parameters) extends PipelinedFunctionalUnit(depth)(p) {
  val supported_insns = IntegerMultiplyFactory(depth, true).insns

  io.stall := false.B
  io.set_fflags.valid := false.B
  io.set_fflags.bits := DontCare

   val ctrl = new VectorDecoder(io.pipe(0).bits, supported_insns, Seq(
     MULHi, MULSign1, MULSign2, MULSwapVdV2, MULAccumulate, MULSub))

  val in_eew = io.pipe(0).bits.rvs1_eew
  val out_eew = io.pipe(0).bits.vd_eew

  val in_vs1 = io.pipe(0).bits.rvs1_data
  val in_vs2 = io.pipe(0).bits.rvs2_data
  val in_vd  = io.pipe(0).bits.rvd_data

  val mul_in1 = in_vs1
  val mul_in2 = Mux(ctrl.bool(MULSwapVdV2), in_vd, in_vs2)

  val multipliers = Seq.fill(dLenB >> 3)(Module(new MultiplierArray(8)))
  for (i <- 0 until (dLenB >> 3)) {
    multipliers(i).io.in1_signed := ctrl.bool(MULSign1)
    multipliers(i).io.in2_signed := ctrl.bool(MULSign2)
    multipliers(i).io.eew        := io.pipe(0).bits.rvs1_eew
    multipliers(i).io.in1        := mul_in1.asTypeOf(Vec(dLenB >> 3, UInt(64.W)))(i).asTypeOf(Vec(8, UInt(8.W)))
    multipliers(i).io.in2        := mul_in2.asTypeOf(Vec(dLenB >> 3, UInt(64.W)))(i).asTypeOf(Vec(8, UInt(8.W)))
  }
  val mul_out_comb = VecInit(multipliers.map(_.io.out)).asUInt

  ////////////////////////////////////////////////////////////////////////////////////////////
  // Pipeline Stages Before Adder Array
  ////////////////////////////////////////////////////////////////////////////////////////////
  val mul_out = Pipe(io.pipe(0).valid, mul_out_comb, depth-2).bits
  
  val in_eew_pipe = io.pipe(depth-2).bits.rvs1_eew
  val out_eew_pipe = io.pipe(depth-2).bits.vd_eew
  val ctrl_wmul = out_eew_pipe > in_eew_pipe
  val ctrl_smul = io.pipe(depth-2).bits.isOpi
  val ctrl_MULSub = Pipe(io.pipe(0).valid, ctrl.bool(MULSub), depth-2).bits
  val ctrl_MULSwapVdV2 = Pipe(io.pipe(0).valid, ctrl.bool(MULSwapVdV2), depth-2).bits
  val ctrl_MULAccumulate = Pipe(io.pipe(0).valid, ctrl.bool(MULAccumulate), depth-2).bits
  val ctrl_MULHi = Pipe(io.pipe(0).valid, ctrl.bool(MULHi), depth-2).bits 
  val in_vs2_pipe = io.pipe(depth-2).bits.rvs2_data
  val in_vd_pipe  = io.pipe(depth-2).bits.rvd_data
  ////////////////////////////////////////////////////////////////////////////////////////////

  val hi = VecInit.tabulate(4)({sew =>
    VecInit(mul_out.asTypeOf(Vec((2*dLenB) >> sew, UInt((8 << sew).W))).grouped(2).map(_.last).toSeq).asUInt
  })(in_eew_pipe)
  val lo = VecInit.tabulate(4)({sew =>
    VecInit(mul_out.asTypeOf(Vec((2*dLenB) >> sew, UInt((8 << sew).W))).grouped(2).map(_.head).toSeq).asUInt
  })(in_eew_pipe)
  val half_sel = (io.pipe(depth-2).bits.eidx >> (dLenOffBits.U - out_eew_pipe))(0)
  val wide = Mux(half_sel, mul_out >> dLen, mul_out)(dLen-1,0)
  
  val (smul_clipped, smul_sat) = {
    val smul_arr = Module(new VectorSMul)
    smul_arr.io.mul_in := mul_out
    smul_arr.io.eew := out_eew_pipe
    smul_arr.io.vxrm := io.pipe(depth-2).bits.vxrm
    (smul_arr.io.clipped, smul_arr.io.sat)
  } 

  val adder_arr = Module(new AdderArray(dLenB, no_rm=true, no_incr=true))
  adder_arr.io.in1 := Mux(ctrl_wmul, wide, lo).asTypeOf(Vec(dLenB, UInt(8.W)))
  adder_arr.io.in2 := Mux(ctrl_MULAccumulate, Mux(ctrl_MULSwapVdV2, in_vs2_pipe, in_vd_pipe), 0.U(dLen.W)).asTypeOf(Vec(dLenB, UInt(8.W)))
  adder_arr.io.incr := DontCare
  adder_arr.io.mask_carry := 0.U
  adder_arr.io.signed := DontCare
  adder_arr.io.eew := out_eew_pipe
  adder_arr.io.avg := false.B
  adder_arr.io.rm := DontCare
  adder_arr.io.sub := ctrl_MULSub
  adder_arr.io.cmask := false.B

  val add_out = adder_arr.io.out

  val out = Mux(ctrl_smul, smul_clipped, 0.U) | Mux(ctrl_MULHi, hi, 0.U) | Mux(!ctrl_smul && !ctrl_MULHi, add_out.asUInt, 0.U)
  val pipe_out = Pipe(io.pipe(depth-2).valid, out, 1).bits
  
  val vxsat = Mux(ctrl_smul, smul_sat, 0.U) & io.pipe(depth-2).bits.wmask
  val pipe_vxsat = Pipe(io.pipe(depth-2).valid, vxsat, 1).bits

  io.write.valid     := io.pipe(depth-1).valid
  io.write.bits.eg   := io.pipe(depth-1).bits.wvd_eg
  io.write.bits.data := pipe_out
  io.write.bits.mask := FillInterleaved(8, io.pipe(depth-1).bits.wmask)

  io.set_vxsat := io.pipe(depth-1).valid && (pipe_vxsat =/= 0.U)
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
}

class MultiplyBlock extends Module {
  val xLen = 64

  val io = IO(new Bundle {
    val in1_signed = Input(Bool())
    val in2_signed = Input(Bool())
    val eew = Input(UInt(2.W))

    val in1 = Input(UInt(xLen.W))
    val in2 = Input(UInt(xLen.W))

    val out_data = Output(UInt((2*xLen).W))
  })

    val mul64 = Module(new Multiplier(64))
    mul64.io.in1_signed := io.in1_signed
    mul64.io.in2_signed := io.in2_signed
    mul64.io.in1 := io.in1
    mul64.io.in2 := io.in2
    
    val mul32 = Module(new Multiplier(32))
    mul32.io.in1_signed := io.in1_signed
    mul32.io.in2_signed := io.in2_signed
    mul32.io.in1 := io.in1(63,32)
    mul32.io.in2 := io.in2(63,32)
    
    val mul16 = Seq.tabulate(2) { i => 
        val indh = 32*(i+1) - 1
        val indl = 32*i + 16
        val in1 = io.in1(indh, indl)
        val in2 = io.in2(indh, indl)
        val mul = Module(new Multiplier(16))
        mul.io.in1_signed := io.in1_signed
        mul.io.in2_signed := io.in2_signed
        mul.io.in1 := in1
        mul.io.in2 := in2
        mul
    }
    val mul8 = Seq.tabulate(4) { i => 
        val indh = 16*(i+1) - 1
        val indl = 16*i + 8
        val in1 = io.in1(indh, indl)
        val in2 = io.in2(indh, indl)
        val mul = Module(new Multiplier(8))
        mul.io.in1_signed := io.in1_signed
        mul.io.in2_signed := io.in2_signed
        mul.io.in1 := in1
        mul.io.in2 := in2
        mul
    }
    when (io.eew === 0.U) {
        mul16(1).io.in1 := Cat(Fill(8, io.in1_signed && io.in1(55)), io.in1(55, 48))
        mul16(1).io.in2 := Cat(Fill(8, io.in2_signed && io.in2(55)), io.in2(55, 48))
        mul32.io.in1 := Cat(Fill(8, io.in1_signed && io.in1(39)), io.in1(39, 32))
        mul32.io.in2 := Cat(Fill(8, io.in2_signed && io.in2(39)), io.in2(39, 32))
        mul16(0).io.in1 := Cat(Fill(8, io.in1_signed && io.in1(23)), io.in1(23, 16))
        mul16(0).io.in2 := Cat(Fill(8, io.in2_signed && io.in2(23)), io.in2(23, 16))
        mul64.io.in1 := Cat(Fill(8, io.in1_signed && io.in1(7)), io.in1(7,0))
        mul64.io.in2 := Cat(Fill(8, io.in2_signed && io.in2(7)), io.in2(7,0))

        io.out_data := Cat(mul8(3).io.out_data, 
                            mul16(1).io.out_data(15,0),
                            mul8(2).io.out_data,
                            mul32.io.out_data(15,0),
                            mul8(1).io.out_data,
                            mul16(0).io.out_data(15,0),
                            mul8(0).io.out_data,
                            mul64.io.out_data(15,0))
      }
    .elsewhen (io.eew === 1.U) {
        mul32.io.in1 := Cat(Fill(16, io.in1_signed && io.in1(47)), io.in1(47, 32))
        mul32.io.in2 := Cat(Fill(16, io.in2_signed && io.in2(47)), io.in2(47, 32))
        mul64.io.in1 := Cat(Fill(16, io.in1_signed && io.in1(15)), io.in1(15,0))
        mul64.io.in2 := Cat(Fill(16, io.in2_signed && io.in2(15)), io.in2(15,0))
    
        io.out_data := Cat(mul16(1).io.out_data,
                            mul32.io.out_data(31,0), 
                            mul16(0).io.out_data, 
                            mul64.io.out_data(31,0))
    }
    .elsewhen (io.eew === 2.U) {
        mul64.io.in1 := Cat(Fill(32, io.in1_signed && io.in1(31)), io.in1(31,0))
        mul64.io.in2 := Cat(Fill(32, io.in2_signed && io.in2(31)), io.in2(31,0))
    
        io.out_data := Cat(mul32.io.out_data, 
                            mul64.io.out_data(63,0))
    }
    .otherwise {
        io.out_data := mul64.io.out_data
    }
}

class Multiplier(width: Int) extends Module {
  val io = IO(new Bundle {
    val in1_signed = Input(Bool())
    val in2_signed = Input(Bool())

    val in1 = Input(UInt(width.W))
    val in2 = Input(UInt(width.W))

    val out_data = Output(UInt((2*width).W))
  })

  val lhs = Cat(io.in1_signed && io.in1(width-1), io.in1).asSInt
  val rhs = Cat(io.in2_signed && io.in2(width-1), io.in2).asSInt

  val prod = lhs * rhs
  
  io.out_data := prod(2*width-1,0)
}

class VectorSMul(implicit p: Parameters) extends CoreModule()(p) with HasVectorParams {
  val io = IO(new Bundle {
    val eew = Input(UInt(2.W))
    val vxrm = Input(UInt(2.W))
    val mul_in = Input(UInt((2*dLen).W))

    val clipped = Output(UInt((dLen).W))
    val sat = Output(UInt(dLenB.W))
  })
  val sat_sew = Wire(Vec(4, UInt(dLenB.W)))
  val clipped_sew = Wire(Vec(4, UInt(dLen.W)))
  for (sew <- 0 until 4) {
    val wideProds = io.mul_in.asTypeOf(Vec(dLenB >> sew, SInt((16 << sew).W)))
    val smul = wideProds.map { wideElem => 
      val rounding_incr = RoundingIncrement(io.vxrm, wideElem((8 << sew)-1, 0)) 
      (wideElem >> ((8 << sew) - 1)) + Cat(0.U(1.W), rounding_incr).asSInt  
    }
    val clip_neg = (-1 << ((8 << sew)-1)).S 
    val clip_pos = ((1 << ((8 << sew)-1)) - 1).S
    val clip_hi = smul.map{ _ > clip_pos }
    val clip_lo = smul.map{ _ < clip_neg }    
    
    clipped_sew(sew) := smul.zipWithIndex.map { case (sm, i) => 
      val long = Mux(clip_hi(i), clip_pos, 0.S) | Mux(clip_lo(i), clip_neg, 0.S) | Mux(!clip_hi(i) && !clip_lo(i), sm, 0.S)
      long((8 << sew)-1, 0)
    }.asUInt
    val sat_vec_sew = smul.zipWithIndex.map { case (sm, i) => 
      clip_hi(i) || clip_lo(i)
    }
    sat_sew(sew) := FillInterleaved((1 << sew), sat_vec_sew)
  }
  
  io.clipped := clipped_sew(io.eew)
  io.sat := sat_sew(io.eew)
}
