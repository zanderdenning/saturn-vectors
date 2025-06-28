package saturn.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import saturn.common._
import saturn.insns._


class TandemFMAPipe(depth: Int, buildFP64: Boolean, segmented: Boolean)(implicit p: Parameters) extends FPUModule()(p) {
  require (depth >= 4)
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val frm = Input(UInt(3.W))
    val addsub = Input(Bool())
    val mul = Input(Bool())
    val op = Input(UInt(2.W))
    val a_eew = Input(UInt(2.W))
    val b_eew = Input(UInt(2.W))
    val c_eew = Input(UInt(2.W))
    val out_eew = Input(UInt(2.W))
    val a = Input(UInt(64.W))
    val b = Input(UInt(64.W))
    val c = Input(UInt(64.W))

    val out = Output(UInt(64.W))
    val exc = Output(Vec(8, UInt(5.W)))
  })

  val out_eew_pipe = Pipe(io.valid, io.out_eew, depth-1)
  val frm_pipe = Pipe(io.valid, io.frm, depth-1)

  val da = io.a.asTypeOf(Vec(1, UInt(64.W))).map(f => FType.D.recode(f))
  val db = io.b.asTypeOf(Vec(1, UInt(64.W))).map(f => FType.D.recode(f))
  val dc = io.c.asTypeOf(Vec(1, UInt(64.W))).map(f => FType.D.recode(f))
  val sa = io.a.asTypeOf(Vec(2, UInt(32.W))).map(f => FType.S.recode(f))
  val sb = io.b.asTypeOf(Vec(2, UInt(32.W))).map(f => FType.S.recode(f))
  val sc = io.c.asTypeOf(Vec(2, UInt(32.W))).map(f => FType.S.recode(f))
  val ha = io.a.asTypeOf(Vec(4, UInt(16.W))).map(f => FType.H.recode(f))
  val hb = io.b.asTypeOf(Vec(4, UInt(16.W))).map(f => FType.H.recode(f))
  val hc = io.c.asTypeOf(Vec(4, UInt(16.W))).map(f => FType.H.recode(f))

  def widen(in: UInt, inT: FType, outT: FType, active: Bool): UInt = {
    val widen = Module(new hardfloat.RecFNToRecFN(inT.exp, inT.sig, outT.exp, outT.sig))
    widen.io.in := Mux(active, in, 0.U)
    widen.io.roundingMode := io.frm
    widen.io.detectTininess := hardfloat.consts.tininess_afterRounding
    widen.io.out
  }

  val dfma_valid = io.valid && io.out_eew === 3.U
  val sfma_valid = io.valid && io.out_eew === 2.U
  val hfma_valid = io.valid && io.out_eew === 1.U

  val swa = Seq(widen(sa(0), FType.S, FType.D, io.out_eew === 3.U && io.a_eew === 2.U))
  val swb = Seq(widen(sb(0), FType.S, FType.D, io.out_eew === 3.U && io.b_eew === 2.U))
  val swc = Seq(widen(sc(0), FType.S, FType.D, io.out_eew === 3.U && io.c_eew === 2.U))
  val hwa = Seq(
    widen(ha(0), FType.H, FType.S, io.out_eew === 2.U && io.a_eew === 1.U),
    widen(ha(2), FType.H, FType.S, io.out_eew === 2.U && io.a_eew === 1.U))
  val hwb = Seq(
    widen(hb(0), FType.H, FType.S, io.out_eew === 2.U && io.b_eew === 1.U),
    widen(hb(2), FType.H, FType.S, io.out_eew === 2.U && io.b_eew === 1.U))
  val hwc = Seq(
    widen(hc(0), FType.H, FType.S, io.out_eew === 2.U && io.c_eew === 1.U),
    widen(hc(2), FType.H, FType.S, io.out_eew === 2.U && io.c_eew === 1.U))

  val da_in = da.zip(swa).map(t => Mux(io.out_eew =/= io.a_eew, t._2, t._1))
  val db_in = db.zip(swb).map(t => Mux(io.out_eew =/= io.b_eew, t._2, t._1))
  val dc_in = dc.zip(swc).map(t => Mux(io.out_eew =/= io.c_eew, t._2, t._1))

  val sa_in = sa.zip(hwa).map(t => Mux(io.out_eew =/= io.a_eew, t._2, t._1))
  val sb_in = sb.zip(hwb).map(t => Mux(io.out_eew =/= io.b_eew, t._2, t._1))
  val sc_in = sc.zip(hwc).map(t => Mux(io.out_eew =/= io.c_eew, t._2, t._1))

  val ha_in = ha
  val hb_in = hb
  val hc_in = hc

  val s1_op = RegEnable(io.op, io.valid)
  val s1_frm = RegEnable(io.frm, io.valid)

  io.out := DontCare
  io.exc := DontCare

  if (!segmented) {
    (buildFP64.option((dfma_valid, FType.D, da_in, db_in, dc_in)) ++ Seq(
      (sfma_valid, FType.S, sa_in, sb_in, sc_in),
      (hfma_valid, FType.H, ha_in, hb_in, hc_in)
    )).foreach { case (fma_valid, ftype, a, b, c) => {
      val n = 64 / ftype.ieeeWidth
      val s1_valid = RegNext(fma_valid, false.B)
      val res = (0 until n).map { i =>
        val fma = Module(new MulAddRecFNPipe(depth-2, ftype.exp, ftype.sig))
        fma.io.validin      := s1_valid
        fma.io.op           := Mux(s1_valid, s1_op, 0.U)
        fma.io.roundingMode := Mux(s1_valid, s1_frm, 0.U)
        fma.io.detectTininess := hardfloat.consts.tininess_afterRounding
        fma.io.a := RegEnable(a(i), fma_valid)
        fma.io.b := RegEnable(Mux(io.addsub, 1.U << (ftype.ieeeWidth - 1), b(i)), fma_valid)
        fma.io.c := RegEnable(Mux(io.mul, (a(i) ^ b(i)) & (1.U << ftype.ieeeWidth), c(i)), fma_valid)

        val out = Pipe(fma.io.validout, ftype.ieee(fma.io.out), depth-4).bits
        val exc = Pipe(fma.io.validout, fma.io.exceptionFlags, depth-4).bits
        (out, Seq.fill(ftype.ieeeWidth / 8)(exc))
      }
      when (out_eew_pipe.bits === log2Ceil(ftype.ieeeWidth >> 3).U) {
        io.out := res.map(_._1).asUInt
        io.exc := res.map(_._2).flatten
      }
    }}
  } else {
    val s1_valid = RegNext(io.valid, false.B)
    val fma = Module(new SegmentedMulAddFNPipe(depth-2))
    fma.io.validin      := s1_valid
    fma.io.op           := Mux(s1_valid, s1_op, 0.U)
    fma.io.roundingMode := Mux(s1_valid, s1_frm, 0.U)
    fma.io.detectTininess := hardfloat.consts.tininess_afterRounding

    fma.io.da := (0 until 1).map(i => RegEnable(da(i), io.valid))
    fma.io.db := (0 until 1).map(i => RegEnable(Mux(io.addsub, 1.U << (FType.D.ieeeWidth - 1), db(i)), io.valid))
    fma.io.dc := (0 until 1).map(i => RegEnable(Mux(io.mul, (da(i) ^ db(i)) & (1.U << FType.D.ieeeWidth), dc(i)), io.valid))

    fma.io.sa := (0 until 2).map(i => RegEnable(sa(i), io.valid))
    fma.io.sb := (0 until 2).map(i => RegEnable(Mux(io.addsub, 1.U << (FType.S.ieeeWidth - 1), sb(i)), io.valid))
    fma.io.sc := (0 until 2).map(i => RegEnable(Mux(io.mul, (sa(i) ^ sb(i)) & (1.U << FType.S.ieeeWidth), sc(i)), io.valid))

    fma.io.ha := (0 until 4).map(i => RegEnable(ha(i), io.valid))
    fma.io.hb := (0 until 4).map(i => RegEnable(Mux(io.addsub, 1.U << (FType.H.ieeeWidth - 1), hb(i)), io.valid))
    fma.io.hc := (0 until 4).map(i => RegEnable(Mux(io.mul, (ha(i) ^ hb(i)) & (1.U << FType.H.ieeeWidth), hc(i)), io.valid))

    io.out := Pipe(fma.io.validout, FType.D.ieee(fma.io.out), depth-4).bits
    io.exc := Pipe(fma.io.validout, fma.io.exceptionFlags, depth-4).bits
  }
}

class SegmentedMulAddFNPipe(latency: Int) extends Module {
  override def desiredName = s"SegmentedMulAddRecFNPipe_l${latency}"
  require (latency <= 2)
  
  val io = IO(new Bundle {
    val validin = Input(Bool())
    val op = Input(Bits(2.W))
    val da = Input(Vec(1, Bits(65.W)))
    val db = Input(Vec(1, Bits(65.W)))
    val dc = Input(Vec(1, Bits(65.W)))
    val sa = Input(Vec(2, Bits(33.W)))
    val sb = Input(Vec(2, Bits(33.W)))
    val sc = Input(Vec(2, Bits(33.W)))
    val ha = Input(Vec(4, Bits(17.W)))
    val hb = Input(Vec(4, Bits(17.W)))
    val hc = Input(Vec(4, Bits(17.W)))
    val eew = Input(Bits(2.W))
    val roundingMode = Input(UInt(3.W))
    val detectTininess = Input(UInt(1.W))
    val out = Output(Bits(68.W))
    val exceptionFlags = Output(Bits(5.W))
    val validout = Output(Bool())
    val debug = Output(Bits(256.W))
  })

  // Rearrangement

  val a_sign = Wire(Bits(4.W))
  val a_exp = Wire(Bits(30.W))
  val a_sig = Wire(Bits(56.W))
  val b_sign = Wire(Bits(4.W))
  val b_exp = Wire(Bits(30.W))
  val b_sig = Wire(Bits(56.W))
  val c_sign = Wire(Bits(4.W))
  val c_exp = Wire(Bits(30.W))
  val c_sig = Wire(Bits(56.W))
  
  a_sign := DontCare
  a_exp := DontCare
  a_sig := DontCare
  b_sign := DontCare
  b_exp := DontCare
  b_sig := DontCare
  c_sign := DontCare
  c_exp := DontCare
  c_sig := DontCare

  val test = Seq(
    (io.da, io.sa, io.ha, a_sign, a_exp, a_sig),
    (io.db, io.sb, io.hb, b_sign, b_exp, b_sig),
    (io.dc, io.sc, io.hc, c_sign, c_exp, c_sig),
  ).foreach { case (dx, sx, hx, sign, exp, sig) => {
    when (io.eew === 3.U) {
      sign := Cat(0.asUInt(3.W), dx(0)(64))
      exp := Cat(0.asUInt(18.W), dx(0)(63, 52))
      sig := Cat(1.asUInt(1.W), dx(0)(51, 0))
    } .elsewhen (io.eew === 2.U) {
      sign := Cat(0.asUInt(1.W), sx(1)(32), 0.asUInt(1.W), sx(0)(32))
      exp := Cat(0.asUInt(10.W), sx(1)(31, 23), 0.asUInt(2.W), sx(0)(31, 23))
      sig := Cat(1.asUInt(5.W), sx(1)(22, 0), 1.asUInt(5.W), sx(0)(22, 0))
    } .elsewhen (io.eew === 1.U) {
      sign := Cat(hx(3)(16), hx(2)(16), hx(1)(16), hx(0)(16))
      exp := Cat(
        hx(3)(15, 10), 0.asUInt(2.W),
        hx(2)(15, 10), 0.asUInt(2.W),
        hx(1)(15, 10), 0.asUInt(2.W),
        hx(0)(15, 10)
      )
      sig := Cat(
        1.asUInt(4.W), hx(3)(9, 0),
        1.asUInt(4.W), hx(2)(9, 0),
        1.asUInt(4.W), hx(1)(9, 0),
        1.asUInt(4.W), hx(0)(9, 0)
      )
    }
  }} // TODO: Override sig for C

  // TODO: Exponent Processor

  val exp_ab = a_exp + b_exp

  // Mantissa Multiplier

  val sig_mul = Module(new MultiplierArray(4, 14, false, 1))
  sig_mul.io.in1_signed := DontCare
  sig_mul.io.in2_signed := DontCare
  sig_mul.io.in1 := a_sig
  sig_mul.io.in2 := b_sig
  sig_mul.io.eew := io.eew
  
  // Misc.

  val sign_ab = a_sign ^ b_sign ^ Fill(4, io.op(1))

  io.debug := sig_mul.io.out
  
  io.out := DontCare
  io.exceptionFlags := DontCare
  io.validout := DontCare
}

trait FMAFactory extends FunctionalUnitFactory {
  def depth: Int
  def base_insns = Seq(
    FADD.VV, FADD.VF, FSUB.VV, FSUB.VF, FRSUB.VF,
    FMUL.VV, FMUL.VF,
    FMACC.VV, FMACC.VF, FNMACC.VV, FNMACC.VF,
    FMSAC.VV, FMSAC.VF, FNMSAC.VV, FNMSAC.VF,
    FMADD.VV, FMADD.VF, FNMADD.VV, FNMADD.VF,
    FMSUB.VV, FMSUB.VF, FNMSUB.VV, FNMSUB.VF,
    FWADD.VV, FWADD.VF, FWSUB.VV, FWSUB.VF,
    FWADDW.VV, FWADDW.VF, FWSUBW.VV, FWSUBW.VF,
    FWMUL.VV, FWMUL.VF,
    FWMACC.VV, FWMACC.VF, FWNMACC.VV, FWNMACC.VF,
    FWMSAC.VV, FWMSAC.VF, FWNMSAC.VV, FWNMSAC.VF,
    FREDOSUM.VV, FREDUSUM.VV, FWREDOSUM.VV, FWREDUSUM.VV
  ).map(_.pipelined(depth)).map(_.restrictSEW(1,2,3)).flatten
}

case class SIMDFPFMAFactory(depth: Int, elementWiseFP64: Boolean = false, segmented: Boolean = false) extends FMAFactory {
  def insns = if (elementWiseFP64) {
    base_insns.map { insn =>
      if (insn.lookup(SEW).value == 3 || (insn.lookup(SEW).value == 2 && insn.lookup(Wide2VD).value == 1)) {
        insn.elementWise
      } else {
        insn
      }
    }
  } else {
    base_insns
  }
  def generate(implicit p: Parameters) = new FPFMAPipe(depth, elementWiseFP64, segmented)(p)
}

class FPFMAPipe(depth: Int, elementwiseFP64: Boolean, segmented: Boolean)(implicit p: Parameters) extends PipelinedFunctionalUnit(depth)(p) with HasFPUParameters {
  val supported_insns = SIMDFPFMAFactory(depth, elementwiseFP64).insns

  io.stall := false.B
  io.set_vxsat := false.B

  val ctrl = new VectorDecoder(io.pipe(0).bits, supported_insns, Seq(
    FPAdd, FPMul, FPSwapVdV2, FPFMACmd))

  val vs1_eew = io.pipe(0).bits.rvs1_eew
  val vs2_eew = io.pipe(0).bits.rvs2_eew
  val vd_eew  = io.pipe(0).bits.vd_eew
  val ctrl_widen_vs2 = vs2_eew =/= vd_eew
  val ctrl_widen_vs1 = vs1_eew =/= vd_eew
  val wmask = io.pipe(0).bits.wmask

  val nTandemFMA = dLenB / 8

  val eidx = Mux(io.pipe(0).bits.acc, 0.U, io.pipe(0).bits.eidx)
  val one_bits = Mux1H(Seq(vd_eew === 3.U, vd_eew === 2.U, vd_eew === 1.U),
                       Seq("h3FF0000000000000".U, "h3F8000003F800000".U, "h3C003C003C003C00".U))
  val fmaCmd = ctrl.uint(FPFMACmd)

  val vec_rvs1 = io.pipe(0).bits.rvs1_data.asTypeOf(Vec(nTandemFMA, UInt(64.W)))
  val vec_rvs2 = io.pipe(0).bits.rvs2_data.asTypeOf(Vec(nTandemFMA, UInt(64.W)))
  val vec_rvd = io.pipe(0).bits.rvd_data.asTypeOf(Vec(nTandemFMA, UInt(64.W)))

  val pipe_out = (0 until nTandemFMA).map { i =>
    val fma_pipe = Module(new TandemFMAPipe(depth, i == 0 || !elementwiseFP64, segmented))
    val widening_vs1_bits = Mux(vd_eew === 3.U,
      0.U(32.W) ## extractElem(io.pipe(0).bits.rvs1_data, 2.U, eidx + i.U)(31,0),
      Cat(
        0.U(16.W),
        extractElem(io.pipe(0).bits.rvs1_data, 1.U, eidx + (i << 1).U + 1.U)(15,0),
        0.U(16.W),
        extractElem(io.pipe(0).bits.rvs1_data, 1.U, eidx + (i << 1).U + 0.U)(15,0)
      )
    )
    val vs1_bits = Mux((i == 0 && elementwiseFP64).B && vd_eew === 3.U,
      Mux(ctrl_widen_vs1, 0.U(32.W) ## io.pipe(0).bits.rvs1_elem(31,0), io.pipe(0).bits.rvs1_elem),
      Mux(ctrl_widen_vs1, widening_vs1_bits, vec_rvs1(i))
    )

    val widening_vs2_bits = Mux(vd_eew === 3.U,
      0.U(32.W) ## extractElem(io.pipe(0).bits.rvs2_data, 2.U, eidx + i.U)(31,0),
      Cat(
        0.U(16.W),
        extractElem(io.pipe(0).bits.rvs2_data, 1.U, eidx + (i << 1).U + 1.U)(15,0),
        0.U(16.W),
        extractElem(io.pipe(0).bits.rvs2_data, 1.U, eidx + (i << 1).U + 0.U)(15,0)
      )
    )
    val vs2_bits = Mux((i == 0 && elementwiseFP64).B && vd_eew === 3.U,
      Mux(ctrl_widen_vs2, 0.U(32.W) ## io.pipe(0).bits.rvs2_elem(32,0), io.pipe(0).bits.rvs2_elem),
      Mux(ctrl_widen_vs2, widening_vs2_bits, vec_rvs2(i))
    )

    val vs3_bits = Mux((i == 0 && elementwiseFP64).B && vd_eew === 3.U,
      io.pipe(0).bits.rvd_elem,
      vec_rvd(i)
    )

    fma_pipe.io.addsub := ctrl.bool(FPAdd) && !ctrl.bool(FPMul)
    fma_pipe.io.mul := ctrl.bool(FPMul) && !ctrl.bool(FPAdd)
    fma_pipe.io.out_eew := vd_eew

    // FMA
    when (ctrl.bool(FPMul) && ctrl.bool(FPAdd)) {
      fma_pipe.io.b     := vs1_bits
      fma_pipe.io.b_eew := vs1_eew
      when (ctrl.bool(FPSwapVdV2)) {
        fma_pipe.io.a     := vs3_bits
        fma_pipe.io.a_eew := vd_eew
        fma_pipe.io.c     := vs2_bits
        fma_pipe.io.c_eew := vs2_eew
      } .otherwise {
        fma_pipe.io.a     := vs2_bits
        fma_pipe.io.a_eew := vs2_eew
        fma_pipe.io.c     := vs3_bits
        fma_pipe.io.c_eew := vd_eew
      }
    }
    // Multiply
    .elsewhen (ctrl.bool(FPMul)) {
      fma_pipe.io.a     := vs2_bits
      fma_pipe.io.a_eew := vs2_eew
      fma_pipe.io.b     := vs1_bits
      fma_pipe.io.b_eew := vs1_eew
      fma_pipe.io.c     := 0.U
      fma_pipe.io.c_eew := vs2_eew
    }
    // Add type
    .elsewhen (ctrl.bool(FPAdd)) {
      fma_pipe.io.a     := vs2_bits
      fma_pipe.io.a_eew := vs2_eew
      fma_pipe.io.b     := one_bits
      fma_pipe.io.b_eew := vd_eew
      fma_pipe.io.c     := vs1_bits
      fma_pipe.io.c_eew := vs1_eew
    } .otherwise {
      fma_pipe.io.a     := 0.U
      fma_pipe.io.a_eew := 0.U
      fma_pipe.io.b     := 0.U
      fma_pipe.io.b_eew := 0.U
      fma_pipe.io.c     := 0.U
      fma_pipe.io.c_eew := 0.U
    }


    fma_pipe.io.valid := io.pipe(0).valid
    fma_pipe.io.frm := io.pipe(0).bits.frm
    fma_pipe.io.op := fmaCmd

    fma_pipe.io
  }

  io.write.valid := io.pipe(depth-1).valid
  io.write.bits.eg := io.pipe(depth-1).bits.wvd_eg
  io.write.bits.mask := FillInterleaved(8, io.pipe(depth-1).bits.wmask)
  io.write.bits.data := pipe_out.map(_.out).asUInt

  when (elementwiseFP64.B && io.pipe(depth-1).bits.vd_eew === 3.U) {
    io.write.bits.data := Fill(dLenB >> 3, pipe_out(0).out)
  }

  io.set_fflags.valid := io.write.valid
  io.set_fflags.bits := pipe_out.map(_.exc).flatten.zipWithIndex.map {
    case (e,i) => Mux(io.pipe(depth-1).bits.wmask(i), e, 0.U)
  }.reduce(_|_)
  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
}
