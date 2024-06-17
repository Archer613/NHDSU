package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class ProtocolLayerCtrl()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chiLinkCtrl = Flipped(new CHILinkCtrlIO())
    val txState = Output(UInt(LinkStates.width.W))
    val rxState = Output(UInt(LinkStates.width.W))
    val reqBufsVal = Input(Bool())
    val txAllLcrdRetrun = Input(Bool())
  })
  // DontCare txsactive and rxsactive
  io.chiLinkCtrl.rxsactive := true.B

  val txStateReg = RegInit(LinkStates.STOP)
  val rxStateReg = RegInit(LinkStates.STOP)

  val txactiveack = WireInit(false.B)
  val rxactivereq = WireInit(false.B)

  txStateReg := LinkStates.getLinkState(io.chiLinkCtrl.txactivereq, io.chiLinkCtrl.txactiveack)
  rxStateReg := LinkStates.getLinkState(io.chiLinkCtrl.rxactivereq, io.chiLinkCtrl.rxactiveack)

  /*
   * txState FSM ctrl by io.chiLinkCtrl.txactiveack
   */
  switch(txStateReg) {
    is(LinkStates.STOP) {
      txactiveack := false.B
    }
    is(LinkStates.ACTIVATE) {
      txactiveack := true.B
    }
    is(LinkStates.RUN) {
      txactiveack := true.B
    }
    is(LinkStates.DEACTIVATE) {
      txactiveack := !io.txAllLcrdRetrun
    }
  }


  /*
   * rxState FSM ctrl by io.chiLinkCtrl.rxactivereq
   */
  switch(rxStateReg) {
    is(LinkStates.STOP) {
      rxactivereq := io.reqBufsVal
    }
    is(LinkStates.ACTIVATE) {
      rxactivereq := true.B
    }
    is(LinkStates.RUN) {
      rxactivereq := Mux(io.reqBufsVal, true.B, !(txStateReg === LinkStates.DEACTIVATE | txStateReg === LinkStates.STOP))
    }
    is(LinkStates.DEACTIVATE) {
      rxactivereq := false.B
    }
  }

  io.txState := txStateReg
  io.rxState := rxStateReg

  io.chiLinkCtrl.txactiveack := txactiveack
  io.chiLinkCtrl.rxactivereq := rxactivereq

}