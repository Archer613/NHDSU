package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, PopCount, PriorityEncoder, Fill, is, switch, Cat}
import org.chipsalliance.cde.config._

class DsuChiTxDat()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi       = CHIChannelIO(new CHIBundleDAT(chiBundleParams))
    val rspResp   = Input(new RxRespBundle())
    val txState   = Input(UInt(LinkStates.width.W))
    val dataFDB   = Flipped(Decoupled(new MsDBOutData()))
    val clTask    = Decoupled(new WCBTBundle())
  })


// ------------------- Reg/Wire declaration ---------------------- //
  val lcrdFreeNumReg  = RegInit(0.U(snTxlcrdBits.W))
  val flitReg         = RegInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitvReg        = RegInit(false.B)
  val flit            = WireInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitv           = WireInit(false.B)
  val flitCanGo       = WireInit(false.B)
  val respBufVec      = RegInit(VecInit(Seq.fill(dsuparam.nrDataBufferEntry) { 0.U.asTypeOf(new RxRespBundle()) }))
  val hitVec          = Wire(Vec(dsuparam.nrDataBufferEntry, Bool()))
  val selId           = Wire(UInt(dbIdBits.W))

// ------------------------- Logic ------------------------------- //
  /*
   * Receive resp from rsp
   */
  val allocId = PriorityEncoder(respBufVec.map(!_.valid))
  respBufVec.zipWithIndex.foreach {
    case(buf, i) =>
      when(allocId === i.U & io.rspResp.valid){
        buf := io.rspResp
      }.elsewhen(selId === i.U & io.dataFDB.bits.isLast & flitv) {
        buf.valid := false.B
      }
  }

  /*
   * has one buf hit data from DataBuffer
   */
  hitVec := respBufVec.map{ case buf => buf.valid & buf.txnid(chiTxnidBits - 2, 0) === io.dataFDB.bits.to.idL2 }
  selId := PriorityEncoder(hitVec)
  io.dataFDB.ready := hitVec.asUInt.orR & Mux(io.dataFDB.bits.isLast, io.clTask.ready, true.B)

  /*
   * Clean block table in ReqArb
   */
  val clTaskWay           = respBufVec(selId).txnid(blockWayBits - 1, 0)
  val clTaskSet           = respBufVec(selId).txnid(blockSetBits + blockWayBits - 1, blockWayBits)
  io.clTask.valid         := io.dataFDB.valid & io.dataFDB.bits.isLast
  io.clTask.bits.isClean  := true.B
  io.clTask.bits.btWay    := clTaskWay
  io.clTask.bits.addr     := Cat(0.U(blockTagBits.W), clTaskSet, 0.U(bankBits.W), 0.U(offsetBits.W))
  io.clTask.bits.to       := DontCare
  /*
   * task to TXREQFLIT
   * Read* txnID:       0XXX_XXXX, X = dbid
   * WriteBack* txnID:  1XXX_XXXX, X = wbid
   */
  flit.qos      := DontCare
  flit.tgtID    := dsuparam.idmap.SNID.U
  flit.srcID    := dsuparam.idmap.HNID.U
  flit.txnID    := respBufVec(selId).dbid
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
  flitv         := hitVec.asUInt.orR & io.dataFDB.valid & Mux(io.dataFDB.bits.isLast, io.clTask.ready, true.B) & flitCanGo

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
  assert(Mux(io.dataFDB.fire & io.dataFDB.bits.isLast, io.clTask.fire, !io.clTask.fire))
  assert(Mux(flitv, flit.opcode === CHIOp.DAT.NonCopyBackWrData, true.B))
  assert(PopCount(hitVec.asUInt) <= 1.U)
  assert(flit.be.andR, "be[0x%x] should all valid", flit.be)
  assert(Mux(PopCount(respBufVec.map(_.valid)) === dsuparam.nrDataBufferEntry.U, !io.rspResp.valid, true.B))
  assert(Mux(io.rspResp.valid, io.rspResp.txnid(chiTxnidBits - 1), true.B))

  // TIMEOUT CHECK
  val cntVecReg = RegInit(VecInit(Seq.fill(dsuparam.nrDataBufferEntry) { 0.U(64.W) }))
  cntVecReg.zip(respBufVec.map(_.valid)).foreach { case (cnt, v) => cnt := Mux(!v, 0.U, cnt + 1.U) }
  cntVecReg.zipWithIndex.foreach { case (cnt, i) => assert(cnt < TIMEOUT_TXD.U, "TXDAT[%d] TIMEOUT", i.U) }


}