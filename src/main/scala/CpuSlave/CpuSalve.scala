package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils._
import Utils.FastArb._
import Utils.IDConnector._


class ReqBufSelector(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle() {
    val idle = Input(Vec(dsuparam.nrReqBuf, Bool()))
    val idleNum = Output(UInt((reqBufIdBits+1).W))
    val out0 = UInt(reqBufIdBits.W)
    val out1 = UInt(reqBufIdBits.W)
  })
  io.idleNum := PopCount(io.idle)
  io.out0 := PriorityEncoder(io.idle)
  val idle1 = WireInit(io.idle)
  idle1(io.out0) := false.B
  io.out1 := PriorityEncoder(idle1)
}



class CpuSlave()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val cpuSlvId      = Input(UInt(coreIdBits.W))
    // CHI
    val chi           = CHIBundleUpstream(chiBundleParams)
    val chiLinkCtrl   = Flipped(new CHILinkCtrlIO())
    // snpCtrl
    val snpTask       = Flipped(Decoupled(new TaskBundle()))
    val snpResp       = Decoupled(new RespBundle())
    // mainpipe
    val mpTask        = Decoupled(new TaskBundle())
    val mpResp        = Flipped(ValidIO(new RespBundle()))
    // dataBuffer
    val dbSigs        = new CpuDBBundle()
  })


// --------------------- Modules declaration ------------------------//
  val chiCtrl = Module(new ProtocolLayerCtrl())
  val txReq = Module(new CpuChiTxReq())
  val txRsp = Module(new CpuChiTxRsp())
  val txDat = Module(new CpuChiTxDat())
  val rxSnp = Module(new CpuChiRxSnp())
  val rxRsp = Module(new CpuChiRxRsp())
  val rxDat = Module(new CpuChiRxDat())

  val reqBufs = Seq.fill(dsuparam.nrReqBuf) { Module(new ReqBuf()) }

// --------------------- Wire declaration ------------------------//
  val snpSelId = Wire(UInt(reqBufIdBits.W))
  val txReqSelId = Wire(UInt(reqBufIdBits.W))

// --------------------- Connection ------------------------//
  /*
   * connect chiXXX <-> reqBufs <-> io.xxx(signals from or to slice)
   */
  // TODO: Connect chi <-> reqBufs
  reqBufs.foreach(_.io.chi <> DontCare)
  /*
   * connect io.chi <-> chiXXX <-> dataBuffer
   */
  chiCtrl.io.chiLinkCtrl <> io.chiLinkCtrl
  chiCtrl.io.txAllLcrdRetrun := txReq.io.allLcrdRetrun & txRsp.io.allLcrdRetrun & txDat.io.allLcrdRetrun
  chiCtrl.io.reqBufsVal := reqBufs.map(!_.io.free).reduce(_|_)

  txReq.io.chi <> io.chi.txreq
  txReq.io.txState := chiCtrl.io.txState

  txRsp.io.chi <> io.chi.txrsp
  txRsp.io.txState := chiCtrl.io.txState
  reqBufs.map(_.io.chi.txrsp).zipWithIndex.foreach {
    case(txrsp, i) =>
      txrsp.valid := txRsp.io.flit.valid & txRsp.io.flit.bits.txnID === i.U
      txrsp.bits := txRsp.io.flit.bits
  }
  txRsp.io.flit.ready := true.B

  txDat.io.chi <> io.chi.txdat
  txDat.io.txState := chiCtrl.io.txState
  txDat.io.dataTDB <> io.dbSigs.dataTDB
  reqBufs.map(_.io.chi.txdat).zipWithIndex.foreach {
    case (txdat, i) =>
      txdat.valid := txDat.io.flit.valid & txDat.io.flit.bits.txnID === reqBufs(i).io.txDatId & !reqBufs(i).io.free
      txdat.bits := txDat.io.flit.bits
  }
  txDat.io.flit.ready := true.B

  io.chi.rxsnp <> rxSnp.io.chi
  rxSnp.io.rxState := chiCtrl.io.rxState
  fastArbDec2Dec(reqBufs.map(_.io.chi.rxsnp), rxSnp.io.flit)

  io.chi.rxrsp <> rxRsp.io.chi
  rxRsp.io.rxState := chiCtrl.io.rxState
  fastArbDec2Dec(reqBufs.map(_.io.chi.rxrsp), rxRsp.io.flit)

  io.chi.rxdat <> rxDat.io.chi
  rxDat.io.rxState := chiCtrl.io.rxState
  rxDat.io.dataFDB <> io.dbSigs.dataFDB
  fastArbDec2Dec(reqBufs.map(_.io.chi.rxdat), rxDat.io.flit)


  // snpTask(Priority)  ---> |----------------| ---> reqBuf(N)
  //                         | ReqBufSelector |
  // txReq              ---> |----------------| ---> reqBuf(N+X)
  val reqBufSel = Module(new ReqBufSelector())
  reqBufSel.io.idle := reqBufs.map(_.io.free)
  io.snpTask.ready := reqBufSel.io.idleNum > 0.U
  txReq.io.flit.ready := reqBufSel.io.idleNum > 1.U // The last reqBuf is reserved for snpTask
  when(io.snpTask.valid){
    snpSelId := reqBufSel.io.out0
    txReqSelId := reqBufSel.io.out1
  }.otherwise{
    snpSelId := DontCare
    txReqSelId := reqBufSel.io.out0
  }

  // ReqBuf input:
  reqBufs.zipWithIndex.foreach {
    case (reqbuf, i) =>
      reqbuf.io.cpuSlvId := io.cpuSlvId
      reqbuf.io.reqBufId := i.U
      // snpTask  ---snpSelId---> reqBuf(N)
      reqbuf.io.snpTask.valid := io.snpTask.fire & snpSelId === i.U
      reqbuf.io.snpTask.bits := io.snpTask.bits
      // txReq    ---txReqSelId---> reqBuf(N+X)
      reqbuf.io.chi.txreq.valid := txReq.io.flit.fire & txReqSelId === i.U
      reqbuf.io.chi.txreq.bits := txReq.io.flit.bits
  }
  // mpResp --(sel by mpResp.id.l2)--> reqBuf
  idSelVal2ValVec(io.mpResp, reqBufs.map(_.io.mpResp), level = 2)
  // dbResp --(sel by mpResp.id.l2)--> reqBuf
  idSelDec2DecVec(io.dbSigs.wResp, reqBufs.map(_.io.wResp), level = 2)


  // ReqBuf output:
  // mpTask ---[fastArb]---> mainPipe
  fastArbDec2Dec(reqBufs.map(_.io.mpTask), io.mpTask, Some("mainPipeArb"))
  // snpResp ---[fastArb]---> snpCtrl
  fastArbDec2Dec(reqBufs.map(_.io.snpResp), io.snpResp, Some("snpRespArb"))
  // dbReq ---[fastArb]---> dataBuffer
  fastArbDec2Dec(reqBufs.map(_.io.wReq), io.dbSigs.wReq, Some("dbWReqArb"))
  // dataFromDB --(sel by dataFromDB.bits.id.l2)--> dbDataValid
  reqBufs.zipWithIndex.foreach {
    case (reqbuf, i) =>
      reqbuf.io.dbDataValid := io.dbSigs.dataFDB.valid & io.dbSigs.dataFDB.bits.to.idL2 === i.U
  }

// --------------------- Assertion ------------------------------- //
  assert(PopCount(reqBufs.map(_.io.chi.txdat.fire)) <= 1.U, "txDat only can be send to one reqBuf")


}