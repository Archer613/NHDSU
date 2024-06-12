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
    val idleNum = Output(UInt(reqBufIdBits.W))
    val out0 = UInt(dsuparam.nrReqBuf.W)
    val out1 = UInt(dsuparam.nrReqBuf.W)
  })
  io.idleNum := PopCount(io.idle)
  io.out0 := ParallelPriorityMux(io.idle.zipWithIndex.map {
    case (b, i) => (b, (1 << i).U)
  })
  val idle1 = WireInit(io.idle)
  idle1(OHToUInt(io.out0.asUInt)) := false.B
  io.out1 := ParallelPriorityMux(idle1.zipWithIndex.map {
    case (b, i) => (b, (1 << i).U)
  })
}



class CpuSlave()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // CHI
    val chi           = CHIBundleUpstream(chiBundleParams)
    val chiLinkCtrl   = Flipped(new CHILinkCtrlIO())
    // snpCtrl
    val snpTask       = Flipped(Decoupled(new TaskBundle()))
    val snpResp       = Decoupled(new TaskRespBundle())
    // mainpipe
    val mpTask        = Decoupled(new TaskBundle())
    val mpResp        = Flipped(ValidIO(new TaskRespBundle()))
    // dataBuffer
    val dbSigs      = new Bundle {
      val req           = Decoupled(new DBReq())
      val wResp         = Flipped(ValidIO(new DBResp()))
      val dataFromDB    = Flipped(ValidIO(new DBOutData()))
      val dataToDB      = Decoupled(new DBInData())
    }
  })

  // TODO: Delete the following code when the coding is complete
  io.chi <> DontCare
  io.chiLinkCtrl <> DontCare
  io.snpTask <> DontCare
  io.snpResp <> DontCare
  io.mpTask <> DontCare
  io.mpResp <> DontCare
  io.dbSigs <> DontCare
  dontTouch(io)


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
   * connect io.chi <-> chiXXX <-> dataBuffer
   */
  chiCtrl.io.chiLinkCtrl <> io.chiLinkCtrl
  chiCtrl.io.txAllLcrdRetrun := txReq.io.allLcrdRetrun & txRsp.io.allLcrdRetrun & txDat.io.allLcrdRetrun

  txReq.io.chi <> io.chi.txreq
  txReq.io.txState := chiCtrl.io.txState
  txReq.io.flit <> DontCare

  txRsp.io.chi <> io.chi.txrsp
  txRsp.io.txState := chiCtrl.io.txState
  txRsp.io.flit <> DontCare

  txDat.io.chi <> io.chi.txdat
  txDat.io.txState := chiCtrl.io.txState
  txDat.io.flit <> DontCare
  txDat.io.toDB <> DontCare

  io.chi.rxsnp <> rxSnp.io.chi
  rxSnp.io.rxState := chiCtrl.io.rxState
  rxSnp.io.flit <> DontCare

  io.chi.rxrsp <> rxRsp.io.chi
  rxRsp.io.rxState := chiCtrl.io.rxState
  rxRsp.io.flit <> DontCare

  io.chi.rxdat <> rxDat.io.chi
  rxDat.io.rxState := chiCtrl.io.rxState
  rxDat.io.flit <> DontCare
  rxDat.io.fromDB <> DontCare

  /*
   * connect chiXXX <-> reqBufs <-> io.xxx(signals from or to slice)
   */
  // TODO: Connect chi <-> reqBufs
  reqBufs.foreach(_.io.chi <> DontCare)


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
    snpSelId := 0.U
    txReqSelId := reqBufSel.io.out0
  }

  // ReqBuf input:
  reqBufs.zipWithIndex.foreach {
    case (reqbuf, i) =>
      reqbuf.io.reqBufId := i.U
      // snpTask  ---snpSelId---> reqBuf(N)
      reqbuf.io.snpTask.valid := io.snpTask.fire & txReqSelId === i.U
      reqbuf.io.snpTask.bits := io.snpTask.bits
      // txReq    ---txReqSelId---> reqBuf(N+X)
      reqbuf.io.chi.txreq.valid := txReq.io.flit.fire & txReqSelId === i.U
      reqbuf.io.chi.txreq.bits := txReq.io.flit.bits
  }
  // mpResp --(sel by mpResp.id.l2)--> reqBuf
  idSelVal2ValVec(io.mpResp, reqBufs.map(_.io.mpResp), level = 2)
  // dbResp --(sel by mpResp.id.l2)--> reqBuf
  idSelVal2ValVec(io.dbSigs.wResp, reqBufs.map(_.io.dbResp), level = 2)


  // ReqBuf output:
  // mpTask ---[fastArb]---> mainPipe
  fastArbDec2Dec(reqBufs.map(_.io.mpTask), io.mpTask, Some("mainPipeArb"))
  // snpResp ---[fastArb]---> snpCtrl
  fastArbDec2Dec(reqBufs.map(_.io.snpResp), io.snpResp, Some("snpRespArb"))
  // dbReq ---[fastArb]---> dataBuffer
  fastArbDec2Dec(reqBufs.map(_.io.dbReq), io.dbSigs.req, Some("dbReqArb"))
  // dataFromDB --(sel by dataFromDB.bits.id.l2)--> dbDataValid
  reqBufs.zipWithIndex.foreach {
    case (reqbuf, i) =>
      reqbuf.io.dbDataValid := io.dbSigs.dataFromDB.valid & io.dbSigs.dataFromDB.bits.idL2 === i.U
  }



}