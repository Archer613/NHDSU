package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU.CHI._
import NHDSU._

class DSUChiRxDat (implicit p : Parameters) extends DSUModule {
 // -------------------------- IO declaration -----------------------------//
    val io = IO(new Bundle {
      val chi                  = CHIChannelIO(new CHIBundleDAT(chiBundleParams))
      val rxState              = Input(UInt(LinkStates.width.W))
      val flit                 = Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))
    })
 // -------------------------- Wire/Reg define -----------------------------//
  val lcrdFreeNumReg           = RegInit(0.U(snTxlcrdBits.W))
  val flitReg                  = RegInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitvReg                 = RegInit(false.B)
  val flit                     = WireInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val flitv                    = WireInit(false.B)
  val taskReady                = WireInit(false.B)

 // -------------------------- Logic -----------------------------//
  flitv                       := io.flit.fire
  flit                        := io.flit.bits

  flitvReg                    := flitv
  flitReg                     := Mux(flitv, flit, flitReg)
  

  switch(io.rxState) {
    is(LinkStates.STOP) {
      // Nothing to do
    }
    is(LinkStates.ACTIVATE) {
      lcrdFreeNumReg         := lcrdFreeNumReg + io.chi.lcrdv.asUInt
    }
    is(LinkStates.RUN) {
      lcrdFreeNumReg         := lcrdFreeNumReg + io.chi.lcrdv.asUInt - flitv
      taskReady              := lcrdFreeNumReg > 0.U
    }
    is(LinkStates.DEACTIVATE) {
      // when(lcrdFreeNumReg > 0.U){
      //   flitv                := true.B
      //   flitReg              := 0.U.asTypeOf(io.flit.bits)
      //   lcrdFreeNumReg       := 0.U
      // }
    }
  }

  io.flit.ready              := taskReady 

  /*
   * Output chi flit
   */
  io.chi.flitpend            := flitv
  io.chi.flitv               := flitvReg
  io.chi.flit                := flitReg
}