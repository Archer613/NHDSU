package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class SnoopCtlWrapper()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // snpCtrl <-> cpuslave
    val snpTask       = Decoupled(new TaskBundle())
    val snpResp       = Flipped(ValidIO(new TaskRespBundle()))
    // mainpipe <-> snpCtrl
    val mpTask        = Flipped(Decoupled(new TaskBundle()))
    val mpResp        = Decoupled(new TaskBundle())
  })

  // TODO: Delete the following code when the coding is complete
  io.snpTask <> DontCare
  io.snpResp <> DontCare
  io.mpTask <> DontCare
  io.mpResp <> DontCare


// --------------------- Modules declaration ------------------------//




// --------------------- Wire declaration ------------------------//


// --------------------- Connection ------------------------//




}