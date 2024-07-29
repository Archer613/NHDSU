package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, Fill, PopCount, PriorityEncoder, Valid, is, switch}
import org.chipsalliance.cde.config._

class BTWayWithTxnidBundle(implicit p: Parameters) extends DSUBundle {
  val valid   = Bool()
  val addr    = UInt(addressBits.W)
  val btWay   = UInt(blockWayBits.W)
  val txnid   = UInt(chiTxnidBits.W)
}

class DsuChiTxDat()(implicit p: Parameters) extends DSUModule {
  // TODO: The architecture of txDat can be optimized
  val io = IO(new Bundle {
    val chi       = CHIChannelIO(new CHIBundleDAT(chiBundleParams))
    val rspResp   = Input(new RxRespBundle())
    val btWay     = Input(new BTWayWithTxnidBundle())
    val txState   = Input(UInt(LinkStates.width.W))
    val dataFDB   = Flipped(Decoupled(new MsDBOutData()))
    val mpResp    = Decoupled(new TaskBundle())
  })


// ------------------- Reg/Wire declaration ---------------------- //
  val lcrdFreeNumReg  = RegInit(0.U(snTxlcrdBits.W))
  val flitReg         = RegInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitvReg        = RegInit(false.B)
  val flit            = WireInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitv           = WireInit(false.B)
  val flitCanGo       = WireInit(false.B)
  val btWayBufVec     = RegInit(VecInit(Seq.fill(dsuparam.nrDataBufferEntry) { 0.U.asTypeOf(new BTWayWithTxnidBundle()) }))
  val respBufVec      = RegInit(VecInit(Seq.fill(dsuparam.nrDataBufferEntry) { 0.U.asTypeOf(new RxRespBundle()) }))
  val respHitVec      = Wire(Vec(dsuparam.nrDataBufferEntry, Bool()))
  val respSelId       = Wire(UInt(dbIdBits.W))
  val btWayHitVec     = Wire(Vec(dsuparam.nrDataBufferEntry, Bool()))
  val btWaySelId      = Wire(UInt(dbIdBits.W))
  val mpResp          = WireInit(0.U.asTypeOf(new TaskBundle()))

// ------------------------- Logic ------------------------------- //
  /*
   * Receive btWay from mpTask
   */
  val btWayAllocId = PriorityEncoder(btWayBufVec.map(!_.valid))
  btWayBufVec.zipWithIndex.foreach {
    case (buf, i) =>
      when(btWayAllocId === i.U & io.btWay.valid) {
        buf := io.btWay
      }.elsewhen(btWaySelId === i.U & io.mpResp.fire) {
        buf.valid := false.B
      }
  }


  /*
   * Receive resp from rsp
   */
  val respAllocId = PriorityEncoder(respBufVec.map(!_.valid))
  respBufVec.zipWithIndex.foreach {
    case(buf, i) =>
      when(respAllocId === i.U & io.rspResp.valid){
        buf := io.rspResp
      }.elsewhen(respSelId === i.U & io.dataFDB.bits.isLast & flitv) {
        buf.valid := false.B
      }
  }

  /*
   * has one btWay buf hit when data from DataBuffer
   */
  btWayHitVec := btWayBufVec.map { case buf => buf.valid & buf.txnid(dbIdBits - 1, 0) === io.dataFDB.bits.dbid }
  btWaySelId := PriorityEncoder(btWayHitVec)

  /*
   * has one resp buf hit when data from DataBuffer
   */
  respHitVec := respBufVec.map{ case buf => buf.valid & buf.txnid(dbIdBits - 1, 0) === io.dataFDB.bits.dbid }
  respSelId := PriorityEncoder(respHitVec)

  // Receive data from DataBuffer
  io.dataFDB.ready := respHitVec.asUInt.orR & Mux(io.dataFDB.bits.isLast, io.mpResp.fire, btWayHitVec.asUInt.orR) & flitCanGo

  /*
   * Clean block table in ReqArb
   */
  io.mpResp.valid := respHitVec.asUInt.orR & btWayHitVec.asUInt.orR & io.dataFDB.valid & io.dataFDB.bits.isLast & flitCanGo
  io.mpResp.bits  := mpResp
  mpResp.cleanBt  := true.B
  mpResp.writeBt  := false.B
  mpResp.btWay    := btWayBufVec(btWaySelId).btWay
  mpResp.addr     := btWayBufVec(btWaySelId).addr
  io.mpResp.bits.from.idL0  := IdL0.MASTER
  io.mpResp.bits.from.idL1  := DontCare
  io.mpResp.bits.from.idL2  := DontCare
  io.mpResp.bits.to         := DontCare

  /*
   * task to TXREQFLIT
   * Read* txnID:       0XXX_XXXX, X = dbid
   * WriteBack* txnID:  1XXX_XXXX, X = wbid
   */
  flit.qos      := DontCare
  flit.tgtID    := dsuparam.idmap.SNID.U
  flit.srcID    := dsuparam.idmap.HNID.U
  flit.txnID    := respBufVec(respSelId).dbid
  flit.homeNID  := DontCare
  flit.opcode   := CHIOp.DAT.NonCopyBackWrData
  flit.respErr  := DontCare
  flit.resp     := ChiResp.I
  flit.fwdState := DontCare
  flit.dbID     := DontCare
  flit.ccID     := DontCare
  flit.dataID   := io.dataFDB.bits.dataID
  flit.traceTag := DontCare
  flit.be       := Fill(flit.be.getWidth, 1.U(1.W))
  flit.data     := io.dataFDB.bits.data
  flitv         := respHitVec.asUInt.orR & Mux(io.dataFDB.bits.isLast, io.mpResp.fire, btWayHitVec.asUInt.orR) & io.dataFDB.valid & flitCanGo

  /*
   * set reg value
   */
  flitvReg := flitv
  flitReg := Mux(flitv, flit, flitReg)


  /*
   * FSM: count free lcrd and set task ready value
   */
  switch(io.txState) {
    is(LinkStates.STOP) {
      // Nothing to do
    }
    is(LinkStates.ACTIVATE) {
      lcrdFreeNumReg := lcrdFreeNumReg + io.chi.lcrdv.asUInt
    }
    is(LinkStates.RUN) {
      lcrdFreeNumReg := lcrdFreeNumReg + io.chi.lcrdv.asUInt - flitv
      flitCanGo := lcrdFreeNumReg > 0.U
    }
    is(LinkStates.DEACTIVATE) {
      // TODO: return lcrd logic
    }
  }

  /*
   * Output chi flit
   */
  io.chi.flitpend := flitv
  io.chi.flitv := flitvReg
  io.chi.flit := flitReg

// --------------------- Assertion ------------------------------- //
  assert(Mux(io.dataFDB.fire & io.dataFDB.bits.isLast, io.mpResp.fire, !io.mpResp.fire))
  assert(Mux(flitv, flit.opcode === CHIOp.DAT.NonCopyBackWrData, true.B))
  assert(PopCount(respHitVec.asUInt) <= 1.U)
  assert(PopCount(btWayHitVec.asUInt) <= 1.U)
  assert(flit.be.andR, "be[0x%x] should all valid", flit.be)
  assert(Mux(PopCount(btWayBufVec.map(_.valid)) === dsuparam.nrDataBufferEntry.U, !io.btWay.valid, true.B))
  assert(Mux(PopCount(respBufVec.map(_.valid)) === dsuparam.nrDataBufferEntry.U, !io.rspResp.valid, true.B))
  assert(Mux(io.rspResp.valid, io.rspResp.txnid(chiTxnidBits - 1), true.B))

  // TIMEOUT CHECK
  val cntVecReg = RegInit(VecInit(Seq.fill(dsuparam.nrDataBufferEntry) { 0.U(64.W) }))
  cntVecReg.zip(respBufVec.map(_.valid)).foreach { case (cnt, v) => cnt := Mux(!v, 0.U, cnt + 1.U) }
  cntVecReg.zipWithIndex.foreach { case (cnt, i) => assert(cnt < 5000.U, "TXDAT[%d] WAIT DATA TIMEOUT", i.U) }


}