package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO}
import org.chipsalliance.cde.config._

class CpuChiTxDat()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = Flipped(CHIChannelIO(new CHIBundleDAT(chiBundleParams)))
    val txState = Input(UInt(LinkStates.width.W))
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val flit = Decoupled(new CHIBundleDAT(chiBundleParams))
    val toDB = Decoupled(new DBInData())
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.allLcrdRetrun := DontCare
  io.flit := DontCare
  io.toDB := DontCare
  dontTouch(io)

}