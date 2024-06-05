package NHDSU

import chi._
import chisel3._
import chisel3.util.{Decoupled, ValidIO}
import org.chipsalliance.cde.config._

class CpuChiRxDat()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleDAT(chiBundleParams))
    val rxState = Input(new LinkState())
    val flit = Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))
    val fromDB = Flipped(ValidIO(new Bundle {
      val id = UInt(dbIdBits.W)
      val data = UInt(beatBits.W)
    }))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.flit := DontCare
  dontTouch(io)

}