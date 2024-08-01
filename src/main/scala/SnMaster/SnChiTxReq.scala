package DONGJIANG.SNMASTER

import DONGJIANG._
import _root_.DONGJIANG.CHI._
import chisel3._
import chisel3.util.{Cat, Decoupled, is, log2Ceil, switch}
import org.chipsalliance.cde.config._

class SnChiTxReqBundle(implicit p: Parameters) extends DJBundle {
  // TODO: RespBundle
  val opcode      = UInt(5.W)
  val addr        = UInt(addressBits.W)
  val txnid       = UInt(8.W)
}

class SnChiTxReq()(implicit p: Parameters) extends DJModule {
  val io = IO(new Bundle {
    val chi = CHIChannelIO(new CHIBundleREQ(chiBundleParams))
    val txState = Input(UInt(LinkStates.width.W))
    val task = Flipped(Decoupled(new SnChiTxReqBundle()))
  })

// ------------------- Reg/Wire declaration ---------------------- //
  val lcrdFreeNumReg  = RegInit(0.U(snTxlcrdBits.W))
  val flitReg         = RegInit(0.U.asTypeOf(new CHIBundleREQ(chiBundleParams)))
  val flitvReg        = RegInit(false.B)
  val flit            = WireInit(0.U.asTypeOf(new CHIBundleREQ(chiBundleParams)))
  val flitv           = WireInit(false.B)
  val taskReady       = WireInit(false.B)



// ------------------------- Logic ------------------------------- //
  /*
   * task to TXREQFLIT
   * Read* txnID:       0XXX_XXXX, X = dbid
   * WriteBack* txnID:  1XXX_XXXX, X = wbid
   */
  flit.tgtID      := djparam.idmap.SNID.U
  flit.srcID      := djparam.idmap.HNID.U
  flit.txnID      := io.task.bits.txnid
  flit.opcode     := io.task.bits.opcode
  flit.size       := log2Ceil(djparam.blockBytes).U
  flit.addr       := io.task.bits.addr
  flit.order      := 0.U
  flit.lpID       := 0.U //  Logical Processor Identifier
  flit.expCompAck := false.B
  flitv           := io.task.fire

  /*
   * set reg value
   */
  flitvReg := flitv
  flitReg := Mux(flitv, flit, flitReg)


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
  io.chi.flitv := flitvReg
  io.chi.flit := flitReg

// --------------------- Assertion ------------------------------- //
  assert(Mux(io.task.valid, io.task.bits.opcode === CHIOp.REQ.ReadNoSnp | io.task.bits.opcode === CHIOp.REQ.WriteNoSnpFull, true.B), "DSU dont support TXREQ[0x%x]", io.task.bits.opcode)

}