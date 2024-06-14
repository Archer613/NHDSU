package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI.{CHILinkCtrlIO, LinkState}
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class ProtocolLayerCtrl()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val chiLinkCtrl = new CHILinkCtrlIO()
    val txState = Output(new LinkState())
    val rxState = Output(new LinkState())
    val rxAllLcrdRetrun = Input(Bool())
  })

  // TODO: Delete the following code when the coding is complete
  io.chiLinkCtrl := DontCare
  io.txState := DontCare
  io.rxState := DontCare
  dontTouch(io)

// --------------------- Modules declaration ------------------------//



// --------------------- Connection ------------------------//

}