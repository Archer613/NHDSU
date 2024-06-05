package NHDSU

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import chi._
import xs.utils._
import Utils.FastArb._


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
  idle1(io.out0) := false.B
  io.out1 := ParallelPriorityMux(idle1.zipWithIndex.map {
    case (b, i) => (b, (1 << i).U)
  })
}



class CpuSlave()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // CHI
    val chi = CHIBundleUpstream(chiBundleParams)
    val chiLinkCtrl = Flipped(new CHILinkCtrlIO())
    // mainpipe and snpCtrl
    val snpTask = Vec(dsuparam.nrBank, Flipped(Decoupled(new TaskBundle())))
    val snpResp = Vec(dsuparam.nrBank, Decoupled(new TaskRespBundle()))
    val mptask = Vec(dsuparam.nrBank, Decoupled(new TaskBundle()))
    val mpResp = Vec(dsuparam.nrBank, Flipped(Decoupled(new TaskRespBundle())))
    // dataBuffer
    val dbCrtl = Vec(dsuparam.nrBank, new DBCtrlBundle())
  })

  // TODO: Delete the following code when the coding is complete
  io.chi <> DontCare
  io.chiLinkCtrl <> DontCare
  io.snpTask <> DontCare
  io.snpResp <> DontCare
  io.mptask <> DontCare
  io.mpResp <> DontCare
  io.dbCrtl <> DontCare
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

  if (dsuparam.nrBank == 1) {
    // mpTask ---[fastArb]---> mainPipe
    fastArbDec(reqBufs.map(_.io.mptask), io.mptask(0), Some("mainPipeArb"))

    // snpResp ---[fastArb]---> snpCtrl
    fastArbDec(reqBufs.map(_.io.snpResp), io.snpResp(0), Some("snpRespArb"))

    // dbReq ---[fastArb]---> dataBuf
    fastArbVal(reqBufs.map(_.io.dbReq), io.dbCrtl(0).req, Some("dbReqArb"))

    // TODO: Connect chi <-> reqBufs
    reqBufs.foreach(_.io.chi <> DontCare)

    // snpTask(Priority)  ---> |----------------| ---> reqBuf(N)
    //                         | ReqBufSelector |
    // txReq              ---> |----------------| ---> reqBuf(N+X)
    val reqBufSel = Module(new ReqBufSelector())
    reqBufSel.io.idle := reqBufs.map(_.io.free)
    io.snpTask(0).ready := reqBufSel.io.idleNum > 0.U
    txReq.io.flit.ready := reqBufSel.io.idleNum > 1.U // The last reqBuf is reserved for snpTask
    when(io.snpTask(0).valid){
      snpSelId := reqBufSel.io.out0
      txReqSelId := reqBufSel.io.out1
    }.otherwise{
      snpSelId := 0.U
      txReqSelId := reqBufSel.io.out0
    }

    reqBufs.zipWithIndex.foreach {
      case (reqbuf, i) =>
        reqbuf.io.reqBufId := i.U
        // snpTask  ---snpSelId---> reqBuf(N)
        reqbuf.io.snpTask.valid := io.snpTask(0).valid & snpSelId === i.U
        reqbuf.io.snpTask.bits := io.snpTask(0).bits
        // txReq    ---txReqSelId---> reqBuf(N+X)
        reqbuf.io.chi.txreq.valid := txReq.io.flit.fire & txReqSelId === i.U
        reqbuf.io.chi.txreq.bits := txReq.io.flit.bits
        // mpResp --(sel by mpResp.id.l2)--> reqBuf
        reqbuf.io.mpResp.valid := io.mpResp(0).valid & io.mpResp(0).bits.id.l2 === i.U
        reqbuf.io.mpResp.bits := io.mpResp(0).bits
        // dbResp --(sel by mpResp.id.l2)--> reqBuf
        reqbuf.io.dbResp.valid := io.dbCrtl(0).wResp.valid & io.dbCrtl(0).wResp.bits.id.l2 === i.U
        reqbuf.io.dbResp.bits := io.dbCrtl(0).wResp.bits
        // dataFromDB --(sel by dataFromDB.bits.id.l2)--> dbDataValid
        reqbuf.io.dbDataValid := io.dbCrtl(0).dataFromDB.valid & io.dbCrtl(0).dataFromDB.bits.id.l2 === i.U
    }




  } else {
    //
    // TODO: multi-bank
    //
    assert(false.B, "Now dont support multi-bank")
  }



}