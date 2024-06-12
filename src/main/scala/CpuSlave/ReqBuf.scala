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
    // mainpipe
    val mpTask = Decoupled(new TaskBundle())
    val mpResp = Flipped(ValidIO(new TaskRespBundle()))
    // snpCtrl
    val snpTask = Flipped(Decoupled(new TaskBundle()))
    val snpResp = Decoupled(new TaskRespBundle())
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
  io.mpTask := DontCare
  io.mpResp := DontCare
  io.dbReq := DontCare
  io.dbResp := DontCare
  dontTouch(io)

  // Whether the io.chi.txreq and io.snpTask can be input is determined by io.free in ReqBufSel
  // So DontCare the following signals
  io.chi.txreq.ready := true.B
  io.snpTask.ready := true.B

}