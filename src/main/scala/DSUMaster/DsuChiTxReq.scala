package NHDSU.DSUMASTER

import NHDSU._
import _root_.NHDSU.CHI._
import chisel3._
import chisel3.util.{Cat, Decoupled, is, log2Ceil, switch}
import org.chipsalliance.cde.config._

class DsuChiTxReqBundle(implicit p: Parameters) extends DSUBundle {
  // TODO: TaskRespBundle
  val opcode      = UInt(5.W)
  val addr        = UInt(addressBits.W)
  val txnid       = UInt(8.W)
}

class DsuChiTxReq()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleREQ(chiBundleParams))
    val txState = Input(UInt(LinkStates.width.W))
    val task = Flipped(Decoupled(new DsuChiTxReqBundle()))
  })

// ------------------- Reg/Wire declaration ---------------------- //
  val lcrdFreeNumReg  = RegInit(0.U(snTxlcrdBits.W))
  val filtReg         = RegInit(0.U.asTypeOf(new CHIBundleREQ(chiBundleParams)))
  val filtvReg        = RegInit(false.B)
  val flit            = WireInit(0.U.asTypeOf(new CHIBundleREQ(chiBundleParams)))
  val flitv           = WireInit(false.B)
  val taskReady       = WireInit(false.B)



// ------------------------- Logic ------------------------------- //
  /*
   * task to TXREQFLIT
   * Read* txnID:       0XXX_XXXX, X = dbid
   * WriteBack* txnID:  1XXX_XXXX, X = wbid
   */
  flit.tgtID      := dsuparam.idmap.SNID.U
  flit.srcID      := dsuparam.idmap.HNID.U
  flit.txnID      := io.task.bits.txnid
  flit.opcode     := io.task.bits.opcode
  flit.size       := log2Ceil(dsuparam.blockBytes).U
  flit.addr       := io.task.bits.addr
  flit.order      := 0.U
  flit.lpID       := 0.U //  Logical Processor Identifier
  flit.expCompAck := false.B
  flitv           := io.task.fire

  /*
   * set reg value
   */
  filtvReg := flitv
  filtReg := Mux(flitv, flit, filtReg)


  /*
   * FSM: count free lcrd and set task ready value
   */
  switch(io.txState) {
    is(LinkStates.STOP) {
      // Nothing to do
    }
    is(LinkStates.ACTIVATE) {
      lcrdFreeNumReg := lcrdFreeNumReg + io.chi.lcrdv.asUInt
    }
    is(LinkStates.RUN) {
      lcrdFreeNumReg := lcrdFreeNumReg + io.chi.lcrdv.asUInt - flitv
      taskReady:= lcrdFreeNumReg > 0.U
    }
    is(LinkStates.DEACTIVATE) {
      // TODO: return lcrd logic
    }
  }
  io.task.ready := taskReady

  /*
   * Output chi flit
   */
  io.chi.flitpend := flitv
  io.chi.flitv := filtvReg
  io.chi.flit := filtReg



}