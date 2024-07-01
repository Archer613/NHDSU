package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util.{Decoupled, ValidIO, switch, is, OHToUInt, PriorityEncoder}
import org.chipsalliance.cde.config._
import xs.utils.ParallelPriorityMux

class ReadCtl()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val mpTask = Flipped(Decoupled(new TaskBundle()))
    val mpResp = Decoupled(new TaskBundle())
    val rReq = Decoupled(new TaskBundle())
    val rxRspResp = Flipped(ValidIO(new CHIBundleRSP(chiBundleParams)))
    val rxDatResp = Flipped(ValidIO(new CHIBundleDAT(chiBundleParams)))
    val dbWrite = Decoupled(new DBReq())
    val dbResp = Flipped(ValidIO(new DBResp()))
  })

  // TODO: Delete the following code when the coding is complete
  io.mpTask := DontCare
  io.mpResp := DontCare
  io.rReq := DontCare
  io.rxRspResp := DontCare
  io.rxDatResp := DontCare
  io.dbWrite := DontCare
  io.dbResp := DontCare
  dontTouch(io)



  // --------------------- Reg/Wire declaration ----------------------- //
  val fsmReg      = RegInit(VecInit(Seq.fill(nrReadCtlEntry) { 0.U.asTypeOf(new ReadCtlTableEntry()) }))
  val fsmFreeVec  = Wire(Vec(nrReadCtlEntry, Bool()))
  val fsmSelId    = WireInit(0.U(rcEntryBits.W))

  dontTouch(fsmReg)
  dontTouch(fsmFreeVec)
  dontTouch(fsmSelId)

  // ---------------------------- Logic  ------------------------------ //
  /*
   * select one free fsm for mpTask input
   */
  fsmFreeVec.zip(fsmReg.map(_.state)).foreach { case(v, s) => v := s === ReadState.FREE }
//  fsmSelId := OHToUInt(ParallelPriorityMux(fsmFreeVec.zipWithIndex.map { case (b, i) => (b, (1 << i).U) }))
  fsmSelId := PriorityEncoder(fsmFreeVec)


  /*
   * receive mpTask
   */
  io.mpTask.ready := fsmFreeVec.asUInt.orR

  /*
   * fsm: FREE -> GET_ID -> WAIT_ID -> SEND_REQ -> WAIT_RESP -> FREE
   */
  fsmReg.zipWithIndex.foreach {
    case (fsm, i) =>
      switch(fsm.state) {
        // ReadState.FREE: Wait mpTask input
        is(ReadState.FREE) {
          when(io.mpTask.fire & fsmSelId === i.U) {
            fsm.state := ReadState.GET_ID
            fsm.from := io.mpTask.bits.from
            fsm.addr := io.mpTask.bits.addr
          }
        }
        is(ReadState.GET_ID) {

        }
      }
  }



  // -------------------------- Assertion ----------------------------- //



}