package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO}
import org.chipsalliance.cde.config._

class ReadCtl()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val mpTask = Flipped(Decoupled(new TaskBundle()))
    val mpResp = Decoupled(new TaskBundle())
    val rReq = Decoupled(new TaskBundle())
    val rxRspResp = Flipped(ValidIO(new CHIBundleRSP(chiBundleParams)))
    val rxDatResp = Flipped(ValidIO(new CHIBundleDAT(chiBundleParams)))
    val dbWrite = Decoupled(new DBReq())
    val dbResp = Flipped(ValidIO(new DBResp()))
  })

  // TODO: Delete the following code when the coding is complete
  io.mpTask := DontCare
  io.mpResp := DontCare
  io.rReq := DontCare
  io.rxRspResp := DontCare
  io.rxDatResp := DontCare
  io.dbWrite := DontCare
  io.dbResp := DontCare
  dontTouch(io)


}