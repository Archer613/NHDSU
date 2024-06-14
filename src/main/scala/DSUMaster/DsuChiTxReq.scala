package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.Decoupled
import org.chipsalliance.cde.config._

class DsuChiTxReq()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleREQ(chiBundleParams))
    val txState = Input(new LinkState())
    val task = Flipped(Decoupled(new TaskBundle()))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.task := DontCare
  dontTouch(io)


}