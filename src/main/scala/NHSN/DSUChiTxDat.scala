package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU.CHI._
import NHDSU._
import NHDSU.CHI.CHIOp.DAT

class DSUChiTxDat (implicit p : Parameters) extends DSUModule {
 // -------------------------- IO declaration -----------------------------//
  val io                     = IO(new Bundle {
    val chi                  = Flipped(CHIChannelIO(new CHIBundleDAT(chiBundleParams)))
    val txState              = Input(UInt(LinkStates.width.W))
    //Dequeue flit
    val flit                 = Decoupled(new CHIBundleDAT(chiBundleParams))
    val lcrdReturn           = Output(Bool())
  })


// --------------------- Modules declaration --------------------- //
  val queue                  = Module(new Queue(new CHIBundleDAT(chiBundleParams), entries = dsuparam.nrSnTxLcrdMax, pipe = true, flow = false, hasFlush = false))

// ------------------- Reg/Wire declaration ---------------------- //
  val lcrdSendNumReg         = RegInit(0.U(rnTxlcrdBits.W))
  val lcrdFreeNum            = Wire(UInt(rnTxlcrdBits.W))
  val lcrdv                  = WireInit(false.B)
  val enq                    = WireInit(0.U.asTypeOf(io.flit))
  val inFlit                 = WireInit(0.U.asTypeOf(new CHIBundleDAT(chiBundleParams)))
  val lcrdReturn             = WireInit(false.B)
  dontTouch(lcrdFreeNum)

// --------------------- Logic ----------------------------------- //
  // Count lcrd
  lcrdSendNumReg            := lcrdSendNumReg + io.chi.lcrdv.asUInt - io.chi.flitv.asUInt
  lcrdFreeNum               := dsuparam.nrSnTxLcrdMax.U - queue.io.count - lcrdSendNumReg


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
      lcrdv                 := lcrdFreeNum > 0.U
      // Receive txReq
      enq.valid             := RegNext(io.chi.flitpend) & io.chi.flitv
      enq.bits              := io.chi.flit
      when(inFlit.opcode    === DAT.DataLCrdReturn & io.chi.flitv){
        lcrdReturn          := true.B
      }
    }
    is(LinkStates.DEACTIVATE) {
      when(inFlit.opcode    === DAT.DataLCrdReturn & io.chi.flitv){
        lcrdReturn          := true.B
      }
    }
  }

  when(lcrdReturn){
    lcrdSendNumReg          := 0.U
  }

  /*
   * Connection
   */
  // lcrdv
  io.chi.lcrdv              := lcrdv
  // enq
  queue.io.enq              <> enq
  // deq
  io.flit                   <> queue.io.deq
  io.lcrdReturn             := lcrdReturn


// --------------------- Assertion ------------------------------- //
  switch(io.txState) {
    is(LinkStates.STOP) {
      assert(!io.chi.flitv, "When STOP, HN cant send flit")
    }
    is(LinkStates.ACTIVATE) {
      assert(!io.chi.flitv, "When ACTIVATE, HN cant send flit")
    }
    is(LinkStates.RUN) {
      assert(Mux(queue.io.enq.valid, queue.io.enq.ready, true.B), "When flitv is true, queue must be able to receive flit")
    }
    is(LinkStates.DEACTIVATE) {
      assert(!io.chi.lcrdv,  "When DEACTIVATE, SN cant send lcrdv")
    }
  }

  assert(lcrdSendNumReg <= dsuparam.nrSnTxLcrdMax.U, "Lcrd be send cant over than nrSnTxLcrdMax")
  assert(queue.io.count <= dsuparam.nrSnTxLcrdMax.U, "queue.io.count cant over than nrSnTxLcrdMax")
  assert(lcrdFreeNum <= dsuparam.nrSnTxLcrdMax.U, "lcrd free num cant over than nrSnTxLcrdMax")
}
