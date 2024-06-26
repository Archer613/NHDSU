package NHDSU.SLICE

import NHDSU._
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
    val sDirWrite    = Decoupled(new SDirWrite)
    val sDirResp     = Flipped(Decoupled(new SDirResp))
    val cDirWrite    = Decoupled(new CDirWrite)
    val cDirResp     = Flipped(Decoupled(new CDirResp))
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
  io.sDirResp <> DontCare
  io.cDirWrite <> DontCare
  io.cDirResp <> DontCare
  io.dsReq <> DontCare
  io.snpTask <> DontCare
  io.msTask <> DontCare
  io.cpuResp <> DontCare
  io.dbReq <> DontCare


//// --------------------- Modules declaration ------------------------//
//
//  val taskQ = Module(new Queue(new TaskBundle(), entries = nrMPQBeat, pipe = true, flow = true))
//  val dirResQ = Module(new Queue(new DirResp(), entries = nrMPQBeat, pipe = true, flow = true))
//
//// --------------------- Reg/Wire declaration ------------------------//
//  // s2 signals
//  val canGo_s2 = WireInit(false.B)
//  val task_s2 = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
//  // s3 basic signals
//  val canGo_s3 = WireInit(false.B)
//  val dirCanGo_s3 = WireInit(false.B)
//  val taskNext_s3 = WireInit(0.U.asTypeOf(new TaskBundle()))
//  val task_s3_g = RegInit(0.U.asTypeOf(Valid(new TaskBundle())))
//  val dirRes_s3 = WireInit(0.U.asTypeOf(Valid(new DirResp())))
//  // s3 can deal tasks
//  val taskRead_s3 = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
//  val taskWrite_s3 = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
//  val taskResp_s3 = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
//  // s3 signals
//  val needSnoop = WireInit(false.B)
//  val needReadDS = WireInit(false.B)
//  val needReadDB = WireInit(false.B)
//  val needResp = WireInit(false.B)
//  val needReq = WireInit(false.B)
//
//
//  dontTouch(task_s3_g)
//  dontTouch(dirRes_s3)
//
//
//// ------------------------ S2: Buffer input task/dirRes --------------------------//
//  // task queue
//  taskQ.io.enq <> io.arbTask
//  task_s2.valid := taskQ.io.deq.valid
//  task_s2.bits := taskQ.io.deq.bits
//  taskQ.io.deq.ready := canGo_s2
//
//  canGo_s2 := canGo_s3 | !task_s3_g.valid
//
//  // dir result queue
//  dirResQ.io.enq <> io.dirResp
//
//// ------------------------ S3: Deal task logic --------------------------//
//  /*
//   * Recieve task_s2
//   */
//  task_s3_g.valid := Mux(task_s2.valid, true.B, task_s3_g.valid & !canGo_s3)
//  taskNext_s3 := Mux(task_s2.valid & canGo_s2, task_s2.bits, task_s3_g.bits)
//  task_s3_g.bits := taskNext_s3
//
//  /*
//   * Recieve dirRes
//   */
//  dirRes_s3.valid := dirResQ.io.deq.valid
//  dirRes_s3.bits := dirResQ.io.deq.bits
//  dirResQ.io.deq.ready := dirCanGo_s3
//
//  dirCanGo_s3 := canGo_s3 & task_s3_g.valid & taskNext_s3.readDir
//
//
//  /*
//   * Determine task_s3 is [ taskRead_s3 / taskWrite_s3 / taskResp_s3 ]
//   */
//  val tasks = Seq(taskRead_s3, taskWrite_s3, taskResp_s3)
//  tasks.foreach(_.bits := task_s3_g.bits)
//  taskRead_s3.valid := task_s3_g.valid & taskRead_s3.bits.isR & dirRes_s3.valid // Default taskRead_s3 needs to read directory
//  taskWrite_s3.valid := false.B // TODO
//  taskResp_s3.valid := false.B // TODO
//
//  /*
//   * Deal taskRead_s3 logic
//   */
//  when(taskRead_s3.valid & dirRes_s3.valid) {
//    // TODO
//  }
//
//
//  /*
//   * Write or Read DS logic
//   */
//
//
//  /*
//   * Alloc SnpCtl logic
//   */
//
//
//  /*
//   * Output to resp logic
//   */
//
//
//  /*
//   * Output to req logic
//   */






}