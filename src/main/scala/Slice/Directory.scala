package NHDSU.SLICE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.ReplacementPolicy
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}


class DirRead(implicit p: Parameters) extends DSUBundle {
  val selfUseWayOH    = UInt(dsuparam.ways.W)
  val clientUseWayOH  = UInt(dsuparam.clientWays.W)
  val sRefill         = Bool()
  val cRefill         = Bool()
  val addr            = UInt(addressBits.W)
}

class Directory()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead    = Flipped(Decoupled(new DirRead()))
  val sDirWrite  = Flipped(Decoupled(new SDirWrite()))
  val cDirWrite  = Flipped(Decoupled(new CDirWrite()))
  val sDirResp   = Decoupled(new SDirResp())
  val cDirResp   = Decoupled(new CDirResp())

  val resetFinish = Output(Bool())
})

  // TODO: Delete the following code when the coding is complete
  io.dirRead    <> DontCare
  io.sDirWrite  <> DontCare
  io.cDirWrite  <> DontCare
  io.sDirResp   <> DontCare
  io.cDirResp   <> DontCare
  io.resetFinish <> DontCare
}