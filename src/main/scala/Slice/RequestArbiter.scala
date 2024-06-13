package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class RequestArbiter()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // SnoopCtl task
    val taskSnp       = Flipped(Decoupled(new TaskBundle)) // Hard wire to MSHRs

    // CHI Channel task
    val taskCpu       = Flipped(Decoupled(new TaskBundle))
    val taskMs        = Flipped(Decoupled(new TaskBundle))

    // Send task to MainPipe
    val mpTask        = Decoupled(new TaskBundle)
    // Lock signals from MainPipe
    val lockAddr      = Flipped(ValidIO(new Bundle {
      val set           = UInt(setBits.W)
      val tag           = UInt(tagBits.W)
    }))
    val lockWay       = Flipped(ValidIO(new Bundle {
      val wayOH         = UInt(dsuparam.ways.W)
      val set           = UInt(setBits.W)
    }))
    // Read directory
    val dirRead = Decoupled(new DirRead)
    // Directory reset finish
    val dirRstFinish  = Input(Bool())
    // Lock Signal from TxReqQueue
    val txReqQFull    = Input(Bool())
  })

  // TODO: Delete the following code when the coding is complete
  io.taskSnp <> DontCare
  io.taskCpu <> DontCare
  io.taskMs <> DontCare
  io.dirRead <> DontCare
  io.mpTask <> DontCare
  io.dirRstFinish <> DontCare


// --------------------- Modules declaration ------------------------//




// --------------------- Wire declaration ------------------------//


// --------------------- Connection ------------------------//




}