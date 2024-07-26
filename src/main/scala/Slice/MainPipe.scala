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
    val snpTask     = Decoupled(new MpSnpTaskBundle())
    // Resp to CpuSlave
    val cpuResp     = Decoupled(new RespBundle())
    // Task to Master
    val msTask      = Decoupled(new TaskBundle()) // TODO: simplified it
    // Req to dataBuffer
    val dbRCReq     = Decoupled(new DBRCReq())
    // MainPipe S3 clean or lock BlockTable
    val mpBTReq     = Decoupled(new Bundle {
      val addr      = UInt(addressBits.W)
      val btWay     = UInt(blockWayBits.W)
      val isClean   = Bool()
    })
    val releaseSnp  = Output(Bool())
  })

  // TODO: Delete the following code when the coding is complete
  io.arbTask <> DontCare
  io.dirResp <> DontCare
  io.dsReq <> DontCare
  io.msTask <> DontCare
  io.cpuResp <> DontCare
  io.dbRCReq <> DontCare

  dontTouch(io.cpuResp)

// --------------------- Modules declaration ------------------------//
  val taskQ = Module(new Queue(new TaskBundle(), entries = nrMPQBeat, pipe = true, flow = false))
  val dirResQ = Module(new Queue(new DirResp(), entries = nrMPQBeat + 2, pipe = true, flow = false)) // one for mp_s1 read Dir before send task to mp_2, one for mp_s3

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
  val needSnoop_s3   = WireInit(false.B)
  val needWSDir_s3   = WireInit(false.B)
  val needWCDir_s3   = WireInit(false.B)
  val needRWDS_s3    = WireInit(false.B) // read or write DataStorage
  val needReadDB_s3  = WireInit(false.B)
  val needResp_s3    = WireInit(false.B)
  val needReq_s3     = WireInit(false.B)
  val needWCBT_s3    = WireInit(false.B) // Write or Clean Block Table
  // s3 done signals
  val doneSnoop_s3   = RegInit(false.B)
  val doneWSDir_s3   = RegInit(false.B)
  val doneWCDir_s3   = RegInit(false.B)
  val doneRWDS_s3    = RegInit(false.B)
  val doneReadDB_s3  = RegInit(false.B)
  val doneResp_s3    = RegInit(false.B)
  val doneReq_s3     = RegInit(false.B)
  val doneWCBT_s3    = RegInit(false.B)
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
  val rnValVec    = Wire(Vec(dsuparam.nrCore, Bool()))
  // s3 task signals
  val taskReq_s3  = WireInit(0.U.asTypeOf(new TaskBundle()))
  val taskResp_s3 = WireInit(0.U.asTypeOf(new RespBundle()))



  dontTouch(otherState)
  dontTouch(othRnNS)
  dontTouch(srcState)
  dontTouch(task_s3_g)
  dontTouch(dirRes_s3)
  dontTouch(needSnoop_s3)
  dontTouch(needWSDir_s3)
  dontTouch(needWCDir_s3)
  dontTouch(needRWDS_s3)
  dontTouch(needReadDB_s3)
  dontTouch(needResp_s3)
  dontTouch(needReq_s3)
  dontTouch(needWCBT_s3)
  dontTouch(doneSnoop_s3)
  dontTouch(doneRWDS_s3)
  dontTouch(doneReadDB_s3)
  dontTouch(doneResp_s3)
  dontTouch(doneReq_s3)
  dontTouch(doneWCBT_s3)
  dontTouch(taskTypeVec)


// ---------------------------------------------------------------------------------------------------------------------- //
// ----------------------------------------------- S2: Buffer input task/dirRes ----------------------------------------- //
// ---------------------------------------------------------------------------------------------------------------------- //
  // task queue
  taskQ.io.enq <> io.arbTask
  task_s2.valid := taskQ.io.deq.valid
  task_s2.bits := taskQ.io.deq.bits
  taskQ.io.deq.ready := canGo_s2

  canGo_s2 := canGo_s3 | !task_s3_g.valid

  // dir result queue
  dirResQ.io.enq <> io.dirResp

// ---------------------------------------------------------------------------------------------------------------------- //
// -------------------------- S3_Alloc: Receive task and dirRes from s2 and alloc TaskType -------------------------------//
// ---------------------------------------------------------------------------------------------------------------------- //
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
   * [io.dsReq]   |  from: None                              | to: [CPU / SLICE / MASTER] [coreId]  [reqBufId] // TODO
   * [io.snpTask] |  from: [CPU]    [coreId]  [reqBufId]     | to: None                          // TODO
   * [io.cpuResp] |  from: None                              | to: [CPU]    [coreId]  [reqBufId]
   * [io.msTask]  |  from: [CPU]    [coreId]  [reqBufId]     | to: None                          // TODO
   * [io.dbRCReq] |  from: None                              | to: [CPU]    [coreId]  [reqBufId]
   */
   // TODO: Consider add SNPHLP_RESP
  taskTypeVec(CPU_REQ)    := task_s3_g.valid & dirRes_s3.valid & !task_s3_g.bits.isWB & task_s3_g.bits.from.idL0 === IdL0.CPU
  taskTypeVec(CPU_WRITE)  := task_s3_g.valid & dirRes_s3.valid & task_s3_g.bits.isWB & task_s3_g.bits.from.idL0 === IdL0.CPU
  taskTypeVec(MS_RESP)    := task_s3_g.valid & dirRes_s3.valid & task_s3_g.bits.from.idL0 === IdL0.MASTER
  taskTypeVec(SNP_RESP)   := task_s3_g.valid & dirRes_s3.valid & task_s3_g.bits.from.idL0 === IdL0.SLICE


// ---------------------------------------------------------------------------------------------------------------------- //
// ---------------------------------- S3_GenCoh: Generate new RN / HN Coherence State ------------------------------------//
// ---------------------------------------------------------------------------------------------------------------------- //
  /*
   * generate (rnNS, hnNS) cpuResp:(channel, op, resp)
   */
  // Base signals
  sourceID      := Mux(task_s3_g.bits.from.idL0 === IdL0.CPU, task_s3_g.bits.from.idL1, task_s3_g.bits.to.idL1)
  sourceHit_s3  := client_s3.hitVec(sourceID)
  if(dsuparam.nrCore > 1) otherHit_s3 := PopCount(client_s3.hitVec) > 1.U | (client_s3.hitVec.asUInt.orR & !sourceHit_s3)
  hnState       := Mux(self_s3.hit, self_s3.state, ChiState.I)
  if(dsuparam.nrCore > 1) otherState := Mux(otherHit_s3, client_s3.metas(PriorityEncoder(client_s3.hitVec)).state, ChiState.I)
  srcState      := Mux(sourceHit_s3, client_s3.metas(sourceID).state, ChiState.I)
  // gen new coherence with snoop
  val (srcRnNSWithSnp, othRnNSWithSnp, hSNSWithSnp, genNewCohWithSnpError)  = genNewCohWithSnp(task_s3_g.bits.opcode, hnState, task_s3_g.bits.resp, task_s3_g.bits.isSnpHlp)
  // gen new coherence without snoop
  val (rnNSWithoutSnp, hnNSWithoutSnp, needRDown_, genNewCohWithoutSnpError) = genNewCohWithoutSnp(task_s3_g.bits.opcode, hnState, otherHit_s3)
  val needRDown = needRDown_ & !task_s3_g.bits.isSnpHlp; dontTouch(needRDown) // SnpHlp may trigger needRDown but it not need do it forever
  // gen new coherence when req is write back
  val (rnNSWriteBack, hnNSWriteBack, genCopyBackNewCohError)                = genCopyBackNewCoh(task_s3_g.bits.opcode, hnState, task_s3_g.bits.resp, otherHit_s3)
  // Mux
  srcRnNS   := Mux(taskTypeVec(SNP_RESP), srcRnNSWithSnp, Mux(taskTypeVec(CPU_WRITE), rnNSWriteBack, rnNSWithoutSnp))
  hnNS      := Mux(taskTypeVec(SNP_RESP), hSNSWithSnp,    Mux(taskTypeVec(CPU_WRITE), hnNSWriteBack, hnNSWithoutSnp))
  othRnNS   := Mux(taskTypeVec(SNP_RESP), othRnNSWithSnp, otherState)




// ---------------------------------------------------------------------------------------------------------------------- //
// --------------------- S3_GenReq/Resp: Generate req[replace / snoop] or resp[Comp*]--------------------------//
// ---------------------------------------------------------------------------------------------------------------------- //
  // gen snoop req
  val (snpOp, doNotGoToSD, retToSrc, needSnp, genSnpReqError)                   = genSnpReq(task_s3_g.bits.opcode, hnState, otherState)
  // gen rn resp(expect write back req)
  val (respChnl, respOp, respResp, genRnRespError)                              = genRnResp(task_s3_g.bits.opcode, srcRnNS)
  // gen snoop helper req
  rnValVec := client_s3.metas.map(!_.isInvalid); dontTouch(rnValVec)
  val rnHit = WireInit(client_s3.hitVec.asUInt.orR); dontTouch(rnHit)
  val rnState = WireInit(client_s3.metas(PriorityEncoder(rnValVec)).state); dontTouch(rnState)
  val (snpHlpFakeSrcOp, snpHlpOp, hlpDoNotGoToSD, hlpRetToSrc, needSnpHlp, genSnpHelperReqError) = genSnpHelperReq(srcRnNS, rnHit, rnState)
  // gen replace req
  val snpHlpResp = Mux(task_s3_g.bits.isSnpHlp & task_s3_g.bits.snpHasData, task_s3_g.bits.resp, ChiResp.I)
  val (replOp, needRepl, needWDS, genReplaceReqError)                           = genReplaceReq(hnNS, self_s3.hit, self_s3.state, snpHlpResp)




// ---------------------------------------------------------------------------------------------------------------------- //
// ------------------------------- S3_DoTask: Do task and send signals to other modules ----------------------------------//
// ---------------------------------------------------------------------------------------------------------------------- //

  /*
   * Snoop Req to SnoopCtl
   * needSnoop_s3 is a task
   * needSnp is generated by genSnpReq
   *
   * When modify needSnoop_s3 logic, need to synchronize changes to reqArb
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needSnoop_s3 := needSnp | (needSnpHlp & needResp_s3) }
    is(CPU_WRITE_OH)  { needSnoop_s3 := false.B }
    is(MS_RESP_OH)    { needSnoop_s3 := needSnpHlp }
    is(SNP_RESP_OH)   { needSnoop_s3 := false.B }
  }
  io.snpTask.valid                := needSnoop_s3 & !doneSnoop_s3
  io.snpTask.bits.from            := task_s3_g.bits.from
  io.snpTask.bits.srcOp           := Mux(needSnp, task_s3_g.bits.opcode, snpHlpFakeSrcOp)
  io.snpTask.bits.opcode          := Mux(needSnp, snpOp, snpHlpOp)
  io.snpTask.bits.addr            := client_s3.addr
  io.snpTask.bits.hitVec          := Mux(needSnp, client_s3.hitVec, rnValVec)
  io.snpTask.bits.isSnpHlp        := needSnpHlp
  io.snpTask.bits.btWay           := task_s3_g.bits.btWay
  io.snpTask.bits.snpRetToSrc     := Mux(needSnp, retToSrc, hlpRetToSrc)
  io.snpTask.bits.snpDoNotGoToSD  := Mux(needSnp, doNotGoToSD, hlpDoNotGoToSD)

  // release snp count in req arbiter
  io.releaseSnp := task_s3_g.valid & canGo_s3 & task_s3_g.bits.willSnp

  /*
   * Write or Read DS logic
   * readDS: (resp to cpu when hit in hn)
   * *** [DS]  ---> [DB] ---> [CPU]
   * wrietDS: (cpu write back / snp get data to hn without replace)
   * *** [CPU] ---> [DB] ---> [DS]
   * read&writeDS: (cpu write back / snp get data to hn with replace)
   * *** 0:[CPU] --d0--> [DB]
   * *** 1:[DS]  --d1--> [DB] --d1--> [MS]
   * *** 2:[DB]  --d0--> [DS]
   */
  val rDS = needResp_s3 & !needRDown & !needSnp & taskResp_s3.isRxDat
  val wDS = needWDS
  val rwDS = needWDS & needRepl
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needRWDS_s3 := rDS }
    is(CPU_WRITE_OH)  { needRWDS_s3 := wDS | rwDS }
    is(MS_RESP_OH)    { needRWDS_s3 := false.B }
    is(SNP_RESP_OH)   { needRWDS_s3 := wDS | rwDS }
  }
  io.dsReq.valid := needRWDS_s3 & !doneRWDS_s3
  io.dsReq.bits.addr := task_s3_g.bits.addr
  io.dsReq.bits.wayOH := self_s3.wayOH
  io.dsReq.bits.ren := rDS | rwDS
  io.dsReq.bits.wen := wDS | rwDS
  io.dsReq.bits.to.idL0 := Mux(needRepl, IdL0.MASTER,         task_s3_g.bits.from.idL0)
  io.dsReq.bits.to.idL1 := Mux(needRepl, DontCare,            task_s3_g.bits.from.idL1)
  io.dsReq.bits.to.idL2 := Mux(needRepl, task_s3_g.bits.dbid, task_s3_g.bits.from.idL2)
  io.dsReq.bits.dbid := task_s3_g.bits.dbid


  /*
   * Read/Clean DB logic
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needReadDB_s3 := false.B } // data from DS
    is(CPU_WRITE_OH)  { needReadDB_s3 := false.B }
    is(MS_RESP_OH)    { needReadDB_s3 := needResp_s3 & io.cpuResp.bits.isRxDat }
    is(SNP_RESP_OH)   { needReadDB_s3 := (needResp_s3 & io.cpuResp.bits.isRxDat) | (task_s3_g.bits.isSnpHlp & task_s3_g.bits.snpHasData & !needRWDS_s3) }
  }
  io.dbRCReq.valid := needReadDB_s3 & !doneReadDB_s3
  io.dbRCReq.bits.to := task_s3_g.bits.to
  io.dbRCReq.bits.dbid := task_s3_g.bits.dbid
  io.dbRCReq.bits.isRead := !task_s3_g.bits.isSnpHlp
  io.dbRCReq.bits.isClean := !rwDS // snoop data will be clean by DS


  /*
   * Write Self Directory
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needWSDir_s3 := hnNS =/= hnState & !needRDown & !needSnp }
    is(CPU_WRITE_OH)  { needWSDir_s3 := hnNS =/= hnState }
    is(MS_RESP_OH)    { needWSDir_s3 := hnNS =/= hnState }
    is(SNP_RESP_OH)   { needWSDir_s3 := hnNS =/= hnState }
  }
  io.sDirWrite.valid          := needWSDir_s3 & !doneWSDir_s3
  io.sDirWrite.bits.state     := hnNS
  io.sDirWrite.bits.addr      := task_s3_g.bits.addr
  io.sDirWrite.bits.wayOH     := self_s3.wayOH
  if(useRepl) io.sDirWrite.bits.replMesOpt.get := self_s3.replMesOpt.get


  /*
   * Write Client Directory
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needWCDir_s3 := srcRnNS =/= srcState & !needRDown & !needSnp }
    is(CPU_WRITE_OH)  { needWCDir_s3 := srcRnNS =/= srcState  }
    is(MS_RESP_OH)    { needWCDir_s3 := srcRnNS =/= srcState }
    is(SNP_RESP_OH)   { needWCDir_s3 := (srcRnNS =/= srcState | othRnNS =/= otherState) & !task_s3_g.bits.isSnpHlp }
  }
  io.cDirWrite.valid                := needWCDir_s3 & !doneWCDir_s3
  io.cDirWrite.bits.addr            := task_s3_g.bits.addr
  io.cDirWrite.bits.metas.map(_.state).zipWithIndex.foreach {
    case(state, i) =>
      if(dsuparam.nrCore > 1) state := Mux(task_s3_g.bits.to.idL1 === i.U, srcRnNS, Mux(client_s3.hitVec(i), othRnNS, ChiState.I))
      else                    state := srcRnNS
  }
  io.cDirWrite.bits.wayOH := client_s3.wayOH
  if(useRepl) io.cDirWrite.bits.replMesOpt.get := client_s3.replMesOpt.get


  /*
   * Output to resp logic
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needResp_s3 := !needRDown & !needSnp }
    is(CPU_WRITE_OH)  { needResp_s3 := false.B }
    is(MS_RESP_OH)    { needResp_s3 := true.B }
    is(SNP_RESP_OH)   { needResp_s3 := true.B & !task_s3_g.bits.isSnpHlp }
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
  taskResp_s3.cleanBt   := !(needWCBT_s3 & !io.mpBTReq.bits.isClean) // when it write bt, cpuSlave dont need to clean bt
  // io
  io.cpuResp.valid      := needResp_s3 & !doneResp_s3
  io.cpuResp.bits       := taskResp_s3


  /*
   * Output to req logic
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needReq_s3 := needRDown }
    is(CPU_WRITE_OH)  { needReq_s3 := needRepl }
    is(MS_RESP_OH)    { needReq_s3 := false.B  }
    is(SNP_RESP_OH)   { needReq_s3 := needRepl }
  }
  // bits
  taskReq_s3.opcode     := Mux(needRDown, task_s3_g.bits.opcode, replOp)
  taskReq_s3.addr       := Mux(needRDown, task_s3_g.bits.addr, self_s3.addr)
  taskReq_s3.isWB       := needRepl
  taskReq_s3.from       := task_s3_g.bits.from
  taskReq_s3.btWay      := task_s3_g.bits.btWay
  taskReq_s3.dbid       := task_s3_g.bits.dbid
  // io
  io.msTask.valid       := needReq_s3 & !doneReq_s3
  io.msTask.bits        := taskReq_s3

  /*
   * Update Block Table
   */
  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)    { needWCBT_s3 := needResp_s3 & needSnpHlp }
    is(CPU_WRITE_OH)  { needWCBT_s3 := true.B }  // TODO: Risk of data errors because Read requests may enter the main pipeline when data is not written back to the SN
    is(MS_RESP_OH)    { needWCBT_s3 := needSnpHlp }
    is(SNP_RESP_OH)   { needWCBT_s3 := task_s3_g.bits.isSnpHlp }
  }
  io.mpBTReq.valid := needWCBT_s3 & !doneWCBT_s3
  io.mpBTReq.bits.isClean := !needSnpHlp
  io.mpBTReq.bits.btWay := task_s3_g.bits.btWay
  io.mpBTReq.bits.addr := Mux(needRepl, self_s3.addr, Mux(needSnpHlp, client_s3.addr, task_s3_g.bits.addr)) // write new block tag when need snpHlp


  /*
   * Update can go s3 logic
   */
  doneSnoop_s3   := Mux(doneSnoop_s3,  !canGo_s3, io.snpTask.fire   & !canGo_s3)
  doneWSDir_s3   := Mux(doneWSDir_s3,  !canGo_s3, io.sDirWrite.fire & !canGo_s3)
  doneWCDir_s3   := Mux(doneWCDir_s3,  !canGo_s3, io.cDirWrite.fire & !canGo_s3)
  doneRWDS_s3    := Mux(doneRWDS_s3,   !canGo_s3, io.dsReq.fire     & !canGo_s3)
  doneReadDB_s3  := Mux(doneReadDB_s3, !canGo_s3, io.dbRCReq.fire   & !canGo_s3)
  doneResp_s3    := Mux(doneResp_s3,   !canGo_s3, io.cpuResp.fire   & !canGo_s3)
  doneReq_s3     := Mux(doneReq_s3,    !canGo_s3, io.msTask.fire    & !canGo_s3)
  doneWCBT_s3    := Mux(doneWCBT_s3,   !canGo_s3, io.mpBTReq.fire   & !canGo_s3)
  val needToDo_s3 = Seq(needSnoop_s3, needWSDir_s3, needWCDir_s3, needRWDS_s3, needReadDB_s3, needResp_s3, needReq_s3, needWCBT_s3)
  val done_s3 = Seq(io.snpTask.fire   | doneSnoop_s3,
                    io.sDirWrite.fire | doneWSDir_s3,
                    io.cDirWrite.fire | doneWCDir_s3,
                    io.dsReq.fire     | doneRWDS_s3,
                    io.dbRCReq.fire   | doneReadDB_s3,
                    io.cpuResp.fire   | doneResp_s3,
                    io.msTask.fire    | doneReq_s3,
                    io.mpBTReq.fire   | doneWCBT_s3)
  canGo_s3 := needToDo_s3.zip(done_s3).map(a => !a._1 | a._2).reduce(_ & _) & taskTypeVec.asUInt.orR

// -------------------------- Assertion ------------------------------- //
  assert(PopCount(taskTypeVec.asUInt) <= 1.U, "State 3: Task can only be one type")
  assert(Mux(task_s3_g.valid & canGo_s3, PopCount(done_s3).orR, true.B), "State 3: when task_s3 go, must has some task done")

  assert(!(needSnp & needSnpHlp), "NeedSnp and NeedSnpHlp Cant be valid at the same time")
  assert(!(needRDown & needRepl), "NeedRDown and NeedRepl Cant be valid at the same time")
  assert(!(needSnpHlp & needRepl), "NeedSnpHlp and NeedRepl Cant be valid at the same time")

  switch(taskTypeVec.asUInt) {
    is(CPU_REQ_OH)   { assert(!genSnpReqError & !genSnpHelperReqError & !genNewCohWithoutSnpError & !genRnRespError, "CPU_REQ has something error") }
    is(CPU_WRITE_OH) { assert(!genCopyBackNewCohError & !genReplaceReqError, "CPU_WRITE has something error") }
    is(MS_RESP_OH)   { assert(!genNewCohWithoutSnpError & !genRnRespError, "MS_RESP has something error") }
    is(SNP_RESP_OH)  { assert(!genNewCohWithSnpError & !genReplaceReqError & (!genRnRespError | task_s3_g.bits.isSnpHlp), "SNP_RESP has something error") }
  }

  assert(Mux(dirRes_s3.valid, PopCount(dirRes_s3.bits.self.wayOH) === 1.U, true.B), "OneHot Error")
  assert(Mux(dirRes_s3.valid, PopCount(dirRes_s3.bits.client.wayOH) === 1.U, true.B), "OneHot Error")

  assert(Mux(io.dsReq.fire & io.dsReq.bits.wen & taskTypeVec(SNP_RESP), task_s3_g.bits.snpHasData, true.B))
  assert(Mux(io.cpuResp.fire & io.cpuResp.bits.isRxDat & taskTypeVec(SNP_RESP), task_s3_g.bits.snpHasData, true.B))

  // TIME OUT CHECK
  val cntReg = RegInit(0.U(64.W))
  cntReg := Mux(!task_s3_g.valid | canGo_s3, 0.U, cntReg + 1.U)
  assert(cntReg < 5000.U, "MAINPIPE S3 TASK[0x%x] ADDR[0x%x] OP[0x%x] SNPHLP[0x%x] TIMEOUT", OHToUInt(taskTypeVec), task_s3_g.bits.addr, task_s3_g.bits.opcode, task_s3_g.bits.isSnpHlp.asUInt)
}