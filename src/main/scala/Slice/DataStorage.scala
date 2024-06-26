package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class DSRequest(implicit p: Parameters) extends DSUBundle with HasIDBits {
  val addr = UInt(addressBits.W)
  val wen = Bool()
}


class DataStorage()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // Req From MainPipe
    val mpReq         = Flipped(Decoupled(new DSRequest()))
    // dataBuffer <-> DataStorage
    val dbSigs2DB     = new Bundle {
      val req           = ValidIO(new DBReq())
      val wResp         = Flipped(ValidIO(new DBResp()))
      val dataFromDB    = Flipped(ValidIO(new DBOutData(beat = nrBeat)))
      val dataToDB      = ValidIO(new DBInData(beat = nrBeat))
    }
  })

  // TODO: Delete the following code when the coding is complete
  io.mpReq <> DontCare
  io.dbSigs2DB <> DontCare


// --------------------- Modules declaration ------------------------//




// --------------------- Wire declaration ------------------------//


// --------------------- Connection ------------------------//




}