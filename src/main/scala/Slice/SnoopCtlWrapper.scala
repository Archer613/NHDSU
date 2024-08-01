package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import Utils.FastArb._

class SnoopCtlWrapper()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // snpCtrl <-> rnSlave
    val snpTask       = Decoupled(new SnpTaskBundle())
    val snpResp       = Flipped(ValidIO(new SnpRespBundle()))
    // mainpipe <-> snpCtrl
    val mpTask        = Flipped(Decoupled(new MpSnpTaskBundle()))
    val mpResp        = Decoupled(new TaskBundle())
    // snpCtl free number
    val freeNum       = Output(UInt((snoopCtlIdBits + 1).W))
  })

  // TODO: Delete the following code when the coding is complete
  io.snpTask <> DontCare
  io.snpResp <> DontCare
  io.mpTask <> DontCare
  io.mpResp <> DontCare


// --------------------- Modules declaration ------------------------//
val snpCtls = Seq.fill(dsuparam.nrSnoopCtl) { Module(new SnoopCtl()) }
  snpCtls.foreach(_.io <> DontCare)


// --------------------- Reg / Wire declaration ------------------------//
  val freeVec           = Wire(Vec(dsuparam.nrSnoopCtl, Bool()))
  val reqSelId          = WireInit(0.U(snoopCtlIdBits.W)) // for mpTask

// --------------------- Connection ------------------------//
  /*
   * Select one snpCtl to receive mpTask
   */
  freeVec := snpCtls.map(_.io.mpTask.ready)
  reqSelId := PriorityEncoder(freeVec)
  snpCtls.zipWithIndex.foreach {
    case(snp, i) =>
      snp.io.snpId := i.U
      snp.io.mpTask.valid := io.mpTask.valid & reqSelId === i.U
      snp.io.mpTask.bits := io.mpTask.bits
  }
  io.mpTask.ready := true.B
  io.freeNum := PopCount(freeVec)


  /*
   * Select one snpCtl to receive snpResp
   */
  snpCtls.zipWithIndex.foreach {
    case(snp, i) =>
      snp.io.snpResp.valid := io.snpResp.valid & io.snpResp.bits.to.idL2 === i.U
      snp.io.snpResp.bits := io.snpResp.bits
  }

  /*
   * Output SnpTask and MpTask
   */
  fastArbDec2Dec(snpCtls.map(_.io.snpTask), io.snpTask, Some("SnpTaskArb"))
  fastArbDec2Dec(snpCtls.map(_.io.mpResp), io.mpResp, Some("mpTaskArb"))



// --------------------- Connection ------------------------//
  assert(Mux(io.mpTask.valid, io.freeNum > 0.U, true.B), "When mpTask input, must has snpCtl free")


}