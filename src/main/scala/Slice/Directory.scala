package NHDSU.SLICE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.ReplacementPolicy
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}

class DirectoryMetaEntry(implicit p: Parameters) extends DSUBundle with HasChiStates {
  val tag          = UInt(tagBits.W)
}

class DirRead(implicit p: Parameters) extends DSUBundle {
  val alreayUseWayOH = UInt(dsuparam.ways.W)
  val set = UInt(setBits.W)
  val tag = UInt(tagBits.W)
}

class DirWrite(implicit p: Parameters) extends DSUBundle {
  val wayOH = UInt(dsuparam.ways.W)
  val set   = UInt(setBits.W)
  val meta  = new DirectoryMetaEntry
}

class DirResp(implicit p: Parameters) extends DSUBundle {
  val meta  = new DirectoryMetaEntry
  val wayOH = UInt(dsuparam.ways.W)
  val hit   = Bool()
}

class Directory()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead   = Flipped(Decoupled(new DirRead))
  val dirWrite  = Flipped(Decoupled(new DirWrite))
  val dirResp   = Decoupled(new DirResp)

  // TODO: update replacer SRAM

  val resetFinish = Output(Bool())
})

  // TODO: Delete the following code when the coding is complete
  io.dirRead <> DontCare
  io.dirWrite <> DontCare
  io.dirResp <> DontCare
  io.resetFinish <> DontCare

// --------------------- Modules and SRAM declaration ------------------------//




// --------------------- Wire declaration ------------------------//


// -----------------------------------------------------------------------------------------
// Stage 1 (dir read) / (dir write)
// -----------------------------------------------------------------------------------------


// -----------------------------------------------------------------------------------------
// Stage 2(dir read)
// -----------------------------------------------------------------------------------------


// -----------------------------------------------------------------------------------------
// Stage 3(dir read)
// -----------------------------------------------------------------------------------------


// -----------------------------------------------------------------------------------------
// Stage 4(dir resp)
// -----------------------------------------------------------------------------------------

}