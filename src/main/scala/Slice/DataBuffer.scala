package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class DataBuffer()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
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
      val wResp         = ValidIO(new DBResp())
      val dataFromDB    = Decoupled(new DBOutData())
      val dataToDB      = Flipped(ValidIO(new DBInData()))
    }
    // dataBuffer <-> DataStorage
    val dbSigs2DS     = new Bundle {
      val req           = Flipped(ValidIO(new DBReq()))
      val wResp         = ValidIO(new DBResp())
      val dataFromDB    = ValidIO(new DBOutData(beat = nrBeat))
      val dataToDB      = Flipped(ValidIO(new DBInData(beat = nrBeat)))
    }
    // dataBuffer <-> MainPipe
    val mpReq           = Flipped(ValidIO(new DBReq()))
  })

  // TODO: Delete the following code when the coding is complete
  io.dbSigs2Cpu <> DontCare
  io.dbSigs2Ms <> DontCare
  io.dbSigs2DS <> DontCare
  io.mpReq <> DontCare

// --------------------- Modules declaration ------------------------//




// --------------------- Wire declaration ------------------------//




// --------------------- Connection ------------------------//




}