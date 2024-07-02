package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI.{CHIBundleDownstream, CHILinkCtrlIO}
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class DSUMaster()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // CHI
    val chi           = CHIBundleDownstream(chiBundleParams)
    val chiLinkCtrl   = new CHILinkCtrlIO()
    // mainpipe
    val mpTask        = Flipped(Decoupled(new TaskBundle())) // Consider splitting the Bundle into rReq and wbReq
    val mpResp        = Decoupled(new TaskBundle())
    // dataBuffer
    val dbSigs        = new DBBundle()
  })

  // TODO: Delete the following code when the coding is complete
  io.chi <> DontCare

// --------------------- Modules declaration ------------------------//
  val chiCtrl = Module(new ProtocolLayerCtrl())
  val rxDat = Module(new DsuChiRxDat())
  val rxRsp = Module(new DsuChiRxRsp())
  val txDat = Module(new DsuChiTxDat())
  val txReq = Module(new DsuChiTxReq())
  val readCtl = Module(new ReadCtl())


// --------------------- Wire declaration ------------------------//
  val wbReq = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle())))
  val rReq = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle())))

  dontTouch(wbReq)
  dontTouch(rReq)

// --------------------- Connection ------------------------//
  /*
   * connect io.chi <-> chiXXX <-> dataBuffer/readCtl
   */
  chiCtrl.io.chiLinkCtrl <> io.chiLinkCtrl
  chiCtrl.io.rxAllLcrdRetrun := rxDat.io.allLcrdRetrun & rxRsp.io.allLcrdRetrun

  rxDat.io.chi <> io.chi.rxdat
  rxDat.io.rxState := chiCtrl.io.rxState
  rxDat.io.resp <> readCtl.io.rxDatResp
  rxDat.io.dataTDB <> io.dbSigs.dataTDB

  rxRsp.io.chi <> io.chi.rxrsp
  rxRsp.io.rxState := chiCtrl.io.rxState
  rxRsp.io.resp <> readCtl.io.rxRspResp
  rxRsp.io.dbidResp <> txDat.io.dbidResp

  txDat.io.chi <> io.chi.txdat
  txDat.io.txState := chiCtrl.io.txState
  txDat.io.dbRCReq <> io.dbSigs.rcReq
  txDat.io.dataFDB <> io.dbSigs.dataFDB

  txReq.io.chi <> io.chi.txreq
  txReq.io.txState := chiCtrl.io.txState
  // txReq.io.task: priority is wbReq
  txReq.io.task.valid := wbReq.valid | readCtl.io.rReq.valid
  txReq.io.task.bits := Mux(wbReq.valid, wbReq.bits, readCtl.io.rReq.bits)
  wbReq.ready := txReq.io.task.ready
  readCtl.io.rReq.ready := txReq.io.task.ready & !wbReq.valid

  // readCtl
  readCtl.io.mpTask <> rReq
  readCtl.io.mpResp <> io.mpResp
  readCtl.io.dbWReq <> io.dbSigs.wReq
  readCtl.io.dbResp <> io.dbSigs.wResp


  // req sel by io.mpTask.bits.isWB
  wbReq.valid := io.mpTask.valid & io.mpTask.bits.isWB
  rReq.valid := io.mpTask.valid & !io.mpTask.bits.isWB
  wbReq.bits := io.mpTask.bits
  rReq.bits := io.mpTask.bits
  io.mpTask.ready := Mux(io.mpTask.bits.isWB, wbReq.ready, rReq.ready)



}