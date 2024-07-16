package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import NHDSU.DSState._

class DSRequest(implicit p: Parameters) extends DSUBundle with HasToIDBits {
  val addr = UInt(addressBits.W)
  val wayOH = UInt(dsuparam.ways.W)
  val ren = Bool()
  val wen = Bool()
  val dbid = UInt(dbIdBits.W)
}


class DataStorage()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // Req From MainPipe
    val mpReq         = Flipped(Decoupled(new DSRequest()))
    // dataBuffer <-> DataStorage
    val dbSigs2DB     = new DsDBBundle()
    // rcReq to dataBuffer
    val dbRCReq       = Decoupled(new DBRCReq())
  })

  // TODO: Delete the following code when the coding is complete
  io.mpReq <> DontCare
  io.dbSigs2DB <> DontCare
  io.dbRCReq <> DontCare

// --------------------- Modules declaration ------------------------//
  /*
   * sram example:
   * nrDSBank = 2, nrBeat = 2:
   * [bank0_beat0][bank0_beat1]
   * [bank1_beat0][bank1_beat1]
   */
  val dataArray = Seq.fill(dsuparam.nrDSBank) { Seq.fill(nrBeat) {
                        Module(new SRAMTemplate(UInt(beatBits.W), dsuparam.sets / dsuparam.nrDSBank, dsuparam.ways,
                          singlePort = true, multicycle = dsuparam.dataMulticycle)) } }
  dataArray.foreach(_.foreach(_.io <> DontCare))

// --------------------- Reg/Wire declaration ------------------------//
  val nrEntry       = 4
  val entryBits     = log2Ceil(nrEntry)
  val dsReqEntries  = RegInit(VecInit(Seq.fill(nrEntry) { 0.U.asTypeOf(new DSReqEntry()) }))
  val freeVec       = Wire(Vec(nrEntry, Bool()))
  val allocId       = Wire(UInt(entryBits.W))
  val dbRC2DSVec    = Wire(Vec(nrEntry, Bool()))
  val dbRC2OTHVec   = Wire(Vec(nrEntry, Bool()))
  val isRC2DS       = Wire(Bool())
  val dsRCDBId      = Wire(UInt(entryBits.W))
  val dsWDataVec    = Wire(Vec(nrEntry, Bool())) // wait data
  val wReadyVec     = Wire(Vec(dsuparam.nrDSBank, Vec(nrBeat, Bool())))
  val rReadyVec     = Wire(Vec(dsuparam.nrDSBank, Vec(nrBeat, Bool())))

  dontTouch(dsReqEntries)
  dontTouch(freeVec)
  dontTouch(allocId)

// --------------------- Logic ------------------------//
  /*
   * select free entry for alloc
   * receive mpReq when some entry is free
   */
  freeVec := dsReqEntries.map(_.state === FREE)
  allocId := PriorityEncoder(freeVec)
  io.mpReq.ready := freeVec.asUInt.orR
  when(io.mpReq.fire){
    val (tag, set, dsBank, bank, offset) = parseAddress(io.mpReq.bits.addr,dsBankBits, dsSetBits) // DontCare tagBits
    dsReqEntries(allocId).set   := set
    dsReqEntries(allocId).bank  := dsBank
    dsReqEntries(allocId).wayOH := io.mpReq.bits.wayOH
    dsReqEntries(allocId).ren   := io.mpReq.bits.ren
    dsReqEntries(allocId).wen   := io.mpReq.bits.wen
    dsReqEntries(allocId).rDBID := io.mpReq.bits.dbid
  }

  /*
   * get wDBID from DataBuffer
   */
//  io.dbSigs2DB.wReq := dsReqEntries.map(_.state === GET_ID).reduce(_ | _)
//  io.dbSigs2DB.wResp := dsWDataVec



  /*
   * Read Data In DataBuffer
   * Priority: RC_DB2DS > RC_DB2OTH
   */
  dbRC2DSVec  := dsReqEntries.map(_.state === RC_DB2DS)
  dbRC2OTHVec := dsReqEntries.map(_.state === RC_DB2OTH)
  isRC2DS     := dbRC2DSVec.asUInt.orR & !dsWDataVec.asUInt.orR
  dsRCDBId    := Mux(isRC2DS, PriorityEncoder(dbRC2DSVec), PriorityEncoder(dbRC2OTHVec))
  val dsRCDBEntry = dsReqEntries(dsRCDBId)
  io.dbRCReq.valid        := isRC2DS | dbRC2OTHVec.asUInt.orR
  io.dbRCReq.bits.dbid    := Mux(isRC2DS, dsRCDBEntry.rDBID, dsRCDBEntry.wDBID)
  io.dbRCReq.bits.to.idL0 := Mux(isRC2DS, IdL0.SLICE, dsRCDBEntry.to.idL0)
  io.dbRCReq.bits.to.idL1 := Mux(isRC2DS, 0.U,        dsRCDBEntry.to.idL0)
  io.dbRCReq.bits.to.idL2 := Mux(isRC2DS, 0.U,        dsRCDBEntry.to.idL0)
  io.dbRCReq.bits.isClean := true.B


  /*
   * Write SRAM
   */
  dsWDataVec := dsReqEntries.map(_.state === WAIT_DATA)
  wReadyVec.zipWithIndex.foreach{ case(ready, i) => ready := dataArray(i).map(_.io.w.req.ready) }
  val dsWDEntry = dsReqEntries(PriorityEncoder(dsWDataVec))
  val fromDB = io.dbSigs2DB.dataFDB
  dataArray.zipWithIndex.foreach{
    case(array, bank) =>
      array.zipWithIndex.foreach {
        case(array, beat) =>
          array.io.w(
            fromDB.valid & dsWDEntry.bank === bank.U & fromDB.bits.beatNum === beat.U,
            fromDB.bits.data,
            dsWDEntry.set,
            dsWDEntry.wayOH
          )
      }
  }
  fromDB.ready := wReadyVec(dsWDEntry.bank)(fromDB.bits.beatNum)

  /*
   * Read SRAM
   */
  rReadyVec.zipWithIndex.foreach{ case(ready, i) => ready := dataArray(i).map(_.io.r.req.ready) }



  /*
   * set dbReqEntries state
   */
  dsReqEntries.zipWithIndex.foreach {
    case(fsm, i) =>
      switch(fsm.state) {
        is(FREE) {
          val hit = allocId === i.U & io.mpReq.fire
          when(hit) { fsm.state := Mux(io.mpReq.bits.ren, GET_ID, RC_DB2DS) }
        }
        is(RC_DB2DS) {
          val hit = dsRCDBId === i.U & io.dbRCReq.fire
          when(hit){ fsm.state := WAIT_DATA }
        }
        is(WAIT_DATA) {
          val hit = io.dbSigs2DB.dataFDB.fire & io.dbSigs2DB.dataFDB.bits.isLast
          when(hit){ fsm := 0.U.asTypeOf(fsm); fsm.state := FREE }
        }
      }
  }


// ----------------------------- Assertion ------------------------------ //
  assert(Mux(io.dbSigs2DB.dataFDB.valid, PopCount(dsWDataVec) === 1.U, true.B), "when has some data from dataBuffer, must has one dsEntry state is WAIT_DATA")
  assert(PopCount(dsWDataVec) <= 1.U)

  val cntVecReg = RegInit(VecInit(Seq.fill(nrEntry) { 0.U(64.W) }))
  cntVecReg.zip(dsReqEntries.map(_.state)).foreach { case (cnt, s) => cnt := Mux(s === FREE, 0.U, cnt + 1.U) }
  cntVecReg.zipWithIndex.foreach { case (cnt, i) => assert(cnt < 5000.U, "DSREQ_ENTRY[%d] TIMEOUT", i.U) }





}