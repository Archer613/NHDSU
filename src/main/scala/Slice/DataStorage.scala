package DONGJIANG.SLICE

import DONGJIANG._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import DONGJIANG.DSState._

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
    val dbSigs2DB     = new DsDBBundle() // TODO: Consider data width: 256 -> 512
    // rcReq to dataBuffer
    val dbRCReq       = Decoupled(new DBRCReq())
  })

  // TODO: Delete the following code when the coding is complete
  io.mpReq <> DontCare
  io.dbSigs2DB <> DontCare
  io.dbRCReq <> DontCare

  val nrEntry = 4
  val entryBits = log2Ceil(nrEntry)

// --------------------- Modules declaration ------------------------//
  /*
   * sram example:
   * nrDSBank = 2, nrBeat = 2:
   * [bank0_beat0][bank0_beat1]
   * [bank1_beat0][bank1_beat1]
   *
   * TODO: multicycle not effect
   */
  val dataArray = Seq.fill(dsuparam.nrDSBank) { Seq.fill(nrBeat) {
                        Module(new SRAMTemplate(UInt(beatBits.W), dsuparam.sets / dsuparam.nrDSBank, dsuparam.ways,
                          singlePort = true, multicycle = dsuparam.dataMulticycle)) } }

  val outIdQ = Module(new Queue(UInt(entryBits.W), entries = nrEntry * nrBeat, flow = false, pipe = true))

// --------------------- Reg/Wire declaration ------------------------//
  val dsReqEntries  = RegInit(VecInit(Seq.fill(nrEntry) { 0.U.asTypeOf(new DSReqEntry()) }))
  val freeVec       = Wire(Vec(nrEntry, Bool()))
  val allocId       = Wire(UInt(entryBits.W))
  val dbRC2DSVec    = Wire(Vec(nrEntry, Bool()))
  val dbRC2OTHVec   = Wire(Vec(nrEntry, Bool()))
  val isRC2DS       = Wire(Bool())
  val dsRCDBId      = Wire(UInt(entryBits.W))
  val dbWReqId      = Wire(UInt(entryBits.W))
  val dsReadId      = Wire(UInt(entryBits.W))
  val dsWDataVec    = Wire(Vec(nrEntry, Bool())) // wait data
  val dsRDataVec    = Wire(Vec(nrEntry, Bool())) // read data
  val wReadyVec     = Wire(Vec(dsuparam.nrDSBank, Vec(nrBeat, Bool())))
  val rReadyVec     = Wire(Vec(dsuparam.nrDSBank, Vec(nrBeat, Bool())))
  val rFireVec      = Wire(Vec(dsuparam.nrDSBank, Vec(nrBeat, Bool())))

  dontTouch(dsReqEntries)
  dontTouch(freeVec)
  dontTouch(allocId)
  dontTouch(rReadyVec)

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
    dsReqEntries(allocId).to    := io.mpReq.bits.to
  }

  /*
   * get wDBID from DataBuffer
   */
  io.dbSigs2DB.wReq.valid := dsReqEntries.map(_.state === GET_ID).reduce(_ | _)
  io.dbSigs2DB.wResp.ready := true.B
  dbWReqId := PriorityEncoder(dsReqEntries.map(_.state === GET_ID))
  when(io.dbSigs2DB.wResp.fire) { dsReqEntries(dbWReqId).wDBID := io.dbSigs2DB.wResp.bits.dbid }



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
  io.dbRCReq.bits.to.idL1 := Mux(isRC2DS, 0.U,        dsRCDBEntry.to.idL1)
  io.dbRCReq.bits.to.idL2 := Mux(isRC2DS, 0.U,        dsRCDBEntry.to.idL2)
  io.dbRCReq.bits.isRead  := true.B
  io.dbRCReq.bits.isClean := true.B


  /*
   * Write SRAM
   */
  dsWDataVec := dsReqEntries.map(_.state === WRITE_DS)
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
  dsRDataVec := dsReqEntries.map(_.state === READ_DS)
  dsReadId := PriorityEncoder(dsRDataVec)
  rFireVec.zipWithIndex.foreach{ case(fire, i) => fire := dataArray(i).map(_.io.r.req.fire) }
  val dsRDEntry = dsReqEntries(dsReadId)
  dataArray.zipWithIndex.foreach {
    case (array, bank) =>
      array.zipWithIndex.foreach {
        case (array, beat) =>
          array.io.r(
            dsRDEntry.bank === bank.U & dsRDEntry.rBeatNum === beat.U & dsRDataVec.asUInt.orR,
            dsRDEntry.set
          )
      }
  }
  outIdQ.io.enq.valid := rFireVec.asUInt.orR
  outIdQ.io.enq.bits := dsReadId


  /*
   * TODO: get data by RegNext(nrMuticycle)(req.fire)
   * Output Data to DataBuffer
   */
  rReadyVec.zipWithIndex.foreach{ case(ready, i) => ready := dataArray(i).map(_.io.r.req.ready) }
  val toDB = io.dbSigs2DB.dataTDB
  val outId = outIdQ.io.deq.bits
  val dsOutEntry = dsReqEntries(outId)
  toDB.valid := outIdQ.io.deq.valid & rReadyVec(dsOutEntry.bank)(dsOutEntry.sBeatNum)
  dataArray.zipWithIndex.foreach {
    case (array, bank) =>
      array.zipWithIndex.foreach {
        case (array, beat) =>
          when(dsOutEntry.bank === bank.U & array.io.r.req.ready) {
            toDB.bits.data := array.io.r.resp.data(OHToUInt(dsOutEntry.wayOH))
            toDB.bits.dataID := toDataID(beat.U)
          }
      }
  }
  toDB.bits.dbid := dsOutEntry.wDBID
  outIdQ.io.deq.ready := toDB.fire



  /*
   * set dbReqEntries state
   */
  dsReqEntries.zipWithIndex.foreach {
    case(fsm, i) =>
      switch(fsm.state) {
        is(FREE) {
          val hit = allocId === i.U & io.mpReq.fire
          when(hit){ fsm.state := Mux(io.mpReq.bits.ren, GET_ID, RC_DB2DS) }
        }
        is(GET_ID) {
          val hit = dbWReqId === i.U & io.dbSigs2DB.wResp.fire
          when(hit){ fsm.state := READ_DS }
        }
        is(READ_DS) {
          val hit = dsReadId === i.U & rFireVec.asUInt.orR
          when(hit){ fsm.rBeatNum := fsm.rBeatNum + 1.U }
          when(io.dbSigs2DB.dataTDB.valid & io.dbSigs2DB.dataTDB.bits.dbid === fsm.wDBID){ fsm.sBeatNum := fsm.sBeatNum + 1.U }
          when(hit & fsm.rBeatNum === (nrBeat - 1).U){ fsm.state := WRITE_DB }
        }
        is(WRITE_DB) {
          val hit = io.dbSigs2DB.dataTDB.fire & io.dbSigs2DB.dataTDB.bits.isLast & io.dbSigs2DB.dataTDB.bits.dbid === fsm.wDBID
          when(io.dbSigs2DB.dataTDB.valid & io.dbSigs2DB.dataTDB.bits.dbid === fsm.wDBID){ fsm.sBeatNum := fsm.sBeatNum + 1.U }
          when(hit){ fsm.state := RC_DB2OTH }
        }
        is(RC_DB2OTH) {
          val hit = dsRCDBId === i.U & io.dbRCReq.fire
          when(hit & fsm.wen) {
            fsm.state := RC_DB2DS
          }.elsewhen(hit) {
            fsm := 0.U.asTypeOf(fsm); fsm.state := FREE
          }
        }
        is(RC_DB2DS) {
          val hit = dsRCDBId === i.U & io.dbRCReq.fire
          when(hit){ fsm.state := WRITE_DS }
        }
        is(WRITE_DS) {
          val hit = io.dbSigs2DB.dataFDB.fire & io.dbSigs2DB.dataFDB.bits.isLast
          when(hit){ fsm := 0.U.asTypeOf(fsm); fsm.state := FREE }
        }
      }
  }


// ----------------------------- Assertion ------------------------------ //
  assert(Mux(io.dbSigs2DB.dataFDB.valid, PopCount(dsWDataVec) === 1.U, true.B), "when has some data from dataBuffer, must has one dsEntry state is WAIT_DATA")
  assert(PopCount(dsWDataVec) <= 1.U)
  assert(PopCount(rFireVec.asUInt) <= 1.U)
  assert(outIdQ.io.enq.ready)
  assert(Mux(io.dbSigs2DB.wReq.fire, io.dbSigs2DB.wResp.fire, true.B), "wReq and wResp should be fire at the same time")
  val sramRValVec = Wire(Vec(dsuparam.nrDSBank, Vec(nrBeat, Bool())))
  dataArray.zipWithIndex.foreach { case(d, i) => d.zipWithIndex.foreach { case(d, j) => sramRValVec(i)(j) := d.io.r.req.valid } }
  assert(PopCount(sramRValVec.asUInt) <= 1.U, "Only one sram can be read per clock cycle")

  val cntVecReg = RegInit(VecInit(Seq.fill(nrEntry) { 0.U(64.W) }))
  cntVecReg.zip(dsReqEntries.map(_.state)).foreach { case (cnt, s) => cnt := Mux(s === FREE, 0.U, cnt + 1.U) }
  cntVecReg.zipWithIndex.foreach { case (cnt, i) => assert(cnt < TIMEOUT_DS.U, "DSREQ_ENTRY[%d] TIMEOUT", i.U) }





}