package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class SnoopCtl()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val snpId         = Input(UInt(snoopCtlIdBits.W))
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


// --------------------- Modules declaration ------------------------//




// --------------------- Reg / Wire declaration ------------------------//


// --------------------- Connection ------------------------//




}