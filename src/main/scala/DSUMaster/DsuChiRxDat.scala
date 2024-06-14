package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO}
import org.chipsalliance.cde.config._

class DsuChiRxDat()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = Flipped(CHIChannelIO(new CHIBundleDAT(chiBundleParams)))
    val rxState = Input(new LinkState())
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val resp = ValidIO(new CHIBundleDAT(chiBundleParams))
    val toDB = ValidIO(new DBInData())
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.allLcrdRetrun := DontCare
  io.resp := DontCare
  io.toDB := DontCare
  dontTouch(io)


}