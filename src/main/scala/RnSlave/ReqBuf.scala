package DONGJIANG.RNSLAVE

import DONGJIANG._
import DONGJIANG.IdL0._
import DONGJIANG.CHI._
import chisel3._
import org.chipsalliance.cde.config._
import chisel3.util.{Cat, Decoupled, PopCount, RegEnable, Valid, ValidIO, log2Ceil}

class RBFSMState(implicit p: Parameters) extends Bundle {
  // schedule
  val s_snp       = Bool()
  val s_snpResp   = Bool()
  val s_req2mshr  = Bool()
  val s_resp      = Bool()
  val s_udpMSHR   = Bool()
  val s_getDBID   = Bool()
  val s_dbidResp  = Bool()

  // wait
  val w_snpResp   = Bool()
  val w_mpResp    = Bool()
  val w_dbid      = Bool()
  val w_dbData    = Bool()
  val w_rnData    = Bool()
  val w_compAck   = Bool()
}


class ReqBuf(rnSlvId: Int, reqBufId: Int)(implicit p: Parameters) extends DJModule {
  val nodeParam = djparam.rnNodeMes(rnSlvId)

// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val free          = Output(Bool())
    // CHI
    val chi           = Flipped(CHIBundleDecoupled(chiParams))
    // slice ctrl signals
    val reqTSlice     = Decoupled(new RnReqOutBundle())
    val respFSlice    = Flipped(Decoupled(new RnRespInBundle()))
    val reqFSlice     = Flipped(Decoupled(new RnReqInBundle()))
    val respTSlice    = Decoupled(new RnRespOutBundle())
    // For txDat and rxDat sinasl
    val reqBufDBID    = Valid(new Bundle {
      val bankId      = UInt(bankBits.W)
      val dbid        = UInt(dbIdBits.W)
    })
    // slice DataBuffer signals
    val wReq          = Decoupled(new RnDBWReq())
    val wResp         = Flipped(Decoupled(new RnDBWResp()))
    val dataFDBVal    = Input(Bool())
  })

// --------------------- Reg and Wire declaration ------------------------//
  // req reg
  val reqReg            = RegInit(0.U.asTypeOf(new DJBundle with HasFromIDBits {
    val addr            = UInt(addressBits.W)
    val opcode          = UInt(6.W)
    val txnId           = UInt(chiParams.txnidBits.W)
    val srcId           = UInt(chiParams.nodeIdBits.W)
    // Snp Mes
    val retToSrc        = Bool()
    val doNotGoToSD     = Bool()
  }))
  // req from slice or txreq
  val reqFSlice         = WireInit(0.U.asTypeOf(reqReg))
  val reqFTxReq         = WireInit(0.U.asTypeOf(reqReg))
  val reqIsWrite        = WireInit(false.B)
  // reqBuf Ctrl
  val freeReg           = RegInit(true.B)
  val fsmReg            = RegInit(0.U.asTypeOf(new RBFSMState))
  // data crtl
  val getDBNumReg       = RegInit(0.U(log2Ceil(nrBeat + 1).W))
  val getTxDatNumReg    = RegInit(0.U(log2Ceil(nrBeat + 1).W))
  val getAllData        = WireInit(false.B) // get all Data from DataBuffer or TxDat
  val dbidReg           = RegInit(0.U(dbIdBits.W))
  val dbidBankIdReg     = RegInit(0.U(dbIdBits.W)) // dbid from which bank
  // snoop resp reg
  val snpRespReg        = RegInit(0.U(3.W))
  val snpRespHasDataReg = RegInit(false.B)
  // slice resp reg
  val sliceRespReg      = RegInit(0.U.asTypeOf(new RnRespInBundle()))



// ---------------------------  ReqBuf State release/alloc/set logic --------------------------------//
  /*
   * ReqBuf release logic
   */
  val alloc   = io.reqFSlice.fire | io.chi.txreq.fire
  val release = fsmReg.asUInt === 0.U // all s_task / w_task done
  freeReg     := Mux(release & !alloc, true.B, Mux(alloc, false.B, freeReg))
  io.free     := freeReg


  /*
  * Alloc or Set state
  */
  when(io.chi.txreq.fire & reqIsWrite) {
    // send
    fsmReg.s_req2mshr := true.B
    fsmReg.s_getDBID  := true.B
    fsmReg.s_dbidResp := true.B
    // wait
    fsmReg.w_dbid     := true.B
    fsmReg.w_rnData   := true.B
  }.elsewhen(io.chi.txreq.fire) {
    // send
    fsmReg.s_req2mshr := true.B
    fsmReg.s_resp     := true.B
    fsmReg.s_udpMSHR  := true.B
    // wait
    fsmReg.w_mpResp   := true.B
    fsmReg.w_compAck  := io.chi.txreq.bits.expCompAck
  }.elsewhen(io.reqFSlice.fire) {
    // send
    fsmReg.s_snp      := true.B
    fsmReg.s_snpResp  := true.B
    fsmReg.s_getDBID  := io.reqFSlice.bits.retToSrc
    // wait
    fsmReg.w_snpResp  := true.B
    fsmReg.w_rnData   := io.reqFSlice.bits.retToSrc
    fsmReg.w_dbid     := io.reqFSlice.bits.retToSrc
  }.otherwise {
    /*
     * Common
     */
    // send
    fsmReg.s_req2mshr := Mux(io.reqTSlice.fire, false.B, fsmReg.s_req2mshr)
    fsmReg.s_getDBID  := Mux(io.wReq.fire, false.B, fsmReg.s_getDBID)
    // wait
    fsmReg.w_dbid     := Mux(io.wResp.fire, false.B, fsmReg.w_dbid)
    fsmReg.w_rnData   := Mux((io.chi.rxdat.fire & getAllData) | (io.chi.txrsp.fire & fsmReg.w_snpResp), false.B, fsmReg.w_rnData) // when need wait snp resp data but only get resp, cancel w_rnData

    /*
     * Deal CHI TxReq (except Write)
     */
    // send
    fsmReg.s_resp     := Mux(io.chi.rxrsp.fire | (io.chi.rxdat.fire & getAllData), false.B, fsmReg.s_resp)
    fsmReg.s_udpMSHR  := Mux(io.respTSlice.fire, false.B, fsmReg.s_udpMSHR)
    // wait
    fsmReg.w_mpResp   := Mux(io.respFSlice.fire, false.B, fsmReg.w_mpResp)
    fsmReg.w_dbData   := Mux(io.respFSlice.fire & io.respFSlice.bits.isRxDat, true.B, Mux(getAllData, false.B, fsmReg.w_dbData))
    fsmReg.w_compAck  := Mux(io.chi.txrsp.fire & io.chi.txrsp.bits.opcode === CHIOp.RSP.CompAck, false.B, fsmReg.w_compAck)

    /*
     * Deal CHI TxReq (Write)
     */
    // send
    fsmReg.s_dbidResp := Mux(io.chi.rxrsp.fire, false.B, fsmReg.s_dbidResp)

    /*
     * Deal Req from Slice (Snoop)
     */
    // send
    fsmReg.s_snp      := Mux(io.chi.rxsnp.fire, false.B ,fsmReg.s_snp)
    fsmReg.s_snpResp  := Mux(io.respTSlice.fire, false.B ,fsmReg.s_snpResp)
    // wait
    fsmReg.w_snpResp  := Mux(io.chi.txrsp.fire | (io.chi.txdat.fire & getAllData), false.B, fsmReg.w_snpResp)
  }


// ---------------------------  Receive Req(TxReq and ReqFSlice) Logic --------------------------------//
  /*
   * Receive reqFSlice(Snoop)
   */
  reqFSlice.addr      := io.reqFSlice.bits.addr
  reqFSlice.opcode    := io.reqFSlice.bits.opcode
  reqFSlice.from      := io.reqFSlice.bits.from
  reqFSlice.txnId     := io.reqFSlice.bits.txnIdOpt.getOrElse(0.U)
  reqFSlice.srcId     := io.reqFSlice.bits.srcIdOpt.getOrElse(0.U)
  reqFSlice.retToSrc  := io.reqFSlice.bits.retToSrc
  reqFSlice.doNotGoToSD := io.reqFSlice.bits.doNotGoToSD

  /*
   * Receive chiTxReq(Read / Dataless / Atomic / CMO)
   */
  reqFTxReq.addr      := io.chi.txreq.bits.addr
  reqFTxReq.opcode    := io.chi.txreq.bits.opcode
  reqFSlice.txnId     := io.chi.txreq.bits.txnID
  reqFTxReq.srcId     := io.chi.txreq.bits.srcID

  /*
   * Save reqFSlice or reqFTxReq
   */
  reqReg := Mux(io.reqFSlice.fire, reqFSlice, Mux(io.chi.txreq.fire, reqFTxReq, reqReg))


// ---------------------------  Receive CHI Resp(TxRsp and TxDat) Logic --------------------------------//
  /*
   * Receive Snoop TxRsp
   */
  when(fsmReg.w_snpResp & (io.chi.txrsp.fire | io.chi.txdat.fire)) {
    val rsp = io.chi.txrsp
    val dat = io.chi.txdat

    snpRespReg        := Mux(rsp.fire, rsp.bits.resp, dat.bits.resp)
    snpRespHasDataReg := dat.fire
  }

  /*
   * Count data get from TxDat number
   */
  getTxDatNumReg := Mux(release, 0.U, getTxDatNumReg + io.chi.txdat.fire.asUInt)

// ---------------------------  Send CHI Req or Resp(RxSnp, RxRsp and RxDat) Logic --------------------------------//
  /*
   * Send RxSnp
   */
  io.chi.rxsnp.valid          := fsmReg.s_snp & !fsmReg.w_dbid
  io.chi.rxsnp.bits           := DontCare
  io.chi.rxsnp.bits.srcID     := rnSlvId.U
  io.chi.rxsnp.bits.txnID     := reqBufId.U
  io.chi.rxsnp.bits.fwdNID    := reqReg.srcId
  io.chi.rxsnp.bits.fwdTxnID  := reqReg.txnId
  io.chi.rxsnp.bits.addr      := reqReg.addr(addressBits - 1, addressBits - io.chi.rxsnp.bits.addr.getWidth)
  io.chi.rxsnp.bits.opcode    := reqReg.opcode
  io.chi.rxsnp.bits.retToSrc  := reqReg.retToSrc
  io.chi.rxsnp.bits.doNotGoToSD := reqReg.doNotGoToSD

  /*
   * Send RxRsp
   */
  val compVal               = fsmReg.s_resp & !fsmReg.w_mpResp & !fsmReg.w_dbData
  val dbdidRespVal          = fsmReg.s_dbidResp & !fsmReg.w_dbid
  io.chi.rxrsp.valid        := compVal | dbdidRespVal
  io.chi.rxrsp.bits         := DontCare
  io.chi.rxrsp.bits.opcode  := Mux(compVal, sliceRespReg.opcode, CHIOp.RSP.CompDBIDResp)
  io.chi.rxrsp.bits.tgtID   := reqReg.srcId
  io.chi.rxrsp.bits.srcID   := rnSlvId.U
  io.chi.rxrsp.bits.txnID   := reqReg.txnId
  io.chi.rxrsp.bits.dbID    := reqBufId.U
  io.chi.rxrsp.bits.resp    := Mux(compVal, sliceRespReg.resp, 0.U)
  io.chi.rxrsp.bits.pCrdType := 0.U // This system dont support Transaction Retry


  /*
   * Send RxDat
   */
  io.chi.rxdat.valid        := fsmReg.s_resp & fsmReg.w_dbData & io.dataFDBVal & !fsmReg.w_mpResp
  io.chi.rxdat.bits         := DontCare
  io.chi.rxdat.bits.opcode  := sliceRespReg.opcode
  io.chi.rxdat.bits.tgtID   := reqReg.srcId
  io.chi.rxdat.bits.srcID   := rnSlvId.U
  io.chi.rxdat.bits.txnID   := reqBufId.U
  io.chi.rxdat.bits.dbID    := reqReg.txnId
  io.chi.rxdat.bits.homeNID := rnSlvId.U
  io.chi.rxdat.bits.resp    := sliceRespReg.resp
  io.chi.rxdat.bits.dataID  := DontCare
  io.chi.rxdat.bits.data    := DontCare


// ---------------------------  Receive respFSlice / Send reqTSlice and respTSlice  --------------------------------//
  /*
   * Receive Resp From Slice
   */
  sliceRespReg := Mux(io.respFSlice.fire, io.respFSlice.bits, sliceRespReg)


  /*
   * Send Req To Slice
   */
  io.reqTSlice.valid            := fsmReg.s_req2mshr & !fsmReg.w_rnData
  io.reqTSlice.bits.opcode      := reqReg.opcode
  io.reqTSlice.bits.addr        := reqReg.addr
  io.reqTSlice.bits.willSnp     := !CHIOp.REQ.isWrite(reqReg.opcode)
  if (djparam.useDCT) io.reqTSlice.bits.srcIDOpt.get := reqReg.srcId
  if (djparam.useDCT) io.reqTSlice.bits.txnIDOpt.get := reqReg.txnId
  // IdMap
  io.reqTSlice.bits.to.idL0     := SLICE
  io.reqTSlice.bits.to.idL1     := DontCare // Remap in Xbar
  io.reqTSlice.bits.to.idL2     := DontCare
  io.reqTSlice.bits.from.idL0   := RNSLV
  io.reqTSlice.bits.from.idL1   := rnSlvId.U
  io.reqTSlice.bits.from.idL2   := reqBufId.U
  // Use in RnMaster
  io.reqTSlice.bits.retToSrc    := DontCare
  io.reqTSlice.bits.doNotGoToSD := DontCare




  /*
   * Send Resp To Slice
   * send update MSHR and send snoop resp also use respTSlice
   */
  io.respTSlice.valid           := (fsmReg.s_udpMSHR | fsmReg.s_snpResp) & PopCount(fsmReg.asUInt) === 1.U // only udpMSHR or snpResp need to do
  io.respTSlice.bits.resp       := snpRespReg
  io.respTSlice.bits.isSnpResp  := fsmReg.s_snpResp
  io.respTSlice.bits.dbid       := dbidReg
  io.respTSlice.bits.mshrSet    := parseMSHRAddress(reqReg.addr)._1
  io.respTSlice.bits.mshrWay    := sliceRespReg.mshrWay
  // IdMap
  io.respTSlice.bits.to.idL0    := SLICE
  io.respTSlice.bits.to.idL1    := DontCare // Remap in Xbar
  io.respTSlice.bits.to.idL2    := DontCare



// ---------------------------  DataBuffer Ctrl Signals  --------------------------------//
  /*
   * Send wReq to get dbid
   */
  io.wReq.valid           := fsmReg.s_getDBID
  // IdMap
  io.wReq.bits.to.idL0    := SLICE
  io.wReq.bits.to.idL1    := DontCare // Remap in Xbar
  io.wReq.bits.to.idL2    := DontCare
  io.wReq.bits.from.idL0  := RNSLV
  io.wReq.bits.from.idL1  := rnSlvId.U
  io.wReq.bits.from.idL2  := reqBufId.U

  /*
   * Receive dbid from wResp
   */
  dbidReg       := Mux(io.wResp.fire, io.wResp.bits.dbid, dbidReg)
  dbidBankIdReg := Mux(io.wResp.fire, io.wResp.bits.from.idL1, dbidBankIdReg)

  /*
   * Count data get from DataBuffer number
   */
  getDBNumReg   := Mux(release, 0.U, getDBNumReg + io.dataFDBVal.asUInt)



// ---------------------------  Other Signals  --------------------------------//
  /*
   * getAllData logic
   */
  getAllData  := getTxDatNumReg === nrBeat.U | (getTxDatNumReg === (nrBeat - 1).U & io.chi.txdat.fire) |
                 getDBNumReg === nrBeat.U    | (getDBNumReg === (nrBeat - 1).U & io.dataFDBVal)

  /*
   * Output reqBufDBID
   */
  io.reqBufDBID.valid       := fsmReg.w_rnData & !fsmReg.w_dbid
  io.reqBufDBID.bits.dbid   := dbidReg
  io.reqBufDBID.bits.bankId := dbidBankIdReg

  /*
   * Set io ready value
   */
  io.chi.txreq.ready  := true.B
  io.chi.txrsp.ready  := true.B
  io.chi.txdat.ready  := true.B
  io.reqFSlice.ready  := true.B
  io.respFSlice.ready := true.B
  io.wResp.ready      := true.B


// ---------------------------  Assertion  --------------------------------//
  // when it is free, it can receive or send mes
  assert(Mux(io.free, !io.chi.txrsp.valid, true.B))
  assert(Mux(io.free, !io.chi.txdat.valid, true.B))
  assert(Mux(io.free, !io.chi.rxdat.valid, true.B))
  assert(Mux(io.free, !io.chi.rxrsp.valid, true.B))
  assert(Mux(io.free, !io.chi.rxsnp.valid, true.B))
  assert(Mux(io.free, !io.reqTSlice.valid, true.B))
  assert(Mux(io.free, !io.respFSlice.valid, true.B))
  assert(Mux(io.free, !io.respTSlice.valid, true.B))
  assert(Mux(io.free, !io.reqBufDBID.valid, true.B))
  assert(Mux(io.free, !io.wReq.valid, true.B))
  assert(Mux(io.free, !io.wResp.valid, true.B))
  assert(Mux(io.free, !io.dataFDBVal, true.B))

  assert(Mux(!freeReg, !(io.chi.txreq.valid | io.reqFSlice.valid), true.B), "When ReqBuf valid, it cant input new req")
  assert(Mux(io.chi.txreq.valid | io.reqFSlice.valid, io.free, true.B), "Reqbuf cant block req input")
  assert(!(io.chi.txreq.valid & io.reqFSlice.valid), "Reqbuf cant receive txreq and snpTask at the same time")
  when(release) {
    assert(fsmReg.asUInt === 0.U, "when ReqBuf release, all task should be done")
  }
  assert(Mux(getDBNumReg === nrBeat.U, !io.dataFDBVal, true.B), "ReqBuf get data from DataBuf overflow")
  assert(Mux(io.dataFDBVal, fsmReg.s_resp & fsmReg.w_dbData, true.B), "When dbDataValid, ReqBuf should set s_resp and w_data")
  assert(Mux(io.dataFDBVal, !fsmReg.w_mpResp, true.B), "When dataFDBVal, ReqBuf should has been receive mpResp")

  assert(Mux(fsmReg.w_snpResp & io.chi.txrsp.fire, !io.chi.txrsp.bits.resp(2), true.B))

  val cntReg = RegInit(0.U(64.W))
  cntReg := Mux(io.free, 0.U, cntReg + 1.U)
  assert(cntReg < TIMEOUT_RB.U, "REQBUF[0x%x] ADDR[0x%x] OP[0x%x] SNP[0x%x] TIMEOUT", reqBufId.U, reqReg.addr, reqReg.opcode, reqReg.from.isSLICE.asUInt)




}