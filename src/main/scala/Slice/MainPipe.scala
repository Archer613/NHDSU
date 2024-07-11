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
    val dbRCReq     = Decoupled(new DBRCReq())
  })

  // TODO: Delete the following code when the coding is complete
  io.arbTask <> DontCare
  io.dirResp <> DontCare
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
  val canGo_s2    = WireInit(false.B)
  val task_s2     = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
  // s3 basic signals
  val canGo_s3    = WireInit(false.B)
  val dirCanGo_s3 = WireInit(false.B)
  val taskNext_s3 = WireInit(0.U.asTypeOf(new TaskBundle()))
  val task_s3_g   = RegInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val dirRes_s3   = WireInit(0.U.asTypeOf(Valid(new DirResp())))
  // s3 can deal task types
  val CPU_REQ_OH    = "b0001".U
  val CPU_WRITE_OH  = "b0010".U
  val MS_RESP_OH    = "b0100".U
  val SNP_RESP_OH   = "b1000".U
  val CPU_REQ       = 0
  val CPU_WRITE     = 1
  val MS_RESP       = 2
  val SNP_RESP      = 3
  val taskTypeVec   = Wire(Vec(4, Bool()))
  // s3 need to do signals
  val needSnoop   = WireInit(false.B)
  val needWSDir   = WireInit(false.B)
  val needWCDir   = WireInit(false.B)
  val needDSReq   = WireInit(false.B)
  val needReadDB  = WireInit(false.B)
  val needResp    = WireInit(false.B)
  val needReq     = WireInit(false.B)
  // s3 done signals
  val doneSnoop   = RegInit(false.B)
  val doneWSDir   = RegInit(false.B)
  val doneWCDir   = RegInit(false.B)
  val doneDSReq   = RegInit(false.B)
  val doneReadDB  = RegInit(false.B)
  val doneResp    = RegInit(false.B)
  val doneReq     = RegInit(false.B)
  // s3 dir signals
  val self_s3     = dirRes_s3.bits.self
  val client_s3   = dirRes_s3.bits.client
  val srcRnNS     = WireInit(ChiState.I) // source RN Next State
  val othRnNS     = WireInit(ChiState.I) // other RN Next State
  val hnNS        = WireInit(ChiState.I) // HN Next State
  val sourceID    = WireInit(0.U(coreIdBits.W))
  val sourceHit_s3 = WireInit(false.B) // Req source hit
  val otherHit_s3 = WireInit(false.B) // Client expect req source hit
  val hnState     = WireInit(ChiState.I)
  val otherState  = WireInit(ChiState.I)
  val srcState    = WireInit(ChiState.I)
  // s3 task signals
  val taskReq_s3  = WireInit(0.U.asTypeOf(new TaskBundle()))
  val taskResp_s3 = WireInit(0.U.asTypeOf(new RespBundle()))
  // s3 data signals
  val wirteDS_s3  = WireInit(false.B)



  dontTouch(task_s3_g)
  dontTouch(dirRes_s3)
  dontTouch(needSnoop)
  dontTouch(needWSDir)
  dontTouch(needWCDir)
  dontTouch(needDSReq)
  dontTouch(needReadDB)
  dontTouch(needResp)
  dontTouch(needReq)
  dontTouch(doneSnoop)
  dontTouch(doneDSReq)
  dontTouch(doneReadDB)
  dontTouch(doneResp)
  dontTouch(doneReq)
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
   * Determine task_s3 is [ CPU_REQ / CPU_WRITE / MS_RESP / SNP_RESP ]
   * io.arbTask:
   * [CPU_REQ]    |  from: [CPU]    [coreId]    [reqBufId]   | to: [SLICE]  [sliceId] [DontCare]
   * [CPU_WRITE]  |  from: [CPU]    [coreId]    [reqBufId]   | to: [SLICE]  [sliceId] [DontCare]
   * [MS_RESP]    |  from: [MASTER] [DontCare]  [DontCare]   | to: [CPU]    [coreId]  [reqBufId]
   * [SNP_RESP]   |  from: [SLICE]  [DontCare]  [DontCare]   | to: [CPU]    [coreId]  [reqBufId]
   * other:
   * [io.dsReq]   |  from: None                              | to: [CPU]    [coreId]  [reqBufId] // TODO
   * [io.snpTask] |  from: [CPU]    [coreId]  [reqBufId]     | to: None                          // TODO
   * [io.cpuResp] |  from: None                              | to: [CPU]    [coreId]  [reqBufId]
   * [io.msTask]  |  from: [CPU]    [coreId]  [reqBufId]     | to: None                          // TODO
   * [io.dbRCReq] |  from: None                              | to: [CPU]    [coreId]  [reqBufId]
   */
  taskTypeVec(CPU_REQ)    := task_s3_g.valid & dirRes_s3.valid & !task_s3_g.bits.isWB & task_s3_g.bits.from.idL0 === IdL0.CPU
  taskTypeVec(CPU_WRITE)  := task_s3_g.valid & dirRes_s3.valid & task_s3_g.bits.isWB & task_s3_g.bits.from.idL0 === IdL0.CPU
  taskTypeVec(MS_RESP)    := task_s3_g.valid & dirRes_s3.valid & task_s3_g.bits.from.idL0 === IdL0.MASTER
  taskTypeVec(SNP_RESP)   := task_s3_g.valid & dirRes_s3.valid & task_s3_g.bits.from.idL0 === IdL0.SLICE

  /*
   * generate (rnNS, hnNS) cpuResp:(channel, op, resp)
   */
  // Base signals
  sourceID      := Mux(task_s3_g.bits.from.idL0 === IdL0.CPU, task_s3_g.bits.from.idL2, task_s3_g.bits.to.idL2)
  sourceHit_s3  := client_s3.hitVec(sourceID)
  if(dsuparam.nrCore > 1) otherHit_s3 := PopCount(client_s3.hitVec) > 1.U | (client_s3.hitVec.asUInt.orR & !sourceHit_s3)
  hnState       := Mux(self_s3.hit, self_s3.state, ChiState.I)
  if(dsuparam.nrCore > 1) otherState := Mux(otherHit_s3, client_s3.metas(PriorityEncoder(client_s3.hitVec)).state, ChiState.I)
  srcState      := client_s3.metas(sourceID).state
  // Gen new state and resp
  val (snpOp, doNotGoToSD, retToSrc, needSnp, genSnpReqError)               = genSnpReq(task_s3_g.bits.opcode, hnState, otherState)
  val (srcRnNSWithSnp, othRnNSWithSnp, hSNSWithSnp, genNewCohWithSnpError)  = genNewCohWithSnp(task_s3_g.bits.opcode, task_s3_g.bits.snpResp)
  val (rnNSWithoutSnp, hnNSWithoutSnp, readDown, genNewCohWithoutSnpError)  = genNewCohWithoutSnp(task_s3_g.bits.opcode, hnState, otherHit_s3)
  val (respChnl, respOp, respResp, genRnRespError)                          = genRnResp(task_s3_g.bits.opcode, srcRnNS)
  // Mux
  srcRnNS   := Mux(taskTypeVec(SNP_RESP), srcRnNSWithSnp, rnNSWithoutSnp)
  hnNS      := Mux(taskTypeVec(SNP_RESP), hSNSWithSnp, hnNSWithoutSnp)
  othRnNS   := othRnNSWithSnp


  /*
   * Write or Read DS logic
   */
  // TODO


  /*
   * Read/Clean DB logic
   */
  needReadDB := needResp & io.cpuResp.bits.isRxDat
  io.dbRCReq.valid := needReadDB & !doneReadDB
  io.dbRCReq.bits.to := task_s3_g.bits.to
  io.dbRCReq.bits.dbid := task_s3_g.bits.dbid
  io.dbRCReq.bits.isClean := !wirteDS_s3


  /*
   * Write Self Directory
   */
  needWSDir := hnNS =/= hnState & needResp
  io.sDirWrite.valid := needWSDir & !doneWSDir
  io.sDirWrite.bits.state := hnNS
  io.sDirWrite.bits.addr := task_s3_g.bits.addr
  io.sDirWrite.bits.wayOH := self_s3.wayOH


  /*
   * Write Client Directory
   */
  needWCDir := (srcRnNS =/= srcState | othRnNS =/= otherState) & needResp
  io.cDirWrite.valid := needWCDir & !doneWCDir
  io.cDirWrite.bits.addr := task_s3_g.bits.addr
  io.cDirWrite.bits.metas.map(_.state).zipWithIndex.foreach {
    case(state, i) =>
      if(dsuparam.nrCore > 1) state := Mux(task_s3_g.bits.to.idL1 === i.U, srcRnNS, Mux(client_s3.hitVec(i), othRnNS, ChiState.I))
      else                    state := srcRnNS
  }
  io.cDirWrite.bits.wayOH := client_s3.wayOH


  /*
   * Alloc SnpCtl logic
   */


  /*
   * Output to resp logic
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needResp := !readDown & !needSnp }
    is(CPU_WRITE_OH)  { needResp := true.B }
    is(MS_RESP_OH)    { needResp := true.B }
    is(SNP_RESP_OH)   { needResp := true.B }
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
  taskResp_s3.to        := task_s3_g.bits.to
  // io
  io.cpuResp.valid      := needResp & !doneResp
  io.cpuResp.bits       := taskResp_s3


  /*
   * Output to req logic
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needReq := readDown }
    is(CPU_WRITE_OH)  { needReq := false.B } // TODO: consider need to replace
    is(MS_RESP_OH)    { needReq := false.B } // TODO: consider need to replace
    is(SNP_RESP_OH)   { needReq := false.B } // TODO: consider need to replace
  }
  // bits
  taskReq_s3.opcode     := task_s3_g.bits.opcode
  taskReq_s3.addr       := task_s3_g.bits.addr
  taskReq_s3.isWB       := false.B // TODO
  taskReq_s3.from       := task_s3_g.bits.from
  taskReq_s3.to         := DontCare
  taskReq_s3.cleanBt    := DontCare
  taskReq_s3.writeBt    := DontCare
  taskReq_s3.readDir    := DontCare
  taskReq_s3.wirteSDir  := DontCare
  taskReq_s3.wirteCDir  := DontCare
  taskReq_s3.btWay      := task_s3_g.bits.btWay
  // io
  io.msTask.valid     := needReq & !doneReq
  io.msTask.bits      := taskReq_s3


  /*
   * Update can go s3 logic
   */
  doneSnoop   := Mux(doneSnoop,  !canGo_s3, io.snpTask.fire   & !canGo_s3)
  doneWSDir   := Mux(doneWSDir,  !canGo_s3, io.sDirWrite.fire & !canGo_s3)
  doneWCDir   := Mux(doneWCDir,  !canGo_s3, io.cDirWrite.fire & !canGo_s3)
  doneDSReq   := Mux(doneDSReq,  !canGo_s3, io.dsReq.fire     & !canGo_s3)
  doneReadDB  := Mux(doneReadDB, !canGo_s3, io.dbRCReq.fire   & !canGo_s3)
  doneResp    := Mux(doneResp,   !canGo_s3, io.cpuResp.fire   & !canGo_s3)
  doneReq     := Mux(doneReq,    !canGo_s3, io.msTask.fire    & !canGo_s3)
  val needToDo_s3 = Seq(needSnoop, needWSDir, needWCDir, needDSReq, needReadDB, needResp, needReq)
  val done_s3 = Seq(io.snpTask.fire   | doneSnoop,
                    io.sDirWrite.fire | doneWSDir,
                    io.cDirWrite.fire | doneWCDir,
                    io.dsReq.fire     | doneDSReq,
                    io.dbRCReq.fire   | doneReadDB,
                    io.cpuResp.fire   | doneResp,
                    io.msTask.fire    | doneReq)
  canGo_s3 := needToDo_s3.zip(done_s3).map(a => !a._1 | a._2).reduce(_ & _) & taskTypeVec.asUInt.orR



// -------------------------- Assertion ------------------------------- //
  assert(PopCount(taskTypeVec.asUInt) <= 1.U, "State 3: Task can only be one type")
  assert(Mux(task_s3_g.valid & canGo_s3, PopCount(done_s3).orR, true.B), "State 3: when task_s3 go, must has some task done")
  assert(Mux(io.cpuResp.fire, !genRnRespError, true.B), "GenRnRespError")
  assert(Mux(io.snpTask.fire, !genSnpReqError, true.B), "GenSnpReqError")
  assert(Mux(io.cDirWrite.fire | io.sDirWrite.fire, Mux(taskTypeVec(SNP_RESP), !genNewCohWithSnpError, !genNewCohWithoutSnpError), true.B),
          "GenNewCohWithSnpError or GenNewCohWithoutSnpError")
  // TODO: Delete the following code when the coding is complete
  assert(!needSnp)
  assert(!needDSReq)
}