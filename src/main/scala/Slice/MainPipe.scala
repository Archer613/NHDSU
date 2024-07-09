package NHDSU.SLICE

import NHDSU._
import NHDSU.CHI._
import NHDSU.SLICE.Coherence._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class MainPipe()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val sliceId     = Input(UInt(bankBits.W))
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
    val cpuResp     = Decoupled(new RespBundle())
    // Task to Master
    val msTask      = Decoupled(new TaskBundle())
    // Req to dataBuffer
    val dbRCReq     = ValidIO(new DBRCReq())
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
  io.dbRCReq <> DontCare


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
  val TYPE_READ   = "b0001".U
  val TYPE_WRITE  = "b0010".U
  val TYPE_RRESP  = "b0100".U // Snp Resp
  val TYPE_SRESP  = "b1000".U // Read Resp
  val taskTypeVec = Wire(Vec(4, Bool()))
  // s3 need to do signals
  val needSnoop   = WireInit(false.B)
  val needReadDS  = WireInit(false.B)
  val needReadDB  = WireInit(false.B)
  val needResp    = WireInit(false.B)
  val needReq     = WireInit(false.B)
  // s3 dir signals
  val self_s3 = dirRes_s3.bits.self
  val client_s3 = dirRes_s3.bits.client
  val rnNS = WireInit(ChiState.ERROR) // RN Next State
  val hnNS = WireInit(ChiState.ERROR) // HN Next State
  // s3 task signals
  val taskReq_s3 = WireInit(0.U.asTypeOf(new TaskBundle()))
  val taskResp_s3 = WireInit(0.U.asTypeOf(new RespBundle()))
  val respChnl = WireInit(0.U(CHIChannel.width.W))
  val respOp = WireInit(0.U(4.W)) // DAT.op.width = 3; RSP.op.width = 4
  val respResp = WireInit(0.U(3.W)) // resp.width = 3
  // s3 data signals
  val wirteDS_s3 = WireInit(false.B)



  dontTouch(task_s3_g)
  dontTouch(dirRes_s3)
  dontTouch(needSnoop)
  dontTouch(needReadDS)
  dontTouch(needReadDB)
  dontTouch(needResp)
  dontTouch(needReq)
  dontTouch(taskTypeVec)



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
   * Determine task_s3 is [ taskRead_s3 / taskWrite_s3 / taskRespMs_s3 / taskRespSnp_s3 ]
   */
  taskTypeVec(0) := task_s3_g.valid & task_s3_g.bits.isR & dirRes_s3.valid & task_s3_g.bits.isTxReq // Default taskRead_s3 needs to read directory
  taskTypeVec(1) := false.B // TODO
  taskTypeVec(2) := task_s3_g.valid & task_s3_g.bits.from.idL0 === IdL0.MASTER & Mux(task_s3_g.bits.readDir, dirRes_s3.valid, true.B)
  taskTypeVec(3) := false.B // TODO

  /*
   * generate (rnNS, hnNS) cpuResp:(channel, op, resp)
   */
  val selfState = Mux(dirRes_s3.bits.self.hit, dirRes_s3.bits.self.state, ChiState.I)
  val (rnRNS, hnRNS) = genNewCohWithoutSnp(task_s3_g.bits.opcode, selfState)
  val (respRChnl, respROp, respRResp) = genRnRespBundle(task_s3_g.bits.opcode, rnNS)
  rnNS      := Mux(taskTypeVec(OHToUInt(TYPE_SRESP)), 0.U, rnRNS)
  hnNS      := Mux(taskTypeVec(OHToUInt(TYPE_SRESP)), 0.U, hnRNS)
  respChnl  := Mux(taskTypeVec(OHToUInt(TYPE_SRESP)), 0.U, respRChnl)
  respOp    := Mux(taskTypeVec(OHToUInt(TYPE_SRESP)), 0.U, respROp)
  respResp  := Mux(taskTypeVec(OHToUInt(TYPE_SRESP)), 0.U, respRResp)


  /*
   * Write or Read DS logic
   */

  /*
   * Read DB logic
   */
  io.dbRCReq.valid := io.cpuResp.fire & io.cpuResp.bits.isRxDat
  io.dbRCReq.bits.to := io.cpuResp.bits.to
  io.dbRCReq.bits.dbid := task_s3_g.bits.dbid
  io.dbRCReq.bits.isRead := true.B
  io.dbRCReq.bits.isClean := !wirteDS_s3



  /*
   * Write Directory
   */



  /*
   * Alloc SnpCtl logic
   */


  /*
   * Output to resp logic
   */
  switch(taskTypeVec.asUInt) {
    // TODO: TYPE_READ
    // TODO: TYPE_WRITE
    is(TYPE_RRESP) { needResp := true.B }
    // TODO: TYPE_SRESP
  }
  // bits
  taskResp_s3.channel   := respChnl
  taskResp_s3.addr      := task_s3_g.bits.addr
  taskResp_s3.opcode    := respOp
  taskResp_s3.resp      := respResp
  taskResp_s3.btWay     := task_s3_g.bits.btWay
  taskResp_s3.from.idL0 := IdL0.SLICE
  taskResp_s3.from.idL1 := io.sliceId
  taskResp_s3.from.idL2 := DontCare
  taskResp_s3.to        := task_s3_g.bits.from
  // io
  io.cpuResp.valid      := needResp
  io.cpuResp.bits       := taskResp_s3


  /*
   * Output to req logic
   */
  switch(taskTypeVec.asUInt) {
    is(TYPE_READ) { needReq := !self_s3.hit & !client_s3.hitVec.asUInt.orR } // Not hit in all cache
    // TODO: TYPE_WRITE
    is(TYPE_RRESP) { needReq := false.B } // TODO: consider need to replace
    // TODO: TYPE_SRESP
  }
  // bits
  taskReq_s3.opcode   := task_s3_g.bits.opcode
  taskReq_s3.addr     := task_s3_g.bits.addr
  taskReq_s3.isR      := true.B
  taskReq_s3.isWB     := false.B
  taskReq_s3.from     := task_s3_g.bits.from
  taskReq_s3.to       := DontCare
  // io
  io.msTask.valid     := needReq
  io.msTask.bits      := taskReq_s3


  /*
   * Update can go s3 logic
   */
  val needToDo_s3 = Seq(needSnoop, needReadDS, needReadDB, needResp, needReq)
  val done_s3 = Seq(io.snpTask.fire, io.dsReq.fire, io.dbRCReq.fire, io.cpuResp.fire, io.msTask.fire)
  canGo_s3 := needToDo_s3.zip(done_s3).map(a => !a._1 | a._2).reduce(_ & _) & taskTypeVec.asUInt.orR



// -------------------------- Assertion ------------------------------- //
  assert(PopCount(taskTypeVec.asUInt) <= 1.U, "State 3: Task can only be one type")
  assert(Mux(task_s3_g.valid & canGo_s3, PopCount(done_s3).orR, true.B), "State 3: when task_s3 go, must has some task done")
  assert(Mux(taskTypeVec.asUInt.orR, rnNS =/= ChiState.ERROR, true.B), "When task s3 valid, RN Next State cant be error")
  assert(Mux(taskTypeVec.asUInt.orR, hnNS =/= ChiState.ERROR, true.B), "When task s3 valid, HN Next State cant be error")
  assert(Mux(io.cpuResp.fire, io.cpuResp.bits.resp =/= ChiResp.ERROR, true.B), "CpuResp.resp cant be error")
}