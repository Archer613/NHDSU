package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.Decoupled
import org.chipsalliance.cde.config._

class DsuChiTxReqBundle(implicit p: Parameters) extends DSUBundle {
  // TODO: TaskRespBundle
  val opcode      = UInt(5.W)
  val addr        = UInt(addressBits.W)
  val txnid       = UInt(8.W)
}

class DsuChiTxReq()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleREQ(chiBundleParams))
    val txState = Input(UInt(LinkStates.width.W))
    val task = Flipped(Decoupled(new DsuChiTxReqBundle()))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.task := DontCare
  dontTouch(io)


}