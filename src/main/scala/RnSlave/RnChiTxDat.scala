package DONGJIANG.RNSLAVE

import DONGJIANG._
import DONGJIANG.CHI._
import chisel3._
import chisel3.util.{Decoupled, Queue, is, switch}
import org.chipsalliance.cde.config._

class RnChiTxDat()(implicit p: Parameters) extends DJModule {
  val io = IO(new Bundle {
    val chi = Flipped(CHIChannelIO(new CHIBundleDAT(chiBundleParams)))
    val txState = Input(UInt(LinkStates.width.W))
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val flit = Decoupled(new CHIBundleDAT(chiBundleParams))
    val dataTDB = Decoupled(new RnDBInData())
  })

  // TODO: Delete the following code when the coding is complete
//  io.chi := DontCare
//  io.allLcrdRetrun := DontCare
//  io.flit := DontCare
//  io.dataTDB := DontCare
//  dontTouch(io)

// --------------------- Modules declaration --------------------- //
  val queue = Module(new Queue(new CHIBundleDAT(chiBundleParams), entries = nrBeat, pipe = true, flow = false, hasFlush = false))

// ------------------- Reg/Wire declaration ---------------------- //
  val lcrdSendNumReg  = RegInit(0.U(rnTxlcrdBits.W))
  val lcrdFreeNum     = Wire(UInt(rnTxlcrdBits.W))
  val lcrdv           = WireInit(false.B)
  val enq             = WireInit(0.U.asTypeOf(io.flit))
  val dbid            = WireInit(0.U(dbIdBits.W))
  val bankId          = WireInit(0.U(bankBits.W))

  dontTouch(lcrdFreeNum)
  dontTouch(dbid)
  dontTouch(bankId)

// --------------------- Logic ----------------------------------- //
  // Count lcrd
  lcrdSendNumReg := lcrdSendNumReg + io.chi.lcrdv.asUInt - io.chi.flitv.asUInt
  lcrdFreeNum := nrBeat.U - queue.io.count - lcrdSendNumReg // max num = nrBeat

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
  io.flit.valid       := io.dataTDB.fire
  io.flit.bits        := queue.io.deq.bits
  queue.io.deq.ready  := io.dataTDB.ready

  // data to dataBuffre
  dbid                    := queue.io.deq.bits.txnID(dbIdBits - 1 ,0)
  bankId                  := (queue.io.deq.bits.txnID >> dbIdBits)(bankBits - 1, 0)
  io.dataTDB.valid        := queue.io.deq.valid
  io.dataTDB.bits.to.idL0 := IdL0.SLICE
  io.dataTDB.bits.to.idL1 := bankId
  io.dataTDB.bits.to.idL2 := DontCare
  io.dataTDB.bits.dbid    := dbid
  io.dataTDB.bits.dataID  := queue.io.deq.bits.dataID
  io.dataTDB.bits.data    := queue.io.deq.bits.data


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

  assert(lcrdSendNumReg <= djparam.nrRnTxLcrdMax.U, "Lcrd be send cant over than nrRnTxLcrdMax")
  assert(queue.io.count <= djparam.nrRnTxLcrdMax.U, "queue.io.count cant over than nrRnTxLcrdMax")
  assert(lcrdFreeNum <= djparam.nrRnTxLcrdMax.U, "lcrd free num cant over than nrRnTxLcrdMax")
  assert(io.flit.ready, "io flit ready should always be true")
  assert(Mux(io.flit.valid, io.flit.bits.opcode === CHIOp.DAT.CopyBackWrData | io.flit.bits.opcode === CHIOp.DAT.SnpRespData, true.B), "DSU dont support TXDAT[0x%x]", io.flit.bits.opcode)


}