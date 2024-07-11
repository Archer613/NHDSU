package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, is, switch}
import org.chipsalliance.cde.config._

class CpuChiRxRsp()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleRSP(chiBundleParams))
    val rxState = Input(UInt(LinkStates.width.W))
    val flit = Flipped(Decoupled(new CHIBundleRSP(chiBundleParams)))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.flit := DontCare
  dontTouch(io)


// ------------------- Reg/Wire declaration ---------------------- //
  // Count lcrd
  val lcrdFreeNumReg  = RegInit(0.U(snTxlcrdBits.W))
  val flitReg         = RegInit(0.U.asTypeOf(new CHIBundleRSP(chiBundleParams)))
  val flitvReg        = RegInit(false.B)
  val flit            = WireInit(0.U.asTypeOf(new CHIBundleRSP(chiBundleParams)))
  val flitv           = WireInit(false.B)
  val taskReady       = WireInit(false.B)


// ------------------------- Logic ------------------------------- //
  /*
   * Receive task and data
   */
  flitv := io.flit.fire
  flit  := io.flit.bits

  /*
   * set reg value
   */
  flitvReg := flitv
  flitReg  := Mux(flitv, flit, flitReg)

  /*
   * FSM: count free lcrd and set task ready value
   */
  switch(io.rxState) {
    is(LinkStates.STOP) {
      // Nothing to do
    }
    is(LinkStates.ACTIVATE) {
      lcrdFreeNumReg := lcrdFreeNumReg + io.chi.lcrdv.asUInt
    }
    is(LinkStates.RUN) {
      lcrdFreeNumReg := lcrdFreeNumReg + io.chi.lcrdv.asUInt - flitv
      taskReady := lcrdFreeNumReg > 0.U
    }
    is(LinkStates.DEACTIVATE) {
      // TODO: return lcrd logic
    }
  }
  io.flit.ready := taskReady

  /*
   * Output chi flit
   */
  io.chi.flitpend := flitv
  io.chi.flitv    := flitvReg
  io.chi.flit     := flitReg

}