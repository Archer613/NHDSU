package DONGJIANG.RNSLAVE

import DONGJIANG._
import DONGJIANG.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO, is, switch}
import org.chipsalliance.cde.config._

class RnChiRxDat()(implicit p: Parameters) extends DJModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleDAT(chiBundleParams))
    val rxState = Input(UInt(LinkStates.width.W))
    val flit = Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))
    val dataFDB = Flipped(Decoupled(new RnDBOutData()))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.flit := DontCare
  io.dataFDB := DontCare
  dontTouch(io)

// ------------------- Reg/Wire declaration ---------------------- //
  // Count lcrd
  val lcrdFreeNumReg  = RegInit(0.U(snTxlcrdBits.W))
  val flitReg         = RegInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitvReg        = RegInit(false.B)
  val flit            = WireInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitv           = WireInit(false.B)
  val taskReady       = WireInit(false.B)


// ------------------------- Logic ------------------------------- //
  /*
   * Receive task and data
   */
  flitv       := io.dataFDB.fire & io.flit.fire
  flit        := io.flit.bits
  flit.data   := io.dataFDB.bits.data
  flit.dataID := io.dataFDB.bits.dataID

  /*
   * set reg value
   */
  flitvReg    := flitv
  flitReg     := Mux(flitv, flit, flitReg)

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
  io.dataFDB.ready := taskReady & io.flit.valid
  io.flit.ready    := taskReady & io.dataFDB.valid

  /*
   * Output chi flit
   */
  io.chi.flitpend := flitv
  io.chi.flitv := flitvReg
  io.chi.flit := flitReg


// ------------------------- Assert ------------------------------- //
  assert(Mux(io.flit.valid, io.dataFDB.valid, true.B), "In cpuRxDat, data will valid before flit valid or at the same time ")
  assert(Mux(flitv, io.flit.bits.dbID === io.dataFDB.bits.to.idL2, true.B), "RnRxDat flit and data dont match")

  assert(Mux(io.flit.valid, io.flit.bits.opcode === CHIOp.DAT.CompData | io.flit.bits.opcode === CHIOp.DAT.SnpRespData, true.B), "DongJiang dont support RXDAT[0x%x]", io.flit.bits.opcode)
  assert(Mux(lcrdFreeNumReg.andR, !io.chi.lcrdv | flitv, true.B), "RXDAT Lcrd overflow")
}