package NHDSU.CPUSALVE

import NHDSU._
import _root_.NHDSU.CHI._
import chisel3._
import chisel3.util.{Counter, Decoupled, Queue, is, switch}
import org.chipsalliance.cde.config._

class CpuChiTxReq()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = Flipped(CHIChannelIO(new CHIBundleREQ(chiBundleParams)))
    val txState = Input(UInt(LinkStates.width.W))
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val flit = Decoupled(new CHIBundleREQ(chiBundleParams))
  })

// --------------------- Modules declaration ---------------------//
  val queue = Module(new Queue(new CHIBundleREQ(chiBundleParams), entries = dsuparam.nrRnTxLcrdMax, pipe = true, flow = false, hasFlush = false))

// --------------------- Wire declaration ------------------------//
  val lcrdSendNumReg = RegInit(0.U(rnTxlcrdBits.W))
  val lcrdFree = WireInit(false.B)
  val lcrdv = WireInit(false.B)
  val enq = WireInit(0.U.asTypeOf(io.flit))

// --------------------- Logic -----------------------------------//
  // count lcrd
  lcrdSendNumReg := lcrdSendNumReg + lcrdv.asUInt
  lcrdFree := dsuparam.nrRnTxLcrdMax.U - queue.io.count - lcrdSendNumReg

  /*
   * FSM
   */
  switch(io.txState) {
    is(LinkStates.STOP) {
      // Nothing to do
    }
    is(LinkStates.ACTIVATE) {
      // Nothing to do
    }
    is(LinkStates.RUN) {
      // Send lcrd
      lcrdv := lcrdFree
      // Receive txReq
      enq.valid := RegNext(io.chi.flitpend) & io.chi.flitv
      enq.bits := io.chi.flit
    }
    is(LinkStates.DEACTIVATE) {
      // TODO: should consider io.chi.flit.bits.opcode
      lcrdSendNumReg := lcrdSendNumReg - io.chi.flitv
    }
  }

  // allLcrdRetrun
  io.allLcrdRetrun := lcrdSendNumReg === 0.U

  /*
   * Connection
   */
  // lcrdv
  io.chi.lcrdv := lcrdv
  // enq
  queue.io.enq <> enq

  // deq
  io.flit <> queue.io.deq


// --------------------- Assertion -------------------------------//
  switch(io.txState) {
    is(LinkStates.STOP) {
      assert(!io.chi.flitv, "When STOP, RN cant send flit")
    }
    is(LinkStates.ACTIVATE) {
      assert(!io.chi.flitv, "When ACTIVATE, RN cant send flit")
    }
    is(LinkStates.RUN) {
      assert(Mux(queue.io.enq.valid, queue.io.enq.ready, true.B), "When flitv is true, queue must be able to receive flit")
    }
    is(LinkStates.DEACTIVATE) {
      assert(!io.chi.lcrdv,  "When DEACTIVATE, HN cant send lcrdv")
    }
  }

}