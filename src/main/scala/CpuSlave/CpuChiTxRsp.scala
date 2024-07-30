package NHDSU.CPUSALVE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, Queue, is, switch}
import org.chipsalliance.cde.config._

class CpuChiTxRsp()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val chi = Flipped(CHIChannelIO(new CHIBundleRSP(chiBundleParams)))
    val txState = Input(UInt(LinkStates.width.W))
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val flit = Decoupled(new CHIBundleRSP(chiBundleParams))
  })

  // TODO: Delete the following code when the coding is complete
  io.chi := DontCare
  io.allLcrdRetrun := DontCare
  io.flit := DontCare
  dontTouch(io)

// --------------------- Modules declaration --------------------- //
  val queue = Module(new Queue(new CHIBundleRSP(chiBundleParams), entries = 1, pipe = true, flow = false, hasFlush = false))

// ------------------- Reg/Wire declaration ---------------------- //
  val lcrdSendNumReg = RegInit(0.U(rnTxlcrdBits.W))
  val lcrdFreeNum = Wire(UInt(rnTxlcrdBits.W))
  val lcrdv = WireInit(false.B)
  val enq = WireInit(0.U.asTypeOf(io.flit))
  dontTouch(lcrdFreeNum)

// --------------------- Logic ----------------------------------- //
  // Count lcrd
  lcrdSendNumReg := lcrdSendNumReg + io.chi.lcrdv.asUInt - io.chi.flitv.asUInt
  lcrdFreeNum := dsuparam.nrRnTxLcrdMax.U - lcrdSendNumReg


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
      lcrdv := lcrdFreeNum > 0.U
      // Receive txReq
      enq.valid := RegNext(io.chi.flitpend) & io.chi.flitv
      enq.bits := io.chi.flit
    }
    is(LinkStates.DEACTIVATE) {
      // TODO: should consider io.chi.flit.bits.opcode
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


// --------------------- Assertion ------------------------------- //
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
      assert(!io.chi.lcrdv, "When DEACTIVATE, HN cant send lcrdv")
    }
  }

  assert(lcrdSendNumReg <= dsuparam.nrRnTxLcrdMax.U, "Lcrd be send cant over than nrRnTxLcrdMax")
  assert(queue.io.count <= dsuparam.nrRnTxLcrdMax.U, "queue.io.count cant over than nrRnTxLcrdMax")
  assert(lcrdFreeNum <= dsuparam.nrRnTxLcrdMax.U, "lcrd free num cant over than nrRnTxLcrdMax")
  assert(io.flit.ready, "io flit ready should always be true")
  assert(queue.io.enq.ready, "io enq ready should always be true")
  assert(Mux(io.flit.valid, io.flit.bits.opcode === CHIOp.RSP.CompAck | io.flit.bits.opcode === CHIOp.RSP.SnpResp, true.B), "DSU dont support TXRSP[0x%x]", io.flit.bits.opcode)
}