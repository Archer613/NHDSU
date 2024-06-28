package NHDSU.SLICE

import NHDSU._
import _root_.NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate

class SDirMetaEntry(implicit p: Parameters) extends DSUBundle with HasChiStates {
  val tag          = UInt(sTagBits.W)
  val bank         = UInt(bankBits.W)
}

class SDirRead(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasSelfAddrBits {
  override def useAddrVal: Boolean = useAddr
  val mes = new DirReadBase(dsuparam.ways)
}

class SDirWrite(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasSelfAddrBits with HasChiStates {
  override def useAddrVal: Boolean = useAddr
  val wayOH = UInt(dsuparam.ways.W)
}

class SDirResp(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasSelfAddrBits with HasChiStates {
  override def useAddrVal: Boolean = useAddr
  val wayOH = UInt(dsuparam.ways.W)
  val hit   = Bool()
}

class SelfDirectory()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead   = Flipped(Decoupled(new SDirRead))
  val dirWrite  = Flipped(Decoupled(new SDirWrite))
  val dirResp   = Decoupled(new SDirResp)

  // TODO: update replacer SRAM

  val resetFinish = Output(Bool())
})

  // TODO: Delete the following code when the coding is complete
  io.dirRead <> DontCare
  io.dirWrite <> DontCare
  io.dirResp <> DontCare
  io.resetFinish <> DontCare
  dontTouch(io)
  io.dirRead.ready := true.B

// --------------------- Modules and SRAM declaration ------------------------//
  val metaArray = Module(new SRAMTemplate(new SDirMetaEntry, dsuparam.sets / dsuparam.nrSelfDirBank, dsuparam.ways,
    singlePort = true, hasClkGate = dsuparam.enableSramClockGate, clk_div_by_2 = false))

  metaArray.io <> DontCare


// --------------------- Reg/Wire declaration ------------------------//
  val respValid_s2 = RegNext(io.dirRead.valid)
  val respValid = RegInit(false.B)



// -----------------------------------------------------------------------------------------
// Stage 1 (dir read) / (dir write)
// -----------------------------------------------------------------------------------------


// -----------------------------------------------------------------------------------------
// Stage 2(dir read)
// -----------------------------------------------------------------------------------------


// -----------------------------------------------------------------------------------------
// Stage 3(dir read)
// -----------------------------------------------------------------------------------------
  respValid := Mux(respValid, !io.dirResp.fire, respValid_s2)
  io.dirResp.valid := respValid
  io.dirResp.bits := 0.U.asTypeOf(io.dirResp.bits)

// -----------------------------------------------------------------------------------------
// Stage 4(dir resp)
// -----------------------------------------------------------------------------------------

}