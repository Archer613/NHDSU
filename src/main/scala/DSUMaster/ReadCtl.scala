package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO, switch, is, OHToUInt, PriorityEncoder}
import org.chipsalliance.cde.config._
import xs.utils.ParallelPriorityMux

class ReadCtl()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val mpTask    = Flipped(Decoupled(new TaskBundle()))
    val mpResp    = Decoupled(new TaskBundle())
    val txReqRead = Decoupled(new DsuChiTxReqBundle())
    val rxRspResp = Flipped(ValidIO(new CHIBundleRSP(chiBundleParams)))
    val rxDatResp = Flipped(ValidIO(new CHIBundleDAT(chiBundleParams)))
    val dbWReq    = Decoupled(new DBWReq())
    val dbWResp   = Flipped(Decoupled(new DBWResp()))
    val readCtlFsmVal = Output(Bool())
  })

  // TODO: Delete the following code when the coding is complete
  io.mpTask := DontCare
  io.mpResp := DontCare
  io.txReqRead := DontCare
  io.rxRspResp := DontCare
  io.rxDatResp := DontCare
  io.dbWReq := DontCare
  io.dbWResp := DontCare
  dontTouch(io)



  // --------------------- Reg/Wire declaration ----------------------- //
  val fsmReg      = RegInit(VecInit(Seq.fill(nrReadCtlEntry) { 0.U.asTypeOf(new ReadCtlTableEntry()) }))
  val stateVec  = Wire(Vec(nrReadCtlEntry, Vec(RCState.nrState, Bool())))
  val selIdVec  = Wire(Vec(RCState.nrState, UInt(rcEntryBits.W)))

  dontTouch(fsmReg)
  dontTouch(stateVec)
  dontTouch(selIdVec)

  // ---------------------------- Logic  ------------------------------ //
  /*
   * Get stateVec and selIdVec
   * exa:
   * fsmReg.state := ALLOC, FREE, FREE, FREE
   * stateVec(FREE) := (0, 1, 1, 1)
   * selIdVec(FREE) := 1
   */
  stateVec.zipWithIndex.foreach{ case(v, i) => v.zip(fsmReg.map(_.state)).foreach{ case(v, s) => v := s === i.U } }
  selIdVec.zip(stateVec).foreach{ case(sel, vec) => sel := PriorityEncoder(vec) }


  /*
   * fsm: FREE -> GET_ID -> WAIT_ID -> SEND_REQ -> WAIT_RESP -> FREE
   * fsm: FREE -> GET_ID -> SEND_REQ -> WAIT_RESP -> FREE
   */
  fsmReg.zipWithIndex.foreach {
    case (fsm, i) =>
      switch(fsm.state) {
        // ReadState.FREE: Wait mpTask input
        is(RCState.FREE) {
          fsm.state := Mux(io.mpTask.fire & selIdVec(RCState.FREE) === i.U, RCState.GET_ID, RCState.FREE)
        }
        // ReadState.GET_ID: Get dbid from DataBuffer
        is(RCState.GET_ID) {
          fsm.state := Mux(io.dbWReq.fire & selIdVec(RCState.GET_ID) === i.U,
                          Mux(io.dbWResp.fire & !stateVec(RCState.WAIT_ID).asUInt.orR,
                            RCState.SEND_REQ,
                              RCState.WAIT_ID),
                                RCState.GET_ID)
        }
        // ReadState.WAIT_ID: Wait dbid from DataBuffer
        is(RCState.WAIT_ID) {
          fsm.state := Mux(io.dbWResp.fire & selIdVec(RCState.WAIT_ID) === i.U, RCState.SEND_REQ, RCState.WAIT_ID)
        }
        // ReadState.SEND_REQ: Send req to chiTxReq
        is(RCState.SEND_REQ) {
          fsm.state := Mux(io.txReqRead.fire & selIdVec(RCState.SEND_REQ) === i.U, RCState.WAIT_RESP, RCState.SEND_REQ)
        }
      }
  }

  /*
   * fsm state: FREE deal logic
   * Receive mpTask
   */
  io.mpTask.ready := stateVec(RCState.FREE).asUInt.orR
  when(io.mpTask.fire) {
    fsmReg(selIdVec(RCState.FREE)).addr := io.mpTask.bits.addr
    fsmReg(selIdVec(RCState.FREE)).opcode := io.mpTask.bits.opcode
    fsmReg(selIdVec(RCState.FREE)).from := io.mpTask.bits.from
  }


  /*
   * fsm state: GET_ID deal logic
   */
  io.dbWReq.valid := stateVec(RCState.GET_ID).asUInt.orR


  /*
   * fsm state: WAIT_ID deal logic
   * io.dbWResp.ready always be true.B
   */
  io.dbWResp.ready := true.B
  when(io.dbWResp.fire){
    when(stateVec(RCState.WAIT_ID).asUInt.orR){
      fsmReg(selIdVec(RCState.WAIT_ID)).txnid := io.dbWResp.bits.from.idL2
    }.otherwise {
      fsmReg(selIdVec(RCState.GET_ID)).txnid := io.dbWResp.bits.from.idL2
    }
  }

  /*
   * fsm state: SEND_REQ deal logic
   */
  io.txReqRead.valid := stateVec(RCState.SEND_REQ).asUInt.orR
  io.txReqRead.bits.opcode := CHIOp.REQ.ReadNoSnp
  io.txReqRead.bits.addr   := fsmReg(selIdVec(RCState.SEND_REQ)).addr
  io.txReqRead.bits.txnid  := fsmReg(selIdVec(RCState.SEND_REQ)).txnid

  /*
   * readCtlFsmVal
   */
  io.readCtlFsmVal := !stateVec(RCState.FREE).asUInt.andR


  // -------------------------- Assertion ----------------------------- //



}