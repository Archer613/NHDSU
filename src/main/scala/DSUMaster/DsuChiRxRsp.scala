package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO}
import org.chipsalliance.cde.config._

class DsuChiRxRsp()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = Flipped(CHIChannelIO(new CHIBundleRSP(chiBundleParams)))
    val rxState = Input(new LinkState())
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val resp = ValidIO(new CHIBundleRSP(chiBundleParams))
    val dbidResp = ValidIO(new Bundle {
      val dbidCHI = Input(UInt(12.W))
      val dbidSlice = Input(UInt(reqBufIdBits.W))
    })
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.allLcrdRetrun := DontCare
  io.resp := DontCare
  io.dbidResp := DontCare
  dontTouch(io)


}