package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import org.chipsalliance.cde.config._
import chisel3.util.{Decoupled, ValidIO}

class ReqBuf()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val free = Output(Bool())
    val reqBufId = Input(UInt(reqBufIdBits.W))
    // CHI
    val chi = Flipped(CHIBundleDecoupled(chiBundleParams))
    // mainpipe and snpCtrl
    val snpTask = Flipped(ValidIO(new TaskBundle()))
    val snpResp = Decoupled(new TaskRespBundle())
    val mptask = Decoupled(new TaskBundle())
    val mpResp = Flipped(ValidIO(new TaskRespBundle()))
    // dataBuffer
    val dbReq = Decoupled(new DBReq())
    val dbResp = Flipped(ValidIO(new DBResp()))
    val dbDataValid = Input(Bool())
  })

  // TODO: Delete the following code when the coding is complete
  io.free := false.B
  io.chi := DontCare
  io.snpTask := DontCare
  io.snpResp := DontCare
  io.mptask := DontCare
  io.mpResp := DontCare
  io.dbReq := DontCare
  io.dbResp := DontCare
  dontTouch(io)

}