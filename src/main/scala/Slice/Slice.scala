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
    val snpTask = Vec(dsuparam.nrCore, Decoupled(new TaskBundle()))
    val snpResp = Vec(dsuparam.nrCore, Flipped(ValidIO(new TaskRespBundle())))
    // mainpipe <-> cpuslave
    val cpuTask = Vec(dsuparam.nrCore, Flipped(Decoupled(new TaskBundle())))
    val cpuResp = Vec(dsuparam.nrCore, ValidIO(new TaskRespBundle()))
    // dataBuffer <-> CPUSLAVE + DSUMASTER
    val dbCrtl = Vec(dsuparam.nrCore + 1, Flipped(new DBCtrlBundle()))
    // mainpipe <-> DSUMASTER
    val msTask = Flipped(Decoupled(new TaskBundle()))
    val msResp = Decoupled(new TaskRespBundle())
  })

  // TODO: Delete the following code when the coding is complete
  io.snpTask <> DontCare
  io.snpResp <> DontCare
  io.cpuTask <> DontCare
  io.cpuResp <> DontCare
  io.dbCrtl <> DontCare
  io.msTask <> DontCare
  io.msResp <> DontCare


// --------------------- Modules declaration ------------------------//


// --------------------- Wire declaration ------------------------//


// --------------------- Connection ------------------------//




}