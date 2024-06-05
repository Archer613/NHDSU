package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.Decoupled
import org.chipsalliance.cde.config._

class CpuChiRxSnp()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleSNP(chiBundleParams))
    val rxState = Input(new LinkState())
    val flit = Flipped(Decoupled(new CHIBundleSNP(chiBundleParams)))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.flit := DontCare
  dontTouch(io)

}