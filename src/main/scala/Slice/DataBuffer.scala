package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import Utils.Encoder.RREncoder

class DataBuffer()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // CPUSLAVE <-> dataBuffer
    val cpu2db    = Flipped(new CpuDBBundle())
    // DSUMASTER <-> dataBuffer
    val ms2db     = Flipped(new MsDBBundle())
    // DataStorage <-> dataBuffer
    val ds2db     = Flipped(new DsDBBundle())
    // MainPipe <-> dataBuffer
    val mpRCReq   = Flipped(Decoupled(new DBRCReq()))
    val dsRCReq   = Flipped(Decoupled(new DBRCReq()))
  })

  // TODO: Delete the following code when the coding is complete
  io.cpu2db <> DontCare
  io.ms2db <> DontCare
  io.ds2db <> DontCare
  io.mpRCReq <> DontCare
  io.dsRCReq <> DontCare

// ----------------------- Modules declaration ------------------------ //
  // TODO: Consider remove cpuWRespQ because cpu wResp.ready is false rare occurrence
  val bankOver1 = dsuparam.nrBank > 1
  val cpuWRespQ = if(bankOver1) { Some(Module(new Queue(gen = new CpuDBWResp(), entries = dsuparam.nrBank-1, flow = true, pipe = true))) } else { None }

// --------------------- Reg/Wire declaration ------------------------ //
  // base
  val dataBuffer  = RegInit(VecInit(Seq.fill(dsuparam.nrDataBufferEntry) { 0.U.asTypeOf(new DBEntry()) }))
  val dbFreeVec   = Wire(Vec(3, Vec(dsuparam.nrDataBufferEntry, Bool())))
  val dbFreeNum   = WireInit(0.U((dbIdBits+1).W))
  val dbAllocId   = Wire(Vec(3, UInt(dbIdBits.W)))
  val canAllocVec = Wire(Vec(3, Bool()))
  // wReq
  val wReqVec     = Seq(io.ms2db.wReq, io.ds2db.wReq, io.cpu2db.wReq)
  val wRespVec    = Seq(io.ms2db.wResp, io.ds2db.wResp, if(bankOver1) cpuWRespQ.get.io.enq else io.cpu2db.wResp)
  // dataTDB
  val dataTDBVec  = Seq(io.ms2db.dataTDB, io.ds2db.dataTDB, io.cpu2db.dataTDB)
  // dataFDB
  val outDsID       = Wire(UInt(dbIdBits.W))
  val outMsID       = Wire(UInt(dbIdBits.W))
  val outCpuID      = Wire(UInt(dbIdBits.W))

  dontTouch(dataBuffer)
  dontTouch(dbFreeVec)
  dontTouch(dbFreeNum)

// ----------------------------- Logic ------------------------------ //
  /*
   * TODO: Consider the legitimacy of request priority
   * select free db for alloc, Priority: [DSUMASTER] > [DS] > [CPUSLAVE]
   */
  // get free dbid
  dbFreeNum := PopCount(dbFreeVec(0).asUInt)
  canAllocVec.zipWithIndex.foreach { case (v, i) => v := dbFreeNum > i.U }
  dbFreeVec(0) := dataBuffer.map(_.state === DBState.FREE)
  dbAllocId.zipWithIndex.foreach{ case(id, i) =>
    if(i > 0) {
      dbFreeVec(i) := dbFreeVec(i-1)
//      dbFreeVec(i)(dbAllocId(i - 1)) := false.B
      dbFreeVec(i)(dbAllocId(i-1)) := !wReqVec(i-1).valid
    }
    id := PriorityEncoder(dbFreeVec(i))
  }
  // set wReq ready
  wReqVec.map(_.ready).zip(canAllocVec).foreach{ case(r, v) => r := v }
  if(bankOver1) io.cpu2db.wReq.ready := cpuWRespQ.get.io.enq.ready

  /*
   * write response
   * dontCare ready, when resp valid ready should be true
   * ms2db.wResp.ready and ds2db.wResp.ready should be true forever
   */
  wRespVec.zip(wReqVec).foreach { case(resp, req) => resp.valid := req.valid }
  wRespVec.zip(dbAllocId).foreach { case(resp, id) => resp.bits.dbid := id}
  if(bankOver1) io.cpu2db.wResp <> cpuWRespQ.get.io.deq
  cpuWRespQ.get.io.enq.bits.to   := io.cpu2db.wReq.bits.from
  cpuWRespQ.get.io.enq.bits.from := io.cpu2db.wReq.bits.to


  /*
   * receive Data from dataTDB and save data in dataBuffer
   * ready be true forever
   * TODO: consider dataWitdth = 512 bits
   */
  dataTDBVec.foreach {
    case t =>
      t.ready := true.B
      when(t.valid) {
        dataBuffer(t.bits.dbid).beatVals(t.bits.beatNum) := true.B
        dataBuffer(t.bits.dbid).beats(t.bits.beatNum) := t.bits.data
      }
  }

  /*
   * receive MainPipe/DataStorage Read/Clean Req
   */
  io.mpRCReq.ready := dataBuffer(io.mpRCReq.bits.dbid).state === DBState.ALLOC      |
                      dataBuffer(io.mpRCReq.bits.dbid).state === DBState.WRITE_DONE |
                      dataBuffer(io.mpRCReq.bits.dbid).state === DBState.READ_DONE
  io.dsRCReq.ready := dataBuffer(io.dsRCReq.bits.dbid).state === DBState.WRITE_DONE |
                      dataBuffer(io.dsRCReq.bits.dbid).state === DBState.READ_DONE

  /*
   * send data to DS / MS / CPU
   * send Data to Ms must be RR
   */
  val dsReadValVec  = dataBuffer.map( d => d.state === DBState.READ & d.to.idL0 === IdL0.SLICE )
  val msReadValVec  = dataBuffer.map( d => d.state === DBState.READ & d.to.idL0 === IdL0.MASTER )
  val cpuReadValVec = dataBuffer.map( d => d.state === DBState.READ & d.to.idL0 === IdL0.CPU )

  val dsReadingValVec   = dataBuffer.map(d => d.state === DBState.READING & d.to.idL0 === IdL0.SLICE)
  val msReadingValVec   = dataBuffer.map(d => d.state === DBState.READING & d.to.idL0 === IdL0.MASTER)
  val cpuReadingValVec  = dataBuffer.map(d => d.state === DBState.READING & d.to.idL0 === IdL0.CPU)

  outDsID   := Mux(dsReadingValVec.reduce(_ | _),   PriorityEncoder(dsReadingValVec),   PriorityEncoder(dsReadValVec))
  outMsID   := Mux(msReadingValVec.reduce(_ | _),   PriorityEncoder(msReadingValVec),   RREncoder(msReadValVec))
  outCpuID  := Mux(cpuReadingValVec.reduce(_ | _),  PriorityEncoder(cpuReadingValVec),  PriorityEncoder(cpuReadValVec))

  io.ds2db.dataFDB.valid  := dsReadValVec.reduce(_ | _) | dsReadingValVec.reduce(_ | _)
  io.ms2db.dataFDB.valid  := msReadValVec.reduce(_ | _) | msReadingValVec.reduce(_ | _)
  io.cpu2db.dataFDB.valid := cpuReadValVec.reduce(_ | _) | cpuReadingValVec.reduce(_ | _)

  io.ds2db.dataFDB.bits.data  := dataBuffer(outDsID).getBeat
  io.ms2db.dataFDB.bits.data  := dataBuffer(outMsID).getBeat
  io.cpu2db.dataFDB.bits.data := dataBuffer(outCpuID).getBeat

  io.ds2db.dataFDB.bits.dataID  := dataBuffer(outDsID).toDataID
  io.ms2db.dataFDB.bits.dataID  := dataBuffer(outMsID).toDataID
  io.cpu2db.dataFDB.bits.dataID := dataBuffer(outCpuID).toDataID

  io.ds2db.dataFDB.bits.dbid  := outDsID
  io.ms2db.dataFDB.bits.dbid  := dataBuffer(outMsID).to.idL2 // more info can see DSUMAster
  io.cpu2db.dataFDB.bits.to   := dataBuffer(outCpuID).to


  /*
   * set dataBuffer state
   */
  dataBuffer.zipWithIndex.foreach {
    case(db, i) =>
      switch(db.state) {
        is(DBState.FREE) {
          val hit       = dbAllocId.zip(wReqVec.map(_.fire)).map(a => a._1 === i.U & a._2).reduce(_ | _)
          db            := 0.U.asTypeOf(db)
          db.state      := Mux(hit, DBState.ALLOC, DBState.FREE)
        }
        is(DBState.ALLOC) {
          val hit         = dataTDBVec.map( t => t.valid & t.bits.dbid === i.U).reduce(_ | _)
          val mpCleanHit  = io.mpRCReq.valid & io.mpRCReq.bits.dbid === i.U & io.mpRCReq.bits.isClean
          when(mpCleanHit) {
            db.state := DBState.FREE
          }.otherwise {
            if(nrBeat > 1) { db.state := Mux(hit, DBState.WRITTING, DBState.ALLOC) }
            else           { db.state := Mux(hit, DBState.WRITE_DONE, DBState.ALLOC) }
          }
        }
        is(DBState.WRITTING) {
          val hit       = dataTDBVec.map(t => t.valid & t.bits.dbid === i.U).reduce(_ | _)
          val writeDone = PopCount(db.beatVals) + 1.U === nrBeat.U
          db.state      := Mux(hit & writeDone, DBState.WRITE_DONE, DBState.WRITTING)
        }
        is(DBState.WRITE_DONE) {
          val mpHit     = io.mpRCReq.valid & io.mpRCReq.bits.dbid === i.U
          val dsHit     = io.dsRCReq.valid & io.dsRCReq.bits.dbid === i.U
          val to        = Mux(mpHit, io.mpRCReq.bits.to,      io.dsRCReq.bits.to)
          val needClean = Mux(mpHit, io.mpRCReq.bits.isClean, io.dsRCReq.bits.isClean)
          val needRead  = Mux(mpHit, io.mpRCReq.bits.isRead,  io.dsRCReq.bits.isRead)
          db.state      := Mux(mpHit | dsHit, Mux(needRead, DBState.READ, DBState.FREE), DBState.WRITE_DONE)
          db.to         := Mux(mpHit | dsHit, to, db.to)
          db.beatRNum   := 0.U
          db.needClean  := Mux(mpHit | dsHit, needClean, db.needClean)
        }
        is(DBState.READ) {
          val dsHit     = io.ds2db.dataFDB.fire & outDsID === i.U
          val msHit     = io.ms2db.dataFDB.fire & outMsID === i.U
          val cpuHit    = io.cpu2db.dataFDB.fire & outCpuID === i.U
          val hit       = dsHit | msHit | cpuHit
          val readDone  = db.beatRNum === (nrBeat - 1).U
          db.state      := Mux(hit, Mux(readDone, Mux(db.needClean, DBState.FREE, DBState.READ_DONE), DBState.READING), DBState.READ)
          db.beatRNum   := db.beatRNum + hit.asUInt
        }
        is(DBState.READING) {
          val dsHit     = io.ds2db.dataFDB.fire & outDsID === i.U
          val msHit     = io.ms2db.dataFDB.fire & outMsID === i.U
          val cpuHit    = io.cpu2db.dataFDB.fire & outCpuID === i.U
          val hit       = dsHit | msHit | cpuHit
          val readDone  = db.beatRNum === (nrBeat - 1).U
          db.state      := Mux(hit & readDone, Mux(db.needClean, DBState.FREE, DBState.READ_DONE), DBState.READING)
          db.beatRNum   := db.beatRNum + hit.asUInt
        }
      }
  }


// ----------------------------- Assertion ------------------------------ //
  assert(wReqVec.zip(wRespVec).map{ a => Mux(a._1.fire, a._2.fire, true.B) }.reduce(_ & _), "When wReq fire, wResp must also fire too")
  assert(!(io.mpRCReq.valid & io.dsRCReq.valid & io.mpRCReq.bits.dbid === io.dsRCReq.bits.dbid), "DS and MP cant read or clean at the same time")

  assert(Mux(io.mpRCReq.fire, io.mpRCReq.bits.isRead | io.mpRCReq.bits.isClean, true.B))
  assert(Mux(io.dsRCReq.fire, io.dsRCReq.bits.isRead | io.dsRCReq.bits.isClean, true.B))

  assert(PopCount(dsReadingValVec) <= 1.U)
  assert(PopCount(msReadingValVec) <= 1.U)
  assert(PopCount(cpuReadingValVec) <= 1.U)

  val cntVecReg  = RegInit(VecInit(Seq.fill(dsuparam.nrDataBufferEntry) { 0.U(64.W) }))
  cntVecReg.zip(dataBuffer.map(_.state)).foreach{ case(cnt, s) => cnt := Mux(s === DBState.FREE, 0.U, cnt + 1.U) }
  cntVecReg.zipWithIndex.foreach{ case(cnt, i) => assert(cnt < TIMEOUT_DB.U, "DATABUF[%d] TIMEOUT", i.U) }
}