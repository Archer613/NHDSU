package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class MainPipe()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // Task From Arb
    val arbTask     = Flipped(Decoupled(new TaskBundle))
    // Lock signals to Arb
    val lockAddr    = ValidIO(new Bundle {
      val set         = UInt(setBits.W)
      val tag         = UInt(tagBits.W)
    })
    val lockWay     = ValidIO(new Bundle {
      val wayOH       = UInt(dsuparam.ways.W)
      val set         = UInt(setBits.W)
    })
    // Resp/Write Directory
    val dirWrite    = Decoupled(new DirWrite)
    val dirResp     = Flipped(Decoupled(new DirResp))
    // Req to DataStorage
    val dsReq       = Decoupled(new DSRequest())
    // Task to snpCtrl
    val snpTask     = Decoupled(new TaskBundle())
    // Resp to CpuSlave
    val cpuResp     = Decoupled(new TaskRespBundle())
    // Task to Master
    val msTask      = Decoupled(new TaskBundle())
    // Req to dataBuffer
    val dbReq       = ValidIO(new DBReq())
  })

  // TODO: Delete the following code when the coding is complete
  io.arbTask <> DontCare
  io.lockAddr <> DontCare
  io.lockWay <> DontCare
  io.dirWrite <> DontCare
  io.dirResp <> DontCare
  io.dsReq <> DontCare
  io.snpTask <> DontCare
  io.msTask <> DontCare
  io.cpuResp <> DontCare
  io.dbReq <> DontCare


// --------------------- Modules declaration ------------------------//




// --------------------- Wire declaration ------------------------//


// --------------------- Connection ------------------------//




}