package NHDSU.DSUMASTER

import NHDSU._
import _root_.NHDSU.CHI.{CHIBundleDownstream, CHILinkCtrlIO}
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
    val mpTask        = Flipped(Decoupled(new TaskBundle()))
    val mpResp        = Decoupled(new TaskRespBundle())
    // dataBuffer
    val dbSigs        = new Bundle {
      val req           = ValidIO(new DBReq())
      val wResp         = Flipped(ValidIO(new DBResp()))
      val dataFromDB    = Flipped(Decoupled(new DBOutData()))
      val dataToDB      = ValidIO(new DBInData())
    }
  })

  // TODO: Delete the following code when the coding is complete
  io.chi <> DontCare
  io.chiLinkCtrl <> DontCare
  io.mpTask <> DontCare
  io.mpResp <> DontCare
  io.dbSigs <> DontCare
  dontTouch(io)

// --------------------- Modules declaration ------------------------//



// --------------------- Connection ------------------------//

}