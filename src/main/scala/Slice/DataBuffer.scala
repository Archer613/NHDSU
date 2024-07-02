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

// // --------------------- Reg/Wire declaration ------------------------ //
//   val dataBuffer  = RegInit(VecInit(Seq.fill(dsuparam.nrDataBufferEntry) { 0.U.asTypeOf(new DBEntry()) }))
//   val dbFreeVec   = Wire(Vec(3, Vec(dsuparam.nrDataBufferEntry, Bool())))
//   val dbFreeNum   = WireInit(0.U((dbIdBits+1).W))
//   val dbAllocId   = Wire(Vec(3, UInt(dbIdBits.W)))
//   val canAllocVec = Wire(Vec(3, Bool()))

// // ----------------------------- Logic ------------------------------ //
//   /*
//    * TODO: Consider the legitimacy of request priority
//    * select free db for alloc, Priority: [DSUMASTER] > [DataStorage] > [CPUSLAVE]
//    */
//   // get free dbid
//   dbFreeVec(0) := dataBuffer.map(_.state === DBState.FREE)
//   dbFreeVec(1)(dbAllocId(0)) := false.B
//   dbFreeVec(2)(dbAllocId(1)) := false.B
//   dbFreeVec(1) := dbFreeVec(0)
//   dbFreeVec(2) := dbFreeVec(1)
//   dbFreeNum := PopCount(dbFreeVec.asUInt)
//   canAllocVec.zipWithIndex.foreach{ case(v, i) => v := dbFreeNum > i.U }
//   dbAllocId.zip(dbFreeVec).foreach{ case(id, vec) => id := PriorityEncoder(vec) }
//   // when req is write, alloc dbid
//   val wReqVec = Seq(io.dbSigs2Ms.req, io.dbSigs2DS.req, io.dbSigs2Cpu.req)
//   wReqVec..zip(canAllocVec).foreach{ case((w, v), c) => v := w.valid & w.bits.dbOp === DBOp.WRITE & c }


//   /*
//    * set dataBuffer state
//    */







//   /*
//    *
//    */




}