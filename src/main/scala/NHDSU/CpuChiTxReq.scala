package NHDSU

import chi._
import chisel3._
import chisel3.util.Decoupled
import org.chipsalliance.cde.config._

class CpuChiTxReq()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = Flipped(CHIChannelIO(new CHIBundleREQ(chiBundleParams)))
    val txState = Input(new LinkState())
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val flit = Decoupled(new CHIBundleREQ(chiBundleParams))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.allLcrdRetrun := DontCare
  io.flit := DontCare
  dontTouch(io)

}