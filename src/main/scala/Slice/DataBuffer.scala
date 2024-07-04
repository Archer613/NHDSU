package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class DataBuffer()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // CPUSLAVE <-> dataBuffer
    val cpu2db    = Flipped(new DBBundle())
    // DSUMASTER <-> dataBuffer
    val ms2db     = Flipped(new DBBundle())
    // DataStorage <-> dataBuffer
    val ds2db     = Flipped(new DBBundle())
    // MainPipe <-> dataBuffer
    val mpRCReq   = Flipped(ValidIO(new DBRCReq()))
  })

  // TODO: Delete the following code when the coding is complete
  io.cpu2db <> DontCare
  io.ms2db <> DontCare
  io.ds2db <> DontCare
  io.mpRCReq <> DontCare

// ----------------------- Modules declaration ------------------------ //
  // TODO: Consider remove cpuWRespQ because cpu wResp.ready is false rare occurrence
  val bankOver1 = dsuparam.nrBank > 1
  val cpuWRespQ = if(bankOver1) { Some(Module(new Queue(gen = new DBWResp(), entries = dsuparam.nrBank-1, flow = true, pipe = true))) } else { None }


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

  dontTouch(dataBuffer)

// ----------------------------- Logic ------------------------------ //
  /*
  * TODO: Consider the legitimacy of request priority
  * select free db for alloc, Priority: [DSUMASTER] > [DataStorage] > [CPUSLAVE]
  */
  // get free dbid
  dbFreeVec(0) := dataBuffer.map(_.state === DBState.FREE)
  dbFreeVec(1)(dbAllocId(0)) := false.B
  dbFreeVec(2)(dbAllocId(1)) := false.B
  dbFreeVec(1) := dbFreeVec(0)
  dbFreeVec(2) := dbFreeVec(1)
  dbFreeNum := PopCount(dbFreeVec.asUInt)
  canAllocVec.zipWithIndex.foreach{ case(v, i) => v := dbFreeNum > i.U }
  dbAllocId.zip(dbFreeVec).foreach{ case(id, vec) => id := PriorityEncoder(vec) }
  // set wReq ready
  wReqVec.map(_.ready).zip(canAllocVec).foreach{ case(r, v) => r := v }
  if(bankOver1) io.cpu2db.wReq.ready := cpuWRespQ.get.io.enq.ready

  /*
   * write response
   * dontCare ready, when resp valid ready should be true
   * ms2db.wResp.ready and ds2db.wResp.ready should be true forever
   */
  wRespVec.zipWithIndex.foreach {
    case(resp, i) =>
      resp.valid := wReqVec(i).valid
      resp.bits.to := wReqVec(i).bits.from
      resp.bits.from := wReqVec(i).bits.to
      resp.bits.from.idL2 := dbAllocId(i)
  }
  if(bankOver1) io.cpu2db.wResp <> cpuWRespQ.get.io.deq


  /*
   * receive Data from dataTDB and save data in dataBuffer
   * ready be true forever
   * TODO: consider dataWitdth = 512 bits
   */
  dataTDBVec.foreach {
    case t =>
      t.ready := true.B
      when(t.valid) {
        dataBuffer(t.bits.to.idL2).beatVals(t.bits.beatNum) := true.B
        dataBuffer(t.bits.to.idL2).beats(t.bits.beatNum) := t.bits.data
      }
  }


  /*
  * set dataBuffer state
  */
  dataBuffer.zipWithIndex.foreach {
    case(db, i) =>
      switch(db.state) {
        is(DBState.FREE) {
          val hit = dbAllocId.zip(wReqVec.map(_.fire)).map(a => a._1 === i.U & a._2).reduce(_ | _)
          db.state := Mux(hit, DBState.ALLOC, DBState.FREE)
        }
        is(DBState.ALLOC) {
          val hit = dataTDBVec.map( t => t.valid & t.bits.to.idL2 === i.U).reduce(_ | _)
          db.state := Mux(hit, DBState.WRITTING, DBState.ALLOC)
        }
        is(DBState.WRITTING) {
          val hit = dataTDBVec.map(t => t.valid & t.bits.to.idL2 === i.U).reduce(_ | _)
          val writeDone = PopCount(db.beatVals) + 1.U === nrBeat.U
          db.state := Mux(hit & writeDone, DBState.WRITE_DONE, DBState.WRITTING)
        }
      }
  }


// ----------------------------- Assertion ------------------------------ //
  assert(wReqVec.zip(wRespVec).map{ a => Mux(a._1.fire, a._2.fire, true.B) }.reduce(_ & _), "When wReq fire, wResp must also fire too")
}