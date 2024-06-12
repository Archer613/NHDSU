package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils._

class Slice()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // snpCtrl <-> cpuslave
    val snpTask       = Decoupled(new TaskBundle())
    val snpResp       = Flipped(ValidIO(new TaskRespBundle()))
    // mainpipe <-> cpuslave
    val cpuTask       = Flipped(Decoupled(new TaskBundle()))
    val cpuResp       = Decoupled(new TaskRespBundle())
    // dataBuffer <-> CPUSLAVE
    val dbSigs2Cpu    = new Bundle {
      val req           = Flipped(ValidIO(new DBReq()))
      val wResp         = Decoupled(new DBResp())
      val dataFromDB    = Decoupled(new DBOutData())
      val dataToDB       = Flipped(ValidIO(new DBInData()))
    }
    // dataBuffer <-> DSUMASTER
    val dbSigs2Ms     = new Bundle {
      val req           = Flipped(ValidIO(new DBReq()))
      val wResp         = Decoupled(new DBResp())
      val dataFromDB    = Decoupled(new DBOutData())
      val dataToDB      = Flipped(ValidIO(new DBInData()))
    }
    val msTask        = Flipped(Decoupled(new TaskBundle()))
    val msResp        = Decoupled(new TaskRespBundle())
  })

  // TODO: Delete the following code when the coding is complete
  io.snpTask <> DontCare
  io.snpResp <> DontCare
  io.cpuTask <> DontCare
  io.cpuResp <> DontCare
  io.dbSigs2Cpu <> DontCare
  io.dbSigs2Ms <> DontCare
  io.msTask <> DontCare
  io.msResp <> DontCare


// --------------------- Modules declaration ------------------------//




// --------------------- Wire declaration ------------------------//


// --------------------- Connection ------------------------//




}