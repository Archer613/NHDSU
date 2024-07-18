package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, Queue, ValidIO, Valid, is, switch}
import org.chipsalliance.cde.config._

class RxRespBundle(implicit p: Parameters) extends DSUBundle {
  val valid   = Bool()
  val txnid   = UInt(chiTxnidBits.W)
  val dbid    = UInt(chiDbidBits.W)
}

class DsuChiRxRsp()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi           = Flipped(CHIChannelIO(new CHIBundleRSP(chiBundleParams)))
    val rxState       = Input(UInt(LinkStates.width.W))
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val resp2rc       = Valid(new CHIBundleRSP(chiBundleParams))
    val resp2dat      = Output(new RxRespBundle())
  })

  
// --------------------- Modules declaration --------------------- //
  val queue = Module(new Queue(new CHIBundleRSP(chiBundleParams), entries = 1, pipe = true, flow = false, hasFlush = false))


  // ------------------- Reg/Wire declaration ---------------------- //
  val lcrdSendNumReg  = RegInit(0.U(rnTxlcrdBits.W))
  val lcrdFreeNum     = Wire(UInt(rnTxlcrdBits.W))
  val lcrdv           = WireInit(false.B)
  val dbidReg         = RegInit(0.U.asTypeOf(Valid(UInt(dbIdBits.W))))
  val enq             = WireInit(0.U.asTypeOf(queue.io.enq))
  dontTouch(lcrdFreeNum)


// ------------------------- Logic ------------------------------- //
  // Count lcrd
  lcrdSendNumReg := lcrdSendNumReg + io.chi.lcrdv.asUInt - io.chi.flitv.asUInt
  lcrdFreeNum := dsuparam.nrSnRxLcrdMax.U - queue.io.count - lcrdSendNumReg


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
      lcrdv := lcrdFreeNum > 0.U
      // Receive txReq
      enq.valid := RegNext(io.chi.flitpend) & io.chi.flitv
      enq.bits := io.chi.flit
    }
    is(LinkStates.DEACTIVATE) {
      // TODO: should consider io.chi.flit.bits.opcode
    }
  }
  io.chi.lcrdv := lcrdv
  queue.io.enq <> enq

  // allLcrdRetrun
  io.allLcrdRetrun := lcrdSendNumReg === 0.U

  /*
   * Output resp signals
   */
  io.resp2rc.valid        := queue.io.deq.valid & queue.io.deq.bits.opcode === CHIOp.RSP.ReadReceipt
  io.resp2rc.bits         := queue.io.deq.bits
  io.resp2dat.valid       := queue.io.deq.valid & queue.io.deq.bits.opcode === CHIOp.RSP.CompDBIDResp
  io.resp2dat.txnid  := queue.io.deq.bits.txnID
  io.resp2dat.dbid   := queue.io.deq.bits.dbID
  queue.io.deq.ready      := true.B


// ------------------------ assertion ------------------------ //
  assert(Mux(queue.io.deq.valid, queue.io.deq.bits.opcode === CHIOp.RSP.CompDBIDResp, true.B), "DSU dont support RXRSP[0x%x]", queue.io.deq.bits.opcode)
  assert(Mux(!queue.io.enq.ready, !io.chi.flitv, true.B), "DsuRxRsp overflow")


}