package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO}
import org.chipsalliance.cde.config._

class CpuChiRxDat()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleDAT(chiBundleParams))
    val rxState = Input(UInt(LinkStates.width.W))
    val flit = Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))
    val dataFDB = Flipped(Decoupled(new DBOutData()))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.flit := DontCare
  io.dataFDB := DontCare
  dontTouch(io)

}