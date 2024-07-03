package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO}
import org.chipsalliance.cde.config._

class DsuChiTxDat()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleDAT(chiBundleParams))
    val dbidResp = Flipped(ValidIO(new Bundle {
      val dbidCHI = Input(UInt(12.W))
      val dbidSlice = Input(UInt(reqBufIdBits.W))
    }))
    val txState = Input(UInt(LinkStates.width.W))
    val dbRCReq  = Decoupled(new DBRCReq())
    val dataFDB = Flipped(Decoupled(new DBOutData()))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.txState := DontCare
  io.dbRCReq := DontCare
  io.dataFDB := DontCare
  dontTouch(io)


}