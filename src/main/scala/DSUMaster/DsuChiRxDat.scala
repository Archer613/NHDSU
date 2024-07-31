package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO, is, switch}
import org.chipsalliance.cde.config._

class DsuChiRxDat()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi             = Flipped(CHIChannelIO(new CHIBundleDAT(chiBundleParams)))
    val rxState         = Input(UInt(LinkStates.width.W))
    val allLcrdRetrun   = Output(Bool()) // Deactive Done
    val resp            = ValidIO(new CHIBundleDAT(chiBundleParams))
    val dataTDB         = Decoupled(new MsDBInData())
  })

  // TODO: Delete the following code when the coding is complete
  io.allLcrdRetrun := DontCare


// ------------------- Reg/Wire declaration ---------------------- //
  val lcrdHasNumReg = RegInit(dsuparam.nrSnTxLcrdMax.U(rnTxlcrdBits.W))
  val lcrdv         = WireInit(false.B)
  val flitReg       = RegInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitvReg      = RegInit(false.B)
  val flitv         = WireInit(false.B)


// ------------------------- Logic ------------------------------- //
  /*
   * receive flit
   */
  flitv := io.chi.flitv
  flitvReg := flitv
  flitReg := Mux(flitv, io.chi.flit, flitReg)

  /*
   * FSM
   */
  switch(io.rxState) {
    is(LinkStates.STOP) {
      // Nothing to do
    }
    is(LinkStates.ACTIVATE) {
      // Nothing to do
    }
    is(LinkStates.RUN) {
      // Send lcrd
      lcrdv := lcrdHasNumReg > 0.U
      // Count lcrd
      lcrdHasNumReg := lcrdHasNumReg - lcrdv + flitv
    }
    is(LinkStates.DEACTIVATE) {
      // TODO: receive lcrd logic
    }
  }
  io.chi.lcrdv := lcrdv

  /*
   * send resp
   */
  io.resp.valid := flitvReg
  io.resp.bits := flitReg

  /*
   * send data to DataBuffer
   */
  io.dataTDB.valid := flitvReg
  io.dataTDB.bits.data := flitReg.data
  io.dataTDB.bits.dbid := flitReg.txnID
  io.dataTDB.bits.dataID := flitReg.dataID


// --------------------- Assertion ------------------------------- //
  assert(io.dataTDB.ready === true.B, "dataTDB ready should always be true.B")
  assert(Mux(flitv, RegNext(io.chi.flitpend), true.B), "Flitpend is required that asserted exactly one cycle before a flit is sent from the transmitter.")

}