package NHDSU

import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import scala.collection.immutable.ListMap
import scala.math.{max, min}

object Tag {
    val width      = 3
    val SLICE      = "b000".U
    val CPU        = "b001".U
    val CMO        = "b010".U
    val AXI        = "b011".U
    val MASTER     = "b100".U
}

//trait HasTagBits extends DSUBundle { this: Bundle =>
//    val sourceL0 = UInt(Tag.width.W)
//    val sourceL1 = UInt(max(coreIdBits, bankBits).W)
//    val sourceL2 = UInt(max(reqBufIdBits, snoopCtlIdBits).W)
//}

class IdBundle(implicit p: Parameters) extends DSUBundle{
    val l0 = UInt(Tag.width.W)
    val l1 = UInt(max(coreIdBits, bankBits).W)
    val l2 = UInt(max(reqBufIdBits, snoopCtlIdBits).W)
}

class TaskBundle(implicit p: Parameters) extends DSUBundle{
    val opcode      = UInt(5.W)
    val id          = new IdBundle()
    val bank        = UInt(bankBits.W)
    val set         = UInt(setBits.W)
    val tag         = UInt(tagBits.W)
}

// TODO: TaskRespBundle
class TaskRespBundle(implicit p: Parameters) extends DSUBundle{
    val id          = new IdBundle()
}

object DBOp {
    val width      = 2
    val Write        = "b01".U // Need Resp
    val Read         = "b10".U // Not Need Resp
    val Clean        = "b11".U // Not Nedd Resp
}




// ---------------------- DataBuffer Bundle ------------------- //
class DBReq(implicit p: Parameters) extends DSUBundle{
    val dbOp = UInt(DBOp.width.W)
    val dbid = UInt(dbIdBits.W)
    val id = new IdBundle()
}
class DBResp(implicit p: Parameters) extends DSUBundle{
    val id = new IdBundle()
    val dbid = UInt(dbIdBits.W)
}
class DBOutData(implicit p: Parameters) extends DSUBundle{
    val id = new IdBundle()
    val data = UInt(beatBits.W)
}
class DBInData(implicit p: Parameters) extends DSUBundle{
    val dbid = UInt(dbIdBits.W)
    val data = UInt(beatBits.W)
}
class DBCtrlBundle(implicit p: Parameters) extends DSUBundle{
    val req = ValidIO(new DBReq())
    val wResp = Flipped(ValidIO(new DBResp()))
    val dataFromDB = Flipped(ValidIO(new DBOutData()))
    val dataToDB = ValidIO(new DBInData())
}




