package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU._
import NHDSU.CHI._
import NHDSU.CHI.CHIOp._


class NHSN(implicit p : Parameters) extends DSUModule {
// ---------------------------- IO declaration -------------------------------//
  val io = IO(new Bundle {
    val snChi              = Vec(dsuparam.nrBank, CHIBundleUpstream(chiBundleParams))
    val snChiLinkCtrl      = Vec(dsuparam.nrBank, Flipped(new CHILinkCtrlIO()))
    })

// ---------------------------- Module instantiation --------------------------//

  val rxReqChan            = Seq.fill(dsuparam.nrBank) { Module(new RxChan(new CHIBundleREQ(chiBundleParams))) }
  val rxDatChan            = Seq.fill(dsuparam.nrBank) { Module(new RxChan(new CHIBundleDAT(chiBundleParams))) }
  val rxRspChan            = Seq.fill(dsuparam.nrBank) { Module(new RxChan(new CHIBundleRSP(chiBundleParams))) }

  val txReqChan            = Seq.fill(dsuparam.nrBank) { Module(new TxChan(new CHIBundleREQ(chiBundleParams))) }
  val txDatChan            = Seq.fill(dsuparam.nrBank) { Module(new TxChan(new CHIBundleDAT(chiBundleParams))) }

  val interface            = Seq.fill(dsuparam.nrBank) { Module(new Interface())}


// ----------------------------- Module Connection ---------------------------//
  
  
}
