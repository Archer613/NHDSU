package DONGJIANG.RNSLAVE

import DONGJIANG._
import DONGJIANG.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils._
import Utils.FastArb._
import Utils.IDConnector._

class RnSlave(rnSlvId: Int)(implicit p: Parameters) extends DJModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // CHI
    val chi           = CHIBundleUpstream(chiParams)
    val chiLinkCtrl   = Flipped(new CHILinkCtrlIO())
    // slice ctrl signals
    val reqTSlice     = Decoupled(new RnReqOutBundle())
    val respFSlice    = Flipped(Decoupled(new RnRespInBundle()))
    val reqFSlice     = Decoupled(new RnReqInBundle())
    val respTSlice    = Flipped(Decoupled(new RnRespOutBundle()))
    // slice DataBuffer signals
    val dbSigs        = new RnDBBundle()
  })

  val nodeParam = djparam.rnNodeMes(rnSlvId)

// --------------------- Modules declaration ------------------------//
  val chiCtrl = Module(new InboundLinkCtrl())

  val txReq   = Module(new InboundFlitCtrl(gen = new CHIBundleREQ(chiParams), lcrdMax = nodeParam.nrRnTxLcrdMax, nodeParam.aggregateIO))
  val txRsp   = Module(new InboundFlitCtrl(gen = new CHIBundleRSP(chiParams), lcrdMax = nodeParam.nrRnTxLcrdMax, nodeParam.aggregateIO))
  val rxSnp   = Module(new OutboundFlitCtrl(gen = new CHIBundleSNP(chiParams), lcrdMax = nodeParam.nrRnRxLcrdMax, nodeParam.aggregateIO))
  val rxRsp   = Module(new OutboundFlitCtrl(gen = new CHIBundleRSP(chiParams), lcrdMax = nodeParam.nrRnRxLcrdMax, nodeParam.aggregateIO))

  val txDat   = Module(new ChiTxDat(rnSlvId))
  val rxDat   = Module(new ChiRxDat(rnSlvId))

  val reqBuf  = Module(new ReqBufWrapper(rnSlvId))

// ------------------------ Connection ---------------------------//
  chiCtrl.io.chiLinkCtrl <> io.chiLinkCtrl
  chiCtrl.io.rxRun := true.B // TODO
  chiCtrl.io.txAllLcrdRetrun := txReq.io.allLcrdRetrun | txRsp.io.allLcrdRetrun | txDat.io.allLcrdRetrun

  txReq.io.txState := chiCtrl.io.txState
  txReq.io.chi <> io.chi.txreq
  txReq.io.flit <> reqBuf.io.chi.txreq

  txRsp.io.txState := chiCtrl.io.txState
  txRsp.io.chi <> io.chi.txrsp
  txReq.io.flit <> reqBuf.io.chi.txrsp

  txDat.io.txState := chiCtrl.io.txState
  txDat.io.chi <> io.chi.txdat
  txDat.io.flit <> reqBuf.io.chi.txdat
  txDat.io.dataTDB <> io.dbSigs.dataTDB
  txDat.io.reqBufDBIDVec <> reqBuf.io.reqBufDBIDVec

  rxSnp.io.rxState := chiCtrl.io.rxState
  rxSnp.io.chi <> io.chi.rxsnp
  rxSnp.io.flit <> reqBuf.io.chi.rxsnp

  rxRsp.io.rxState := chiCtrl.io.rxState
  rxRsp.io.chi <> io.chi.rxrsp
  rxRsp.io.flit <> reqBuf.io.chi.rxrsp

  rxDat.io.rxState := chiCtrl.io.rxState
  rxDat.io.chi <> io.chi.txdat
  rxDat.io.flit <> reqBuf.io.chi.txdat
  rxDat.io.dataFDB <> io.dbSigs.dataFDB
  rxDat.io.reqBufDBIDVec <> reqBuf.io.reqBufDBIDVec
  rxDat.io.dataFDBVal <> reqBuf.io.dataFDBVal

  reqBuf.io.reqTSlice <> io.reqTSlice
  reqBuf.io.respFSlice <> io.respFSlice
  reqBuf.io.reqFSlice <> io.reqFSlice
  reqBuf.io.respTSlice <> io.respTSlice
  reqBuf.io.wReq <> io.dbSigs.wReq
  reqBuf.io.wResp <> io.dbSigs.wResp
}