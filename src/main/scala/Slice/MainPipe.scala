package NHDSU.SLICE

import NHDSU._
import _root_.NHDSU.CHI.CHIOp
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class MainPipe()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // Task From Arb
    val arbTask     = Flipped(Decoupled(new TaskBundle))
    // TODO: Lock signals to Arb
    // Resp/Write Directory
    val dirResp     = Flipped(Decoupled(new DirResp()))
    val sDirWrite   = Decoupled(new SDirWrite(useAddr = true))
    val cDirWrite   = Decoupled(new CDirWrite(useAddr = true))
    // Req to DataStorage
    val dsReq       = Decoupled(new DSRequest())
    // Task to snpCtrl
    val snpTask     = Decoupled(new TaskBundle())
    // Resp to CpuSlave
    val cpuResp     = Decoupled(new TaskRespBundle())
    // Task to Master
    val msTask      = Decoupled(new TaskBundle())
    // Req to dataBuffer
    val dbReq       = ValidIO(new DBReq())
  })

  // TODO: Delete the following code when the coding is complete
  io.arbTask <> DontCare
  io.sDirWrite <> DontCare
  io.dirResp <> DontCare
  io.cDirWrite <> DontCare
  io.dsReq <> DontCare
  io.snpTask <> DontCare
  io.msTask <> DontCare
  io.cpuResp <> DontCare
  io.dbReq <> DontCare


// --------------------- Modules declaration ------------------------//

  val taskQ = Module(new Queue(new TaskBundle(), entries = nrMPQBeat, pipe = true, flow = true))
  val dirResQ = Module(new Queue(new DirResp(), entries = nrMPQBeat, pipe = true, flow = true))

// --------------------- Reg/Wire declaration ------------------------//
  // s2 signals
  val canGo_s2 = WireInit(false.B)
  val task_s2 = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
  // s3 basic signals
  val canGo_s3 = WireInit(false.B)
  val dirCanGo_s3 = WireInit(false.B)
  val taskNext_s3 = WireInit(0.U.asTypeOf(new TaskBundle()))
  val task_s3_g = RegInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val dirRes_s3 = WireInit(0.U.asTypeOf(Valid(new DirResp())))
  // s3 can deal tasks
  val TYPE_READ = "b001".U
  val TYPE_WRITE = "b010".U
  val TYPE_RESP = "b100".U
  val taskTypeVec = Wire(Vec(3, Bool()))
  // s3 need to do signals
  val needSnoop = WireInit(false.B)
  val needReadDS = WireInit(false.B)
  val needReadDB = WireInit(false.B)
  val needResp = WireInit(false.B)
  val needReq = WireInit(false.B)
  // s3 dir signals
  val self_s3 = dirRes_s3.bits.self
  val client_s3 = dirRes_s3.bits.client
  // s3 task signals
  val taskReq_s3 = WireInit(0.U.asTypeOf(new TaskBundle()))


  dontTouch(task_s3_g)
  dontTouch(dirRes_s3)


// ------------------------ S2: Buffer input task/dirRes --------------------------//
  // task queue
  taskQ.io.enq <> io.arbTask
  task_s2.valid := taskQ.io.deq.valid
  task_s2.bits := taskQ.io.deq.bits
  taskQ.io.deq.ready := canGo_s2

  canGo_s2 := canGo_s3 | !task_s3_g.valid

  // dir result queue
  dirResQ.io.enq <> io.dirResp

// -------------------------- S3: Deal task logic -------------------------------//
  /*
   * Recieve task_s2
   */
  task_s3_g.valid := Mux(task_s2.valid, true.B, task_s3_g.valid & !canGo_s3)
  taskNext_s3 := Mux(task_s2.valid & canGo_s2, task_s2.bits, task_s3_g.bits)
  task_s3_g.bits := taskNext_s3

  /*
   * Recieve dirRes
   */
  dirRes_s3.valid := dirResQ.io.deq.valid
  dirRes_s3.bits := dirResQ.io.deq.bits
  dirResQ.io.deq.ready := dirCanGo_s3

  dirCanGo_s3 := canGo_s3 & task_s3_g.valid & taskNext_s3.readDir


  /*
   * Determine task_s3 is [ taskRead_s3 / taskWrite_s3 / taskResp_s3 ]
   */
  taskTypeVec(0) := task_s3_g.valid & task_s3_g.bits.isR & dirRes_s3.valid // Default taskRead_s3 needs to read directory
  taskTypeVec(1) := false.B // TODO
  taskTypeVec(2) := false.B // TODO


  /*
   * Write or Read DS logic
   */


  /*
   * Alloc SnpCtl logic
   */


  /*
   * Output to resp logic
   */


  /*
   * Output to req logic
   */
  switch(taskTypeVec.asUInt) {
    is(TYPE_READ) { needReq := !self_s3.hit & !client_s3.hitVec.asUInt.orR } // Not hit in all cache
  }
  // bits
  taskReq_s3.opcode   := CHIOp.REQ.ReadNoSnp
  taskReq_s3.addr     := task_s3_g.bits.addr
  taskReq_s3.isR      := true.B
  taskReq_s3.isWB     := false.B
  taskReq_s3.from     := task_s3_g.bits.from
  taskReq_s3.to.idL0  := IdL0.MASTER
  taskReq_s3.to.idL1  := DontCare
  taskReq_s3.to.idL2  := DontCare
  // io
  io.msTask.valid     := needReq
  io.msTask.bits      := taskReq_s3


  /*
   * Update can go s3 logic
   */
  val needToDo_s3 = Seq(needSnoop, needReadDS, needReadDB, needResp, needReq)
  val done_s3 = Seq(io.snpTask.fire, io.dsReq.fire, io.dbReq.fire, io.cpuResp.fire, io.msTask.fire)
  canGo_s3 := needToDo_s3.zip(done_s3).map(a => !a._1 | a._2).reduce(_ & _) & taskTypeVec.asUInt.orR



// -------------------------- Assertion ------------------------------- //
  assert(PopCount(taskTypeVec.asUInt) <= 1.U, "State 3: Task can only be one type")
  assert(Mux(task_s3_g.valid & canGo_s3, PopCount(done_s3).orR, true.B), "State 3: when task_s3 go, must has some task done")



}