package NHDSU

import chi._
import chisel3._
import chisel3.util.{Decoupled, ValidIO}
import org.chipsalliance.cde.config._

class CpuChiTxDat()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = Flipped(CHIChannelIO(new CHIBundleDAT(chiBundleParams)))
    val txState = Input(new LinkState())
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val flit = Decoupled(new CHIBundleDAT(chiBundleParams))
    val toDB = ValidIO(new Bundle {
      val id = UInt(dbIdBits.W)
      val data = UInt(beatBits.W)
    })
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.allLcrdRetrun := DontCare
  io.flit := DontCare
  io.toDB := DontCare
  dontTouch(io)

}