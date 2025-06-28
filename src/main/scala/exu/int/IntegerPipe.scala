package saturn.exu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import saturn.common._
import saturn.insns._

class AdderArray(dLenB: Int, maxSizeB: Int = 8, bSize: Int = 8, start_eew: Int = 0, no_rm: Boolean = false, no_incr: Boolean = false) extends Module {
  val levels = log2Ceil(maxSizeB) + 1
  val io = IO(new Bundle {
    val in1 = Input(Vec(dLenB, UInt(bSize.W)))
    val in2 = Input(Vec(dLenB, UInt(bSize.W)))
    val incr = Input(Vec(dLenB, Bool()))
    val mask_carry = Input(UInt(dLenB.W))

    val signed    = Input(Bool())
    val eew       = Input(UInt(log2Ceil(levels + start_eew).W))
    val avg       = Input(Bool())
    val rm        = Input(UInt(2.W))
    val sub       = Input(Bool())
    val cmask     = Input(Bool())

    val out   = Output(Vec(dLenB, UInt(bSize.W)))
    val carry = Output(Vec(dLenB, Bool()))
  })

  val use_carry = VecInit.tabulate(levels)({ eew =>
    Fill(dLenB >> eew, ~(1.U((1 << eew).W)))
  })(io.eew - start_eew.U)
  val carry_clear = Mux(io.avg, use_carry.asBools.map(Cat(~(0.U(bSize.W)), _)).asUInt, ~(0.U((dLenB * (bSize + 1) + 1).W)))
  val carry_restore = Mux(io.avg, use_carry.asBools.map(Cat(0.U(bSize.W), _)).asUInt, 0.U((dLenB * (bSize + 1) + 1).W))

  val avg_in1 = VecInit.tabulate(levels) { eew =>
    VecInit(io.in1.asTypeOf(Vec(dLenB >> eew, UInt((bSize << eew).W))).map(e => Cat(io.signed && e((bSize<<eew)-1), e) >> 1)).asUInt
  }(io.eew - start_eew.U).asTypeOf(Vec(dLenB, UInt(bSize.W)))
  val avg_in2 = VecInit.tabulate(levels) { eew =>
    VecInit(io.in2.asTypeOf(Vec(dLenB >> eew, UInt((bSize << eew).W))).map(e => Cat(io.signed && e((bSize<<eew)-1), e) >> 1)).asUInt
  }(io.eew - start_eew.U).asTypeOf(Vec(dLenB, UInt(bSize.W)))

  val in1 = Mux(io.avg, avg_in1, io.in1)
  val in2 = Mux(io.avg, avg_in2, io.in2)

  for (i <- 0 until (dLenB / maxSizeB)) {
    val h = (i+1)*maxSizeB-1
    val l = i*maxSizeB
    val io_in1_slice = io.in1.slice(l,h+1)
    val io_in2_slice = io.in2.slice(l,h+1)
    val in1_slice = in1.slice(l,h+1)
    val in2_slice = in2.slice(l,h+1)
    val use_carry_slice = use_carry(h,l).asBools
    val mask_carry_slice = io.mask_carry(h,l).asBools
    val incr_slice = io.incr.slice(l,h+1)

    val in1_dummy_bits = (io_in1_slice
      .zip(io_in2_slice)
      .zip(use_carry_slice)
      .zip(mask_carry_slice).map { case(((i1, i2), carry), mask_bit) => {
        val avg_bit = ((io.sub ^ i1(0)) & i2(0)) | (((io.sub ^ i1(0)) ^ i2(0)) & io.sub)
        val bit = (!io.cmask & io.sub) | (io.cmask & (io.sub ^ mask_bit))
        Mux(carry, 1.U(1.W), Mux(io.avg, avg_bit, bit))
      }})
    val in2_dummy_bits = (io_in1_slice
      .zip(io_in2_slice)
      .zip(use_carry_slice)
      .zip(mask_carry_slice).map { case(((i1, i2), carry), mask_bit) => {
        val avg_bit = ((io.sub ^ i1(0)) & i2(0)) | (((io.sub ^ i1(0)) ^ i2(0)) & io.sub)
        val bit = (!io.cmask & io.sub) | (io.cmask & (io.sub ^ mask_bit))
        Mux(carry, 0.U(1.W), Mux(io.avg, avg_bit, bit))
      }})
    
    val round_incrs = if (no_rm) 0.U else (io_in1_slice
      .zip(io_in2_slice)
      .zipWithIndex.map { case((l, r), i) => {
        val sum = r(1,0) +& ((l(1,0) ^ Fill(2, io.sub)) +& io.sub)
        Cat(0.U((bSize-1).W), Cat(Mux(io.avg, RoundingIncrement(io.rm, sum(1), sum(0), None) & !use_carry_slice(i), 0.U), 0.U(1.W)))
      }}
      .asUInt)


    val in1_constructed = in1_slice.zip(in1_dummy_bits).map { case(i1, dummy_bit) => (i1 ^ Fill(bSize, io.sub)) ## dummy_bit }.asUInt
    val in2_constructed = in2_slice.zip(in2_dummy_bits).map { case(i2, dummy_bit) => i2 ## dummy_bit }.asUInt

    val incr_constructed = if (no_incr) 0.U else incr_slice.zip(use_carry_slice).map { case(incr, masking) => Cat(0.U((bSize-1).W), Cat(Mux(!masking, incr, 0.U(1.W)), 0.U(1.W))) }.asUInt

    val sum = (((in1_constructed +& in2_constructed) & carry_clear) | carry_restore) +& round_incrs +& incr_constructed

    for (j <- 0 until maxSizeB) {
      io.out((i*maxSizeB) + j) := sum(((j+1)*(bSize+1))-1, (j*(bSize+1)) + 1)
      io.carry((i*maxSizeB) + j) := sum((j+1)*(bSize+1))
    }
  }
}

class CompareArray(dLenB: Int) extends Module {
  val io = IO(new Bundle {
    val in1 = Input(Vec(dLenB, UInt(8.W)))
    val in2 = Input(Vec(dLenB, UInt(8.W)))
    val eew = Input(UInt(2.W))
    val signed = Input(Bool())
    val less   = Input(Bool())
    val sle    = Input(Bool())
    val inv    = Input(Bool())

    val minmax = Output(UInt(dLenB.W))
    val result = Output(UInt(dLenB.W))
  })

  val eq = io.in2.zip(io.in1).map { x => x._1 === x._2 }
  val lt = io.in2.zip(io.in1).map { x => x._1  <  x._2 }

  val minmax_bits = Wire(Vec(4, UInt(dLenB.W)))
  val result_bits  = Wire(Vec(4, UInt(dLenB.W)))

  io.minmax := minmax_bits(io.eew)
  io.result := result_bits(io.eew)

  for (eew <- 0 until 4) {
    val lts = lt.grouped(1 << eew)
    val eqs = eq.grouped(1 << eew)
    val bits = VecInit(lts.zip(eqs).zipWithIndex.map { case ((e_lts, e_eqs), i) =>
      val eq = e_eqs.andR
      val in1_hi = io.in1((i+1)*(1<<eew)-1)(7)
      val in2_hi = io.in2((i+1)*(1<<eew)-1)(7)
      val hi_lt = Mux(io.signed, in2_hi & !in1_hi, !in2_hi & in1_hi)
      val hi_eq = in1_hi === in2_hi
      val lt = (e_lts :+ hi_lt).zip(e_eqs :+ hi_eq).foldLeft(false.B) { case (p, (l, e)) => l || (e && p) }
      Mux(io.less, lt || (io.sle && eq), io.inv ^ eq)
    }.toSeq).asUInt
    minmax_bits(eew) := FillInterleaved(1 << eew, bits)
    result_bits(eew) := Fill(1 << eew, bits)
  }
}

class SaturatedSumArray(dLenB: Int) extends Module {
  val dLen = dLenB * 8
  val io = IO(new Bundle {
    val sum      = Input(Vec(dLenB, UInt(8.W)))
    val carry    = Input(Vec(dLenB, Bool()))
    val in1_sign = Input(Vec(dLenB, Bool()))
    val in2_sign = Input(Vec(dLenB, Bool()))
    val sub      = Input(Bool())
    val eew      = Input(UInt(2.W))
    val signed   = Input(Bool())

    val set_vxsat = Output(UInt(dLenB.W))
    val out       = Output(Vec(dLenB, UInt(8.W)))
  })

  val unsigned_mask = VecInit.tabulate(4)({ eew =>
    FillInterleaved(1 << eew, VecInit.tabulate(dLenB >> eew)(i => io.sub ^ io.carry(((i+1) << eew)-1)).asUInt)
  })(io.eew)
  val unsigned_clip = Mux(io.sub, 0.U(dLen.W), ~(0.U(dLen.W))).asTypeOf(Vec(dLenB, UInt(8.W)))

  val (signed_masks, signed_clips): (Seq[UInt], Seq[UInt]) = Seq.tabulate(4)({ eew =>
    val out_sign = VecInit.tabulate(dLenB >> eew)(i =>      io.sum(((i+1)<<eew)-1)(7)).asUInt
    val vs2_sign = VecInit.tabulate(dLenB >> eew)(i => io.in2_sign(((i+1)<<eew)-1)   ).asUInt
    val vs1_sign = VecInit.tabulate(dLenB >> eew)(i => io.in1_sign(((i+1)<<eew)-1)   ).asUInt
    val input_xor  = vs2_sign ^ vs1_sign
    val may_clip   = Mux(io.sub, input_xor, ~input_xor) // add clips when signs match, sub clips when signs mismatch
    val clip       = (vs2_sign ^ out_sign) & may_clip   // clips if the output sign doesn't match the input sign
    val clip_neg   = Cat(1.U, 0.U(((8 << eew)-1).W))
    val clip_pos   = ~clip_neg
    val clip_value = VecInit(vs2_sign.asBools.map(sign => Mux(sign, clip_neg, clip_pos))).asUInt
    (FillInterleaved((1 << eew), clip), clip_value)
  }).unzip
  val signed_mask = VecInit(signed_masks)(io.eew)
  val signed_clip = VecInit(signed_clips)(io.eew).asTypeOf(Vec(dLenB, UInt(8.W)))

  val mask = Mux(io.signed, signed_mask, unsigned_mask)
  val clip = Mux(io.signed, signed_clip, unsigned_clip)
  io.out := io.sum.zipWithIndex.map { case (o,i) => Mux(mask(i), clip(i), o) }
  io.set_vxsat := mask
}

class MultiplierArray(dLenB: Int, bSize: Int = 8, signed: Boolean = true, start_eew: Int = 0) extends Module {
  val levels = log2Ceil(dLenB) + 1
  val dLen = dLenB * bSize
  val io = IO(new Bundle {
    val in1_signed = Input(Bool())
    val in2_signed = Input(Bool())

    val in1 = Input(Vec(dLenB, UInt(bSize.W)))
    val in2 = Input(Vec(dLenB, UInt(bSize.W)))

    val eew = Input(UInt(log2Ceil(levels + start_eew).W))

    val out = Output(Vec(dLenB, UInt((bSize*2).W)))
  })

  val in1_negative = Wire(Vec(dLenB, Bool()))
  val in2_negative = Wire(Vec(dLenB, Bool()))
  in1_negative := DontCare
  in2_negative := DontCare

  val segments1 = Wire(Vec(dLenB, UInt(bSize.W)))
  val segments2 = Wire(Vec(dLenB, UInt(bSize.W)))

  val negation_cin = VecInit.tabulate(levels)({ eew =>
    Fill(dLenB >> eew, 1.U(((1 << eew) * bSize).W))
  })(io.eew - start_eew.U).asTypeOf(Vec(dLenB, UInt(bSize.W)))

  Seq(
    (io.in1_signed, io.in1, segments1, in1_negative),
    (io.in2_signed, io.in2, segments2, in2_negative)
  ).foreach { case (in_signed, in, out, negative) => {
    if (signed) {
      negative.zipWithIndex.foreach { case (e, i) => e := in_signed && in(i)(bSize - 1) }
      
      val negator = Module(new AdderArray(dLenB, bSize=bSize, start_eew=start_eew, no_rm=true, no_incr=true))
      negator.io.in1 := in.map(~_)
      negator.io.in2 := negation_cin
      negator.io.incr := DontCare
      negator.io.mask_carry := 0.U
      negator.io.signed := DontCare
      negator.io.eew := io.eew
      negator.io.avg := false.B
      negator.io.rm := DontCare
      negator.io.sub := false.B
      negator.io.cmask := false.B

      out.zipWithIndex.foreach { case (e, i) =>
        e := DontCare
        for (l <- 0 until levels) {
          when (io.eew === (l + start_eew).U) {
            e := Mux(negative(i | ((1 << l) - 1)), negator.io.out(i), in(i))
          }
        }
      }
    } else {
      out := in
    }
  }}

  val partial_single = Wire(Vec(dLenB, UInt((bSize*2).W)))
  for (i <- 0 until dLenB) {
    partial_single(i) := segments1(i) * segments2(i)
  }

  val partial_double = Wire(Vec(dLenB, Vec(dLenB-1, UInt((bSize*2+2).W))))
  for (i <- 0 until dLenB) {
    for (j <- 0 until i) {
      val sum1 = segments1(i) +& segments1(j)
      val sum2 = segments2(i) +& segments2(j)
      partial_double(i)(j) := sum1 * sum2
    }
    for (j <- i until (dLenB - 1)) {
      partial_double(i)(j) := DontCare
    }
  }

  val output_levels = (0 until levels).map(i => Wire(Vec(dLenB / (1 << i), UInt((bSize * (1 << i) * 2).W))))

  for (i <- 0 until dLenB) {
    output_levels(0)(i) := partial_single(i)
  }
  for (l <- 1 until levels) {
    val elements_count = 1 << l
    val result_width = bSize * elements_count * 2
    for (i <- 0 until (1 << (levels - l - 1))) {
      output_levels(l)(i) := ((elements_count / 2 until elements_count).map(a =>
        (0 until elements_count / 2).map(b => Seq(
          ~(partial_single(a + i * elements_count) << ((a + b) * bSize)).asTypeOf(UInt(result_width.W)),
          ~(partial_single(b + i * elements_count) << ((a + b) * bSize)).asTypeOf(UInt(result_width.W)),
          partial_double(a + i * elements_count)(b + i * elements_count) << ((a + b) * bSize)
        )).flatten
      ).flatten ++ Seq(
        output_levels(l - 1)(i * 2),
        output_levels(l - 1)(i * 2 + 1) << (result_width / 2),
        (elements_count * elements_count / 2).U
      )).foldLeft(0.U) { (acc, e) =>
        acc +& e
      }
    }
  }

  val unsigned_out = Wire(Vec(dLenB, UInt((bSize*2).W)))

  unsigned_out := DontCare
  for (i <- 0 until levels) {
    when (io.eew === (i + start_eew).U) {
      unsigned_out := output_levels(i).asTypeOf(Vec(dLenB, UInt((bSize*2).W)))
    }
  }

  val negation_cin_out = VecInit.tabulate(levels)({ eew =>
    Fill(dLenB >> eew, 1.U(((1 << eew) * bSize * 2).W))
  })(io.eew - start_eew.U).asTypeOf(Vec(dLenB, UInt((bSize*2).W)))

  if (signed) {
    val negative_out = in1_negative.zip(in2_negative).map { case (a, b) => a ^ b }
    val negator = Module(new AdderArray(dLenB, bSize=bSize*2, start_eew=start_eew, no_rm=true, no_incr=true))
    negator.io.in1 := unsigned_out.map(~_)
    negator.io.in2 := negation_cin_out
    negator.io.incr := DontCare
    negator.io.mask_carry := 0.U
    negator.io.signed := DontCare
    negator.io.eew := io.eew
    negator.io.avg := false.B
    negator.io.rm := DontCare
    negator.io.sub := false.B
    negator.io.cmask := false.B

    io.out.zipWithIndex.foreach { case (e, i) =>
      e := DontCare
      for (l <- 0 until levels) {
        when (io.eew === (l + start_eew).U) {
          e := Mux(negative_out(i | ((1 << l) - 1)), negator.io.out(i), unsigned_out(i))
        }
      }
    }
  } else {
    io.out := unsigned_out
  }
}

case object IntegerPipeFactory extends FunctionalUnitFactory {

  def baseInsns = Seq(
    ADD.VV, ADD.VX, ADD.VI, SUB.VV, SUB.VX, RSUB.VX, RSUB.VI,
    ADC.VV, ADC.VX, ADC.VI, MADC.VV, MADC.VX, MADC.VI,
    SBC.VV, SBC.VX, MSBC.VV, MSBC.VX,
    NEXT.VV, // TODO these don't support all SEWs
    MSEQ.VV, MSEQ.VX, MSEQ.VI, MSNE.VV, MSNE.VX, MSNE.VI,
    MSLTU.VV, MSLTU.VX, MSLT.VV, MSLT.VX,
    MSLEU.VV, MSLEU.VX, MSLEU.VI, MSLE.VV, MSLE.VX, MSLE.VI,
    MSGTU.VX, MSGTU.VI, MSGT.VX, MSGT.VI,
    MINU.VV, MINU.VX, MIN.VV, MIN.VX,
    MAXU.VV, MAXU.VX, MAX.VV, MAX.VX,
    MERGE.VV, MERGE.VX, MERGE.VI,
    AADDU.VV, AADDU.VX, AADD.VV, AADD.VX,
    ASUBU.VV, ASUBU.VX, ASUB.VV, ASUB.VX,
    REDSUM.VV,
    REDMINU.VV, REDMIN.VV, REDMAXU.VV, REDMAX.VV,
    FMERGE.VF,
  )

  // These support only SEW=0/1/2
  def wideningInsns = Seq(
    WADDU.VV, WADDU.VX, WADD.VV, WADD.VX, WSUBU.VV, WSUBU.VX, WSUB.VV, WSUB.VX,
    WADDUW.VV, WADDUW.VX, WADDW.VV, WADDW.VX, WSUBUW.VV, WSUBUW.VX, WSUBW.VV, WSUBW.VX,
    WREDSUM.VV, WREDSUMU.VV,
  ).map(_.restrictSEW(0, 1, 2)).flatten

  def satInsns = Seq(
    SADDU.VV, SADDU.VX, SADDU.VI, SADD.VV, SADD.VX, SADD.VI,
    SSUBU.VV, SSUBU.VX, SSUB.VV, SSUB.VX,
  )

  def insns = (baseInsns ++ wideningInsns).map(_.pipelined(1)) ++ satInsns.map(_.pipelined(2))

  def generate(implicit p: Parameters) = new IntegerPipe()(p)
}

class IntegerPipe(implicit p: Parameters) extends PipelinedFunctionalUnit(2)(p) {
  val supported_insns = IntegerPipeFactory.insns

  val rvs1_eew = io.pipe(0).bits.rvs1_eew
  val rvs2_eew = io.pipe(0).bits.rvs2_eew
  val vd_eew   = io.pipe(0).bits.vd_eew

  val iss_ctrl = Wire(new VectorDecodedControl(
    supported_insns,
    Seq(WideningSext, UsesCmp, UsesNarrowingSext, UsesMinMax, UsesMerge, UsesSat,
      DoSub, Averaging,
      CarryIn, AlwaysCarryIn, CmpLess, Swap12, WritesAsMask)
  )).decode(io.iss.op)

  val ctrl = RegEnable(iss_ctrl, io.iss.valid)

  io.stall := false.B

  val carry_in = ctrl.bool(CarryIn) && (!io.pipe(0).bits.vm || ctrl.bool(AlwaysCarryIn))

  val iss_rvs1_bytes = io.iss.op.rvs1_data.asTypeOf(Vec(dLenB, UInt(8.W)))
  val iss_rvs2_bytes = io.iss.op.rvs2_data.asTypeOf(Vec(dLenB, UInt(8.W)))

  val rvs1_bytes = io.pipe(0).bits.rvs1_data.asTypeOf(Vec(dLenB, UInt(8.W)))
  val rvs2_bytes = io.pipe(0).bits.rvs2_data.asTypeOf(Vec(dLenB, UInt(8.W)))

  val in1_bytes = Mux(ctrl.bool(Swap12), rvs2_bytes, rvs1_bytes)
  val in2_bytes = Mux(ctrl.bool(Swap12), rvs1_bytes, rvs2_bytes)

  val narrow_vs1 = RegEnable(narrow2_expand(iss_rvs1_bytes, io.iss.op.rvs1_eew,
    (io.iss.op.eidx >> (dLenOffBits.U - io.iss.op.vd_eew))(0),
    iss_ctrl.bool(WideningSext)), io.iss.valid)
  val narrow_vs2 = RegEnable(narrow2_expand(iss_rvs2_bytes, io.iss.op.rvs2_eew,
    (io.iss.op.eidx >> (dLenOffBits.U - io.iss.op.vd_eew))(0),
    iss_ctrl.bool(WideningSext)), io.iss.valid)

  val add_mask_carry = VecInit.tabulate(4)({ eew =>
    VecInit((0 until dLenB >> eew).map { i => io.pipe(0).bits.rmask(i) | 0.U((1 << eew).W) }).asUInt
  })(rvs2_eew)
  val add_carry = Wire(Vec(dLenB, UInt(1.W)))
  val add_out = Wire(Vec(dLenB, UInt(8.W)))

  val merge_mask = VecInit.tabulate(4)({eew => FillInterleaved(1 << eew, io.pipe(0).bits.rmask((dLenB >> eew)-1,0))})(rvs2_eew)
  val merge_out  = VecInit((0 until dLenB).map { i => Mux(merge_mask(i), rvs1_bytes(i), rvs2_bytes(i)) }).asUInt

  val carryborrow_res = VecInit.tabulate(4)({ eew =>
    Fill(1 << eew, VecInit(add_carry.grouped(1 << eew).map(_.last).toSeq).asUInt)
  })(rvs1_eew)

  val adder_arr = Module(new AdderArray(dLenB, no_incr=true))
  adder_arr.io.in1 := Mux(rvs1_eew < vd_eew, narrow_vs1, in1_bytes)
  adder_arr.io.in2 := Mux(rvs2_eew < vd_eew, narrow_vs2, in2_bytes)
  adder_arr.io.incr := DontCare
  adder_arr.io.avg := ctrl.bool(Averaging)
  adder_arr.io.eew := vd_eew
  adder_arr.io.rm  := io.pipe(0).bits.vxrm
  adder_arr.io.mask_carry := add_mask_carry
  adder_arr.io.sub        := ctrl.bool(DoSub)
  adder_arr.io.cmask      := carry_in
  adder_arr.io.signed     := io.pipe(0).bits.funct6(0)
  add_out   := adder_arr.io.out
  add_carry := adder_arr.io.carry

  val cmp_arr = Module(new CompareArray(dLenB))
  cmp_arr.io.in1 := in1_bytes
  cmp_arr.io.in2 := in2_bytes
  cmp_arr.io.eew := rvs1_eew
  cmp_arr.io.signed := io.pipe(0).bits.funct6(0)
  cmp_arr.io.less   := ctrl.bool(CmpLess)
  cmp_arr.io.sle    := io.pipe(0).bits.funct6(2,1) === 2.U
  cmp_arr.io.inv    := io.pipe(0).bits.funct6(0)
  val minmax_out = VecInit(rvs1_bytes.zip(rvs2_bytes).zip(cmp_arr.io.minmax.asBools).map { case ((v1, v2), s) => Mux(s, v2, v1) }).asUInt

  val mask_out = Fill(8, Mux(ctrl.bool(UsesCmp), cmp_arr.io.result, carryborrow_res ^ Fill(dLenB, ctrl.bool(DoSub))))

  val sat_arr = Module(new SaturatedSumArray(dLenB))
  sat_arr.io.sum      := RegEnable(add_out, io.pipe(0).valid && ctrl.bool(UsesSat))
  sat_arr.io.carry    := RegEnable(add_carry, io.pipe(0).valid && ctrl.bool(UsesSat))
  sat_arr.io.in1_sign := io.pipe(1).bits.rvs1_data.asTypeOf(Vec(dLenB, UInt(8.W))).map(_(7))
  sat_arr.io.in2_sign := io.pipe(1).bits.rvs2_data.asTypeOf(Vec(dLenB, UInt(8.W))).map(_(7))
  sat_arr.io.sub      := RegEnable(ctrl.bool(DoSub), io.pipe(0).valid && ctrl.bool(UsesSat))
  sat_arr.io.eew      := io.pipe(1).bits.vd_eew
  sat_arr.io.signed   := io.pipe(1).bits.funct6(0)
  val sat_out = sat_arr.io.out.asUInt

  val narrowing_ext_eew_mul = io.pipe(0).bits.vd_eew - rvs2_eew
  val narrowing_ext_in = (1 until 4).map { m =>
    val w = dLen >> m
    val in = Wire(UInt(w.W))
    val in_mul = io.pipe(0).bits.rvs2_data.asTypeOf(Vec(1 << m, UInt(w.W)))
    val sel = (io.pipe(0).bits.eidx >> (dLenOffBits.U - vd_eew))(m-1,0)
    in := in_mul(sel)
    in
  }
  val narrowing_ext_out = Mux1H((1 until 4).map { eew => (0 until eew).map { vs2_eew =>
    (vd_eew === eew.U && rvs2_eew === vs2_eew.U) -> {
      val mul = eew - vs2_eew
      val in = narrowing_ext_in(mul-1).asTypeOf(Vec(dLenB >> eew, UInt((8 << vs2_eew).W)))
      val out = Wire(Vec(dLenB >> eew, UInt((8 << eew).W)))
      out.zip(in).foreach { case (l, r) => l := Cat(
        Fill((8 << eew) - (8 << vs2_eew), io.pipe(0).bits.rs1(0) && r((8 << vs2_eew)-1)),
        r)
      }
      out.asUInt
    }
  }}.flatten)

  val outs = Seq(
    (ctrl.bool(UsesNarrowingSext)        , narrowing_ext_out),
    (ctrl.bool(WritesAsMask)             , mask_out),
    (ctrl.bool(UsesMinMax)               , minmax_out),
    (ctrl.bool(UsesMerge)                , merge_out),
  )

  val out = Mux(outs.map(_._1).orR, Mux1H(outs), add_out.asUInt)

  assert(!(io.pipe(1).valid && io.pipe(0).valid && !ctrl.bool(UsesSat)))

  val mask_write_offset = VecInit.tabulate(4)({ eew =>
    Cat(io.pipe(0).bits.eidx(log2Ceil(dLen)-1, dLenOffBits-eew), 0.U((dLenOffBits-eew).W))
  })(rvs1_eew)
  val mask_write_mask = (VecInit.tabulate(4)({ eew =>
    VecInit(io.pipe(0).bits.wmask.asBools.grouped(1 << eew).map(_.head).toSeq).asUInt
  })(rvs1_eew) << mask_write_offset)(dLen-1,0)

  io.write.valid     := io.pipe(1).valid || (io.pipe(0).valid && !ctrl.bool(UsesSat))
  io.write.bits.eg   := Mux(io.pipe(1).valid, io.pipe(1).bits.wvd_eg, io.pipe(0).bits.wvd_eg)
  io.write.bits.mask := Mux(io.pipe(1).valid,
    FillInterleaved(8, io.pipe(1).bits.wmask),
    Mux(ctrl.bool(WritesAsMask), mask_write_mask, FillInterleaved(8, io.pipe(0).bits.wmask)))
  io.write.bits.data := Mux(io.pipe(1).valid, sat_out.asUInt, out)

  val sat_vxsat   = Mux(io.pipe(1).valid, sat_arr.io.set_vxsat, 0.U) & io.pipe(1).bits.wmask
  io.set_vxsat := io.pipe(1).valid && (sat_vxsat =/= 0.U)
  io.set_fflags.valid := false.B
  io.set_fflags.bits := DontCare

  io.scalar_write.valid := false.B
  io.scalar_write.bits := DontCare
}
