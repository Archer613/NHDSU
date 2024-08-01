package DONGJIANG.SNMASTER

import DONGJIANG._
import DONGJIANG.CHI.{CHILinkCtrlIO, LinkState, LinkStates}
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class ProtocolLayerCtrl()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val chiLinkCtrl = new CHILinkCtrlIO()
    val txState = Output(UInt(LinkStates.width.W))
    val rxState = Output(UInt(LinkStates.width.W))
    val rxAllLcrdRetrun = Input(Bool())
    val readCtlFsmVal = Input(Bool()) // TODO: Consider WriteBack
  })
  // DontCare txsactive and rxsactive
  io.chiLinkCtrl.txsactive := true.B

  val txState = LinkStates.getLinkState(io.chiLinkCtrl.txactivereq, io.chiLinkCtrl.txactiveack)
  val rxState = LinkStates.getLinkState(io.chiLinkCtrl.rxactivereq, io.chiLinkCtrl.rxactiveack)

  val txStateReg = RegInit(LinkStates.STOP)
  val rxStateReg = RegInit(LinkStates.STOP)

  val txactivereqReg = RegInit(false.B)
  val rxactiveackReg = RegInit(false.B)

  txStateReg := txState
  rxStateReg := rxState

  /*
   * txState FSM ctrl by io.chiLinkCtrl.txactiveack
   */
  switch(txStateReg) {
    is(LinkStates.STOP) {
      txactivereqReg := io.readCtlFsmVal
    }
    is(LinkStates.ACTIVATE) {
      txactivereqReg := true.B
    }
    is(LinkStates.RUN) {
      txactivereqReg := Mux(io.readCtlFsmVal, true.B, !(rxStateReg === LinkStates.DEACTIVATE | rxStateReg === LinkStates.STOP))
    }
    is(LinkStates.DEACTIVATE) {
      txactivereqReg := false.B
    }
  }

  /*
   * rxState FSM ctrl by io.chiLinkCtrl.rxactivereq
   */
  switch(rxStateReg) {
    is(LinkStates.STOP) {
      rxactiveackReg := false.B
    }
    is(LinkStates.ACTIVATE) {
      rxactiveackReg := true.B
    }
    is(LinkStates.RUN) {
      rxactiveackReg := true.B
    }
    is(LinkStates.DEACTIVATE) {
      rxactiveackReg := !io.rxAllLcrdRetrun
    }
  }

  io.txState := txState
  io.rxState := rxState

  io.chiLinkCtrl.txactivereq := txactivereqReg
  io.chiLinkCtrl.rxactiveack := rxactiveackReg


}