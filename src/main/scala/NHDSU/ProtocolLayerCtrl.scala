package NHDSU

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import chi._

class ProtocolLayerCtrl()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chiLinkCtrl = Flipped(new CHILinkCtrlIO())
    val txState = Output(new LinkState())
    val rxState = Output(new LinkState())
    val txAllLcrdRetrun = Input(Bool())
  })

  // TODO: Delete the following code when the coding is complete
  io.chiLinkCtrl := DontCare
  io.txState := DontCare
  io.rxState := DontCare
  dontTouch(io)


}