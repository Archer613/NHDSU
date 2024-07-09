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
    val dbSigs2DB     = new DsDBBundle()
    // rcReq to dataBuffer
    val dbRCReq = ValidIO(new DBRCReq())
  })

  // TODO: Delete the following code when the coding is complete
  io.mpReq <> DontCare
  io.dbSigs2DB <> DontCare
  io.dbRCReq <> DontCare

// --------------------- Modules declaration ------------------------//




// --------------------- Wire declaration ------------------------//


// --------------------- Connection ------------------------//




}