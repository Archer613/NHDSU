package NHDSU

import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import scala.collection.immutable.ListMap
import scala.math.{max, min}

object IdL0 {
    val width      = 3
    val SLICE      = "b000".U
    val CPU        = "b001".U
    val CMO        = "b010".U
    val AXI        = "b011".U
    val MASTER     = "b100".U
}

trait HasIDBits extends DSUBundle { this: Bundle =>
    val idL0 = UInt(IdL0.width.W)
    val idL1 = UInt(max(coreIdBits, bankBits).W)
    val idL2 = UInt(max(reqBufIdBits, snoopCtlIdBits).W)
}

class TaskBundle(implicit p: Parameters) extends DSUBundle with HasIDBits{
    // TODO: TaskBundle
    val opcode      = UInt(5.W)
    val set         = UInt(setBits.W)
    val tag         = UInt(tagBits.W)
    val isWB        = Bool()
}


class TaskRespBundle(implicit p: Parameters) extends DSUBundle with HasIDBits{
    // TODO: TaskRespBundle
    val opcode      = UInt(5.W)
    val set = UInt(setBits.W)
    val tag = UInt(tagBits.W)
}


// ---------------------- DataBuffer Bundle ------------------- //
object DBOp {
    val width        = 2
    val Write        = "b01".U // Need Resp
    val Read         = "b10".U // Not Need Resp
    val Clean        = "b11".U // Not Nedd Resp
}
class DBReq(implicit p: Parameters) extends DSUBundle with HasIDBits{
    val dbOp = UInt(DBOp.width.W)
    val dbid = UInt(dbIdBits.W)
}
class DBResp(implicit p: Parameters) extends DSUBundle with HasIDBits {
    val dbid = UInt(dbIdBits.W)
}
class DBOutData(beat: Int = 1)(implicit p: Parameters) extends DSUBundle with HasIDBits{
    val dbid = UInt(dbIdBits.W)
    val data = UInt((beatBits*beat).W)
}
class DBInData(beat: Int = 1)(implicit p: Parameters) extends DSUBundle with HasIDBits{
    val dbid = UInt(dbIdBits.W)
    val data = UInt((beatBits*beat).W)
}




