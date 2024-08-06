package DONGJIANG.RNMASTER

import DONGJIANG._
import DONGJIANG.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils._
import Utils.FastArb._
import Utils.IDConnector._

class RnMaster(rnMasId: Int)(implicit p: Parameters) extends RnNodeBase {
  val nodeParam = djparam.rnNodeMes(rnMasId)

// --------------------- IO declaration ------------------------//
  val chiIO = IO(new Bundle {
    // CHI
    val chnls      = CHIBundleDownstream(chiParams)
    val linkCtrl   = new CHILinkCtrlIO()
  })

  io <> DontCare

}