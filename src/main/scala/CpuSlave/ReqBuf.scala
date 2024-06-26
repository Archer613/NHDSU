package NHDSU.CPUSALVE

import NHDSU._
import _root_.NHDSU.CHI._
import chisel3._
import org.chipsalliance.cde.config._
import chisel3.util.{Decoupled, RegEnable, ValidIO}

class ReqBuf()(implicit p: Parameters) extends DSUModule {
  val io = IO(new Bundle {
    val free        = Output(Bool())
    val cpuSlvId    = Input(UInt(coreIdBits.W))
    val reqBufId    = Input(UInt(reqBufIdBits.W))
    // CHI
    val chi         = Flipped(CHIBundleDecoupled(chiBundleParams))
    // mainpipe
    val mpTask      = Decoupled(new TaskBundle())
    val mpResp      = Flipped(ValidIO(new TaskRespBundle()))
    // snpCtrl
    val snpTask     = Flipped(Decoupled(new TaskBundle()))
    val snpResp     = Decoupled(new TaskRespBundle())
    // dataBuffer
    val dbReq       = Decoupled(new DBReq())
    val dbResp      = Flipped(ValidIO(new DBResp()))
    val dbDataValid = Input(Bool())
  })

  // TODO: Delete the following code when the coding is complete
  io.free := true.B
  io.chi := DontCare
  io.snpTask := DontCare
  io.snpResp := DontCare
  io.mpTask := DontCare
  io.mpResp := DontCare
  io.dbReq := DontCare
  io.dbResp := DontCare
  dontTouch(io)

  // --------------------- Modules declaration ---------------------//


  // --------------------- Reg and Wire declaration ------------------------//
  val freeReg         = RegInit(true.B)
  val reqTxnIDReg     = WireInit(0.U.asTypeOf(io.chi.txreq.bits.txnID))
  val taskReg         = RegInit(0.U.asTypeOf(io.mpTask.bits))
  val fsmReg          = RegInit(0.U.asTypeOf(new RBFSMState()))
  val reqValid        = WireInit(false.B)
  val alloc           = WireInit(false.B)
  val release         = WireInit(false.B)
  val task            = WireInit(0.U.asTypeOf(io.mpTask.bits))

  // --------------------- Logic -----------------------------------//
  /*
   * ReqBuf release logic
   */
  alloc := !RegNext(reqValid) & reqValid
  freeReg := Mux(release, true.B, Mux(alloc, false.B, freeReg))
  io.free := freeReg
  when(release) {
    reqTxnIDReg   := 0.U
    taskReg       := 0.U.asTypeOf(taskReg)
    fsmReg        := 0.U.asTypeOf(fsmReg)
  }

  /*
   * Req Input
   */
  reqValid := io.chi.txreq.valid | io.snpTask.valid
  when(io.chi.txreq.valid) {
    taskReg         := task
    val txreq       = io.chi.txreq.bits
    // task base message
    task.channel    := CHIChannel.TXREQ
    task.opcode     := txreq.opcode
    task.isR        := (CHIOp.REQ.ReadShared <= txreq.opcode & txreq.opcode <= CHIOp.REQ.ReadUnique) |
                       (CHIOp.REQ.ReadOnceCleanInvalid <= txreq.opcode & txreq.opcode <= CHIOp.REQ.ReadNotSharedDirty)
    task.isWB       := CHIOp.REQ.WriteEvictFull <= txreq.opcode & txreq.opcode <= CHIOp.REQ.WriteUniquePtlStash
    // task addr
    task.addr       := txreq.addr
    // task id
    task.from.idL0  := IdL0.CPU
    task.from.idL1  := io.cpuSlvId
    task.from.idL2  := io.reqBufId
    task.to.idL0    := IdL0.SLICE
    task.to.idL1    := DontCare
    task.to.idL2    := DontCare
    // task other
    task.isClean    := false.B
    task.readDir    := true.B
    task.btWay      := DontCare
    // other
    reqTxnIDReg     := txreq.txnID
  }


  /*
   * Alloc and Release state
   */
  when(io.chi.txreq.valid) {
    fsmReg.s_rReq2mp  := task.isR
    fsmReg.s_wReq2mp  := task.isWB
    fsmReg.w_rResp    := true.B
  }.otherwise {
    fsmReg.s_rReq2mp := Mux(io.mpTask.fire, false.B, fsmReg.s_rReq2mp)
    fsmReg.s_wReq2mp := Mux(io.mpTask.fire, false.B, fsmReg.s_wReq2mp)
    fsmReg.w_rResp := Mux(io.mpResp.fire, false.B, fsmReg.w_rResp)
  }

  /*
   * task or resp output
   */
  io.mpTask.valid     := fsmReg.s_wReq2mp | fsmReg.s_rReq2mp
  io.mpTask.bits      := taskReg

  /*
   * io.chi.fire ctrl logic
   */
  io.chi.txreq.ready := freeReg
  io.chi.txdat.ready := true.B
  io.chi.txrsp.ready := true.B
  io.chi.rxdat.valid := false.B // TODO
  io.chi.rxrsp.valid := false.B // TODO
  io.chi.rxsnp.valid := false.B // TODO

  // Whether the io.chi.txreq and io.snpTask can be input is determined by io.free in ReqBufSel
  // So DontCare the following signals
  io.chi.txreq.ready := true.B
  io.snpTask.ready := true.B


  // --------------------- Assertion ------------------------------- //
  assert(Mux(!freeReg, !(io.chi.txreq.valid | io.snpTask.valid), true.B), "When ReqBuf valid, it cant input new req")
  assert(Mux(io.chi.txreq.valid | io.snpTask.valid, io.free, true.B), "Reqbuf cant block req input")
  assert(!(io.chi.txreq.valid & io.snpTask.valid), "Reqbuf cant receive txreq and snpTask at the same time")
  when(release) {
    assert(fsmReg.asUInt === 0.U, "when ReqBuf release, all task should be done")
  }
  assert(!(task.isR & task.isWB), "Cant alloc r and wb task at the same time")
}