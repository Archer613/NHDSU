package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import org.chipsalliance.cde.config._
import chisel3.util.{Decoupled, PopCount, RegEnable, ValidIO, log2Ceil, Cat}

// TODO: Stall writeBack when has some reqBuf Snoop RN(already get dbid) is same addr with it
// TODO: Stall snoop when has some reqBuf writeBack HN(already get dbid) is same addr with it

class ReqBuf()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val free        = Output(Bool())
    val cpuSlvId    = Input(UInt(coreIdBits.W))
    val reqBufId    = Input(UInt(reqBufIdBits.W))
    // CHI
    val chi         = Flipped(CHIBundleDecoupled(chiBundleParams))
    val txDatId     = ValidIO(UInt(chiTxnidBits.W))
    val txRspId     = ValidIO(UInt(chiTxnidBits.W))
    // mainpipe
    val mpTask      = Decoupled(new TaskBundle())
    val mpResp      = Flipped(ValidIO(new RespBundle()))
    // snpCtrl
    val snpTask     = Flipped(Decoupled(new SnpTaskBundle()))
    val snpResp     = Decoupled(new SnpRespBundle())
    // dataBuffer
    val wReq        = Decoupled(new CpuDBWReq())
    val wResp       = Flipped(Decoupled(new CpuDBWResp()))
    val dbDataValid = Input(Bool())
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.snpTask := DontCare
  io.snpResp := DontCare
  dontTouch(io)

// --------------------- Modules declaration ---------------------//


// --------------------- Reg and Wire declaration ------------------------//
  val freeReg         = RegInit(true.B)
  val reqTxnIDReg     = RegInit(0.U.asTypeOf(io.chi.txreq.bits.txnID))
  val dbidReg         = RegInit(0.U((bankBits+dbIdBits).W))
  val taskReg         = RegInit(0.U.asTypeOf(new TaskBundle()))
  val respReg         = RegInit(0.U.asTypeOf(new RespBundle()))
  val fsmReg          = RegInit(0.U.asTypeOf(new RBFSMState()))
  val reqValid        = WireInit(false.B)
  val alloc           = WireInit(false.B)
  val release         = WireInit(false.B)
  val task            = WireInit(0.U.asTypeOf(new TaskBundle()))
  val getAllDB        = WireInit(false.B)
  val getDBNumReg     = RegInit(0.U(log2Ceil(nrBeat+1).W))
  val getAllDat       = WireInit(false.B)
  val getDatNumReg    = RegInit(0.U(log2Ceil(nrBeat + 1).W))
  val cleanTask       = WireInit(0.U.asTypeOf(new TaskBundle()))
  val cleanTaskVal    = WireInit(false.B)
  val snpDoNotGoToSDReg = RegInit(false.B)
  val snpRetToSrcReg    = RegInit(false.B)
  val snpRespReg      = RegInit(0.U.asTypeOf(new SnpRespBundle()))
  val snpTxnid        = WireInit(0.U(chiTxnidBits.W))

  dontTouch(reqTxnIDReg)
  dontTouch(dbidReg)
  dontTouch(fsmReg)
  dontTouch(getAllDB)
  dontTouch(getDBNumReg)
  dontTouch(getAllDat)
  dontTouch(getDatNumReg)
  dontTouch(release)
  dontTouch(snpTxnid)

// ---------------------------  Receive Req/Resp Logic --------------------------------//
  /*
   * Receive Cpu/Snp Req
   */
  reqValid := io.chi.txreq.valid | io.snpTask.valid
  when(io.chi.txreq.valid) {
    taskReg         := task
    val txreq       = io.chi.txreq.bits
    // task base message
    task.opcode     := txreq.opcode
    task.isWB       := txreq.opcode === CHIOp.REQ.WriteBackFull
    // task addr
    task.addr       := txreq.addr
    // task id
    task.from.idL0  := IdL0.CPU
    task.from.idL1  := io.cpuSlvId
    task.from.idL2  := io.reqBufId
    task.to.idL0    := IdL0.SLICE
    task.to.idL1    := parseAddress(txreq.addr)._2
    // task other
    task.cleanBt    := false.B
    task.writeBt    := true.B
    task.readDir    := true.B
    task.willSnp    := !task.isWB
    // other
    reqTxnIDReg     := txreq.txnID
  }.elsewhen(io.snpTask.valid) {
    taskReg         := task
    val snp         = io.snpTask.bits
    task.opcode     := snp.opcode
    task.addr       := snp.addr
    task.from       := snp.from
    // other
    snpDoNotGoToSDReg := snp.snpDoNotGoToSD
    snpRetToSrcReg  := snp.snpRetToSrc
  }


  /*
   * Receive Cpu Resp
   */
  respReg := Mux(io.mpResp.fire, io.mpResp.bits, respReg)

  /*
   * Receive and Count dataBuffer Data Valid
   */
  getDBNumReg := Mux(release, 0.U, getDBNumReg + (io.dbDataValid & io.chi.rxdat.fire).asUInt)
  getAllDB    := getDBNumReg === nrBeat.U | (getDBNumReg === (nrBeat - 1).U & (io.dbDataValid & io.chi.rxdat.fire))

  /*
   * Receive dbid from dataBuffer
   * dbid' = Cat(sliceId, dbid), Add sliceId in dbid to distinguish sources
   */
  dbidReg           := Mux(io.wResp.fire, Cat(io.wResp.bits.from.idL1, io.wResp.bits.dbid), Mux(io.free, 0.U, dbidReg))
  taskReg.dbid      := dbidReg

  /*
   * Receive CputxDat.resp and Count txDat Data Valid
   */
  getDatNumReg  := Mux(release, 0.U, getDatNumReg + io.chi.txdat.fire.asUInt)
  getAllDat     := getDatNumReg === nrBeat.U | (getDatNumReg === (nrBeat - 1).U &  io.chi.txdat.fire)
  taskReg.resp  := Mux(io.chi.txdat.fire, io.chi.txdat.bits.resp, taskReg.resp)

  /*
   * Receive snp resp
   */
  when(fsmReg.w_snpResp & (io.chi.txrsp.fire | io.chi.txdat.fire)) {
    val rsp = io.chi.txrsp.bits
    val dat = io.chi.txdat.bits
    snpRespReg.resp := Mux(io.chi.txrsp.fire, rsp.resp, dat.resp)
    snpRespReg.hasData := snpRetToSrcReg
    snpRespReg.dbid := dbidReg // TODO: clean dataBuffer when snpRetToSrc bug get txrsp
  }


// ---------------------------  Output Req/Resp Logic --------------------------------//

  /*
   * for txDat or txRsp sel reqBuf input
   * WriteBack:         txnid = dbid
   * Snp With Data:     txnid = Cat(1.U, dbid)
   * snp Without Data:  txnid = reqBufId
   */
  io.txDatId.valid := fsmReg.w_rnData
  io.txDatId.bits := dbidReg // for cpuTxDat determine destination
  io.txRspId.valid := fsmReg.w_snpResp | fsmReg.w_compAck
  io.txRspId.bits := Mux(fsmReg.w_compAck, io.reqBufId, snpTxnid)


  /*
   * Output snoop Req
   *
   * reqBufId'  = Cat(0.U, reqBufId)
   * dbid'      = Cat(1.U, sliceId, dbid)
   *
   * !snpRetToSrc:
   * *** ReqBuf ---Snoop[txnid = reqBufId']--->      RN
   * *** RN     ---SnpResp[txnid = reqBufId']--->    ReqBuf
   * snpRetToSrc:
   * *** ReqBuf ---Snoop[txnid = dbid']--->         RN
   * *** RN     ---SnpRespData[txnid = dbid']--->   ReqBuf
   * *** RN     ---SnpRespData[txnid = dbid']--->   DataBuffer
   */
  val temp = WireInit(0.U((chiTxnidBits - 1).W))
  temp := dbidReg
  snpTxnid := Mux(snpRetToSrcReg, Cat(1.U, temp), io.reqBufId)
  io.chi.rxsnp.valid            := fsmReg.s_snp & !fsmReg.w_dbid
  io.chi.rxsnp.bits.srcID       := dsuparam.idmap.HNID.U
  io.chi.rxsnp.bits.txnID       := snpTxnid
  io.chi.rxsnp.bits.addr        := taskReg.addr(addressBits-1, addressBits-io.chi.rxsnp.bits.addr.getWidth)
  io.chi.rxsnp.bits.opcode      := taskReg.opcode
  io.chi.rxsnp.bits.retToSrc    := snpRetToSrcReg
  io.chi.rxsnp.bits.doNotGoToSD := snpDoNotGoToSDReg

  /*
   * Output snp resp to snpCtl
   */
  io.snpResp.valid := fsmReg.s_snpResp & !fsmReg.w_snpResp
  io.snpResp.bits := snpRespReg

  /*
   * wReq output to dataBuffer
   */
  io.wReq.bits.from.idL0  := IdL0.CPU
  io.wReq.bits.from.idL1  := io.cpuSlvId
  io.wReq.bits.from.idL2  := io.reqBufId
  io.wReq.bits.to.idL0    := IdL0.SLICE
  io.wReq.bits.to.idL1    := taskReg.to.idL1
  io.wReq.bits.to.idL2    := DontCare
  io.wReq.valid           := fsmReg.s_getDBID


  /*
   * task or resp output
   */
  cleanTask.from.idL0 := IdL0.CPU
  cleanTask.from.idL1 := io.cpuSlvId
  cleanTask.from.idL2 := io.reqBufId
  cleanTask.to.idL0   := IdL0.SLICE
  cleanTask.addr      := taskReg.addr
  cleanTask.btWay     := respReg.btWay
  cleanTask.cleanBt   := true.B
  cleanTask.writeBt   := false.B
  cleanTaskVal        := fsmReg.s_clean & PopCount(fsmReg.asUInt) === 1.U // only clean need to do
  io.mpTask.valid     := (fsmReg.s_wbReq2mp & !fsmReg.w_rnData) | fsmReg.s_req2mp | cleanTaskVal
  io.mpTask.bits      := Mux(cleanTaskVal, cleanTask, taskReg)

  /*
   * chi rxdat output
   */
  io.chi.rxdat.valid        := fsmReg.s_resp & fsmReg.w_dbData & io.dbDataValid & !fsmReg.w_mpResp
  io.chi.rxdat.bits.opcode  := respReg.opcode
  // IDs
  io.chi.rxdat.bits.tgtID   := dsuparam.idmap.RNID(0).U
  io.chi.rxdat.bits.srcID   := dsuparam.idmap.HNID.U
  io.chi.rxdat.bits.txnID   := reqTxnIDReg
  io.chi.rxdat.bits.homeNID := dsuparam.idmap.HNID.U
  io.chi.rxdat.bits.dbID    := io.reqBufId
  io.chi.rxdat.bits.resp    := respReg.resp

  /*
   * chi rxrsp output
   */
  val compVal                 = fsmReg.s_resp & !fsmReg.w_mpResp & !fsmReg.w_dbData
  val dbdidRespVal            = fsmReg.s_dbidResp & !fsmReg.w_dbid
  io.chi.rxrsp.valid          := compVal | dbdidRespVal
  io.chi.rxrsp.bits.opcode    := Mux(compVal, respReg.opcode, CHIOp.RSP.CompDBIDResp)
  // IDs
  io.chi.rxrsp.bits.tgtID     := dsuparam.idmap.RNID(0).U
  io.chi.rxrsp.bits.srcID     := dsuparam.idmap.HNID.U
  io.chi.rxrsp.bits.txnID     := reqTxnIDReg
  io.chi.rxrsp.bits.dbID      := Mux(compVal, io.reqBufId, dbidReg)
  io.chi.rxrsp.bits.pCrdType  := 0.U // This system dont support Transaction Retry
  io.chi.rxrsp.bits.resp      := Mux(compVal, respReg.resp, 0.U)


  /*
   * IO ready ctrl logic
   * Whether the io.chi.txreq and io.snpTask can be input is determined by io.free in ReqBufSel
   */
  io.snpTask.ready   := true.B // Always be true
  io.chi.txreq.ready := true.B // Always be true
  io.chi.txdat.ready := true.B // Always be true
  io.chi.txrsp.ready := true.B // Always be true
  io.wResp.ready     := true.B // Always be true

// ---------------------------  ReqBuf State release/alloc/set logic --------------------------------//
  /*
   * ReqBuf release logic
   */
  alloc := !RegNext(reqValid) & reqValid
  release := fsmReg.asUInt === 0.U // all s_task/w_task done
  freeReg := Mux(release & !alloc, true.B, Mux(alloc, false.B, freeReg))
  io.free := freeReg


  /*
   * Alloc or Set state
   */
  when(io.chi.txreq.fire & task.isWB) {
    // send
    fsmReg.s_wbReq2mp := true.B
    fsmReg.s_getDBID  := true.B
    fsmReg.s_dbidResp := true.B
    // wait
    fsmReg.w_dbid     := true.B
    fsmReg.w_rnData   := true.B
  }.elsewhen(io.chi.txreq.fire & !task.isWB) {
    // send
    fsmReg.s_req2mp   := true.B
    fsmReg.s_resp     := true.B
    fsmReg.s_clean    := true.B
    // wait
    fsmReg.w_mpResp   := true.B
    fsmReg.w_compAck  := io.chi.txreq.bits.expCompAck
  }.elsewhen(io.snpTask.fire) {
    // send
    fsmReg.s_snp      := true.B
    fsmReg.s_snpResp  := true.B
    fsmReg.s_getDBID  := io.snpTask.bits.snpRetToSrc
    // wait
    fsmReg.w_snpResp  := true.B
    fsmReg.w_rnData   := io.snpTask.bits.snpRetToSrc
    fsmReg.w_dbid     := io.snpTask.bits.snpRetToSrc
  }.otherwise {
    /*
     * req(expect write back) fsm task
     */
    // send
    fsmReg.s_req2mp   := Mux(io.mpTask.fire, false.B, fsmReg.s_req2mp)
    fsmReg.s_resp     := Mux(io.chi.rxrsp.fire | (io.chi.rxdat.fire & getAllDB), false.B, fsmReg.s_resp)
    fsmReg.s_clean    := Mux((io.mpTask.fire & io.mpTask.bits.cleanBt) | (io.mpResp.fire & !io.mpResp.bits.cleanBt), false.B, fsmReg.s_clean)
    // wait
    fsmReg.w_mpResp   := Mux(io.mpResp.fire, false.B, fsmReg.w_mpResp)
    fsmReg.w_dbData   := Mux(io.mpResp.fire & io.mpResp.bits.isRxDat, true.B, Mux(getAllDB, false.B, fsmReg.w_dbData))
    fsmReg.w_compAck  := Mux(io.chi.txrsp.fire & io.chi.txrsp.bits.opcode === CHIOp.RSP.CompAck, false.B, fsmReg.w_compAck)
    /*
     * write back fsm task
     */
    // send
    fsmReg.s_wbReq2mp := Mux(io.mpTask.fire, false.B, fsmReg.s_wbReq2mp)
    fsmReg.s_dbidResp := Mux(io.chi.rxrsp.fire, false.B, fsmReg.s_dbidResp)
    /*
     * snp fsm task
     */
    // send
    fsmReg.s_snp      := Mux(io.chi.rxsnp.fire, false.B ,fsmReg.s_snp)
    fsmReg.s_snpResp  := Mux(io.snpResp.fire, false.B ,fsmReg.s_snpResp)
    // wait
    fsmReg.w_snpResp  := Mux(io.chi.txrsp.fire | io.chi.txdat.fire, false.B, fsmReg.w_snpResp)
    /*
     * dataBuffer fsm task
     */
    fsmReg.s_getDBID  := Mux(io.wReq.fire, false.B, fsmReg.s_getDBID)
    fsmReg.w_dbid     := Mux(io.wResp.fire, false.B, fsmReg.w_dbid)
    fsmReg.w_rnData   := Mux(getAllDat | (io.chi.txrsp.fire & fsmReg.w_snpResp), false.B, fsmReg.w_rnData) // when need wait snp resp data but only get resp, cancel w_rnData
  }


// --------------------- Assertion ------------------------------- //
  assert(Mux(!freeReg, !(io.chi.txreq.valid | io.snpTask.valid), true.B), "When ReqBuf valid, it cant input new req")
  assert(Mux(io.chi.txreq.valid | io.snpTask.valid, io.free, true.B), "Reqbuf cant block req input")
  assert(!(io.chi.txreq.valid & io.snpTask.valid), "Reqbuf cant receive txreq and snpTask at the same time")
  when(release) {
    assert(fsmReg.asUInt === 0.U, "when ReqBuf release, all task should be done")
  }
  assert(Mux(getDBNumReg === nrBeat.U, !io.dbDataValid, true.B), "ReqBuf get data from DataBuf overflow")
  assert(Mux(io.dbDataValid, fsmReg.s_resp & fsmReg.w_dbData, true.B), "When dbDataValid, ReqBuf should set s_resp and w_data")
  assert(Mux(io.dbDataValid, !fsmReg.w_mpResp, true.B), "When dbDataValid, ReqBuf should has been receive mpResp")

  assert(Mux(fsmReg.w_snpResp & io.chi.txrsp.fire, !io.chi.txrsp.bits.resp(2), true.B))

  val cntReg = RegInit(0.U(64.W))
  cntReg := Mux(io.free, 0.U, cntReg + 1.U)
  assert(cntReg < 5000.U, "REQBUF[0x%x] ADDR[0x%x] OP[0x%x] SNP[0x%x] TIMEOUT", io.reqBufId, taskReg.addr, taskReg.opcode, taskReg.from.isSLICE.asUInt)
}