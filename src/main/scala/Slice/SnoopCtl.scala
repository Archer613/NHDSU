package NHDSU.SLICE

import NHDSU._
import _root_.NHDSU.CHI.CHIChannel
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class SnoopCtl()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val snpId         = Input(UInt(snoopCtlIdBits.W))
    val sliceId       = Input(UInt(bankBits.W))
    // snpCtrl <-> cpuslave
    val snpTask       = Decoupled(new SnpTaskBundle())
    val snpResp       = Flipped(ValidIO(new SnpRespBundle()))
    // mainpipe <-> snpCtrl
    val mpTask        = Flipped(Decoupled(new MpSnpTaskBundle()))
    val mpResp        = Decoupled(new TaskBundle())
  })

  // TODO: Delete the following code when the coding is complete
  io.snpTask <> DontCare
  io.snpResp <> DontCare
  io.mpTask <> DontCare
  io.mpResp <> DontCare


// --------------------- Reg / Wire declaration ------------------------//
  val validReg    = RegInit(false.B)
  val mpTaskReg   = RegInit(0.U.asTypeOf(new MpSnpTaskBundle()))
  val needSnpVec  = Wire(Vec(dsuparam.nrCore, Bool()))
  val doneVecReg  = Reg(Vec(dsuparam.nrCore, Bool()))
  val respVecReg  = Reg(Vec(dsuparam.nrCore, Bool()))
  val respReg     = RegInit(0.U.asTypeOf(new SnpRespBundle()))
  val release     = WireInit(false.B)
  val respCoreId  = Wire(UInt(coreIdBits.W))

// --------------------- Logic ------------------------//
  /*
   * Receive mainpipe Task
   */
  validReg := Mux(validReg, !io.mpResp.fire, io.mpTask.valid)
  when(io.mpTask.valid) { mpTaskReg := io.mpTask.bits }
  io.mpTask.ready := !validReg

  /*
   * determine which core should be snoop
   */
  val isSnpHlp    = mpTaskReg.isSnpHlp
  val sourceId    = mpTaskReg.from.idL1
  val hitVec      = mpTaskReg.hitVec
  val needSnpNum  = PopCount(needSnpVec)
  needSnpVec.zipWithIndex.foreach { case(need, i) => need := Mux(sourceId === i.U, isSnpHlp & hitVec(i), hitVec(i)) }

  /*
   * send task to cpuSlaves
   */
  val retToSrcId = PriorityEncoder(needSnpVec.asUInt)
  val snpCoreId = PriorityEncoder(needSnpVec.asUInt ^ doneVecReg.asUInt)
  val snpDone = needSnpNum === PopCount(doneVecReg)
  io.snpTask.valid                := validReg & !snpDone
  io.snpTask.bits.from.idL0       := IdL0.SLICE
  io.snpTask.bits.from.idL1       := io.sliceId
  io.snpTask.bits.from.idL2       := io.snpId
  io.snpTask.bits.to.idL0         := IdL0.CPU
  io.snpTask.bits.to.idL1         := snpCoreId
  io.snpTask.bits.to.idL2         := DontCare
  io.snpTask.bits.opcode          := mpTaskReg.opcode
  io.snpTask.bits.addr            := mpTaskReg.addr
  io.snpTask.bits.snpDoNotGoToSD  := mpTaskReg.snpDoNotGoToSD
  io.snpTask.bits.snpRetToSrc     := mpTaskReg.snpRetToSrc & retToSrcId === snpCoreId

  when(!validReg) {
    doneVecReg.foreach(_ := false.B)
  }.otherwise {
    doneVecReg(snpCoreId) := Mux(doneVecReg(snpCoreId), true.B, io.snpTask.fire)
  }

  /*
   * Receive snpResp
   */
  respCoreId := io.snpResp.bits.from.idL1
  when(!validReg) {
    respVecReg.foreach(_ := false.B)
    respReg := 0.U.asTypeOf(respReg)
  }.otherwise {
    respVecReg(respCoreId) := Mux(respVecReg(respCoreId), true.B, io.snpResp.fire)
    when(io.snpResp.fire) {
      respReg := io.snpResp.bits
      respReg.hasData := respReg.hasData | io.snpResp.bits.hasData
      respReg.dbid := Mux(io.snpResp.bits.hasData, io.snpResp.bits.dbid, respReg.dbid)
    }
  }

  /*
   * Send mpResp to mainPipe
   */
  io.mpResp.valid := validReg & needSnpNum === PopCount(respVecReg)
  io.mpResp.bits.from.idL0  := IdL0.SLICE
  io.mpResp.bits.from.idL1  := DontCare
  io.mpResp.bits.from.idL2  := DontCare
  io.mpResp.bits.to         := mpTaskReg.from
  io.mpResp.bits.addr       := mpTaskReg.addr
  io.mpResp.bits.opcode     := mpTaskReg.srcOp
  io.mpResp.bits.resp       := respReg.resp
  io.mpResp.bits.isWB       := false.B
  io.mpResp.bits.isSnpHlp   := mpTaskReg.isSnpHlp
  io.mpResp.bits.writeBt    := false.B
  io.mpResp.bits.readDir    := true.B
  io.mpResp.bits.btWay      := mpTaskReg.btWay
  io.mpResp.bits.willSnp    := false.B
  io.mpResp.bits.dbid       := respReg.dbid
  io.mpResp.bits.snpHasData := respReg.hasData



// --------------------- Assertion ------------------------//
  when(validReg) { assert(needSnpNum >= 1.U) }
  when(io.snpTask.fire) { assert(!doneVecReg(snpCoreId)) }
  when(io.snpResp.fire) { assert(!respVecReg(respCoreId)) }
  when(io.mpTask.fire) { assert(io.mpTask.bits.hitVec.asUInt.orR) }

  // TIME OUT CHECK
  val cntReg = RegInit(0.U(64.W))
  cntReg := Mux(!validReg, 0.U, cntReg + 1.U)
  assert(cntReg < TIMEOUT_SNP.U, "SNPCTL[0x%x] OP[0x%x] ADDR[0x%x] SNPHLP[%d] TIMEOUT", io.snpId, mpTaskReg.opcode, mpTaskReg.addr, mpTaskReg.isSnpHlp.asUInt)
}