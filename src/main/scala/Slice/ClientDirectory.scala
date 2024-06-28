package NHDSU.SLICE

import NHDSU._
import _root_.NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate

class CDirMetaEntry(implicit p: Parameters) extends DSUBundle {
  val tag          = UInt(cTagBits.W)
  val bank         = UInt(bankBits.W)
  val metas        = Vec(dsuparam.nrCore, new CHIStateBundle())
}

class CDirRead(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasClientAddrBits {
  override def useAddrVal: Boolean = useAddr
  val mes = new DirReadBase(dsuparam.clientWays)
}

class CDirWrite(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasClientAddrBits {
  override def useAddrVal: Boolean = useAddr
  val wayOH = UInt(dsuparam.clientWays.W)
  val metas = Vec(dsuparam.nrCore, new CHIStateBundle())
}

class CDirResp(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasClientAddrBits {
  override def useAddrVal: Boolean = useAddr
  val wayOH = UInt(dsuparam.clientWays.W)
  val metas = Vec(dsuparam.nrCore, new CHIStateBundle())
  val hitVec = Vec(dsuparam.nrCore, Bool())
}

class ClientDirectory()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead   = Flipped(Decoupled(new CDirRead))
  val dirWrite  = Flipped(Decoupled(new CDirWrite))
  val dirResp   = Decoupled(new CDirResp)

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
  val metaArray = Module(new SRAMTemplate(new CDirMetaEntry, dsuparam.sets, dsuparam.ways,
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