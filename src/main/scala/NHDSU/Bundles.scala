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

class IDBundle(implicit p: Parameters) extends DSUBundle {
    val idL0 = UInt(IdL0.width.W) // Module: IDL0
    val idL1 = UInt(max(coreIdBits, bankBits).W) // SubModule: CpuSlaves, Slices
    val idL2 = UInt(max(max(reqBufIdBits, snoopCtlIdBits), dbIdBits).W) // SubSubModule: ReqBufs, SnpCtls, dbid
}

trait HasFromIDBits extends DSUBundle { this: Bundle => val from = new IDBundle() }

trait HasToIDBits extends DSUBundle { this: Bundle => val to = new IDBundle() }

trait HasIDBits extends DSUBundle with HasFromIDBits with HasToIDBits

class TaskBundle(implicit p: Parameters) extends DSUBundle with HasIDBits with HasCHIChannel{
    // constants related to CHI
    def tgtID       = 0.U(chiBundleParams.nodeIdBits.W)
    def srcID       = 0.U(chiBundleParams.nodeIdBits.W)
    // value in use
    val opcode      = UInt(5.W)
    val addr        = UInt(addressBits.W)
    val isR         = Bool()
    val isWB        = Bool() // write back
    val isClean     = Bool()
    val readDir     = Bool()
    val wirteSDir   = Bool()
    val wirteCDir   = Bool()
    val wirteDS     = Bool()
    val btWay       = UInt(blockWayBits.W) // block table
}


class TaskRespBundle(implicit p: Parameters) extends DSUBundle with HasIDBits with HasCHIChannel{
    // TODO: TaskRespBundle
    val opcode      = UInt(5.W)
    val addr        = UInt(addressBits.W)
}


// ---------------------- DataBuffer Bundle ------------------- //
object DBState {
    val width       = 3
    val FREE        = "b000".U
    val ALLOC       = "b001".U
    val WRITE_B0    = "b010".U // Has been written beat 0
    val WRITE_B1    = "b011".U // Has been written beat 1
    val READ_B0     = "b100".U // Has been read beat 0
    val READ_B1     = "b101".U // Has been read beat 1
}
class DBEntry(implicit p: Parameters) extends DSUBundle {
    val state = UInt(DBState.width.W)
    val beat0 = UInt(beatBits.W)
    val beat1 = UInt(beatBits.W)
}
trait HasDBRCOp extends DSUBundle { this: Bundle =>
    val isRead = Bool()
    val isClean = Bool()
}
class DBRCReq(implicit p: Parameters) extends DSUBundle with HasIDBits with HasDBRCOp   // DataBuffer Read Req
class DBWReq(implicit p: Parameters) extends DSUBundle with HasIDBits                   // DataBuffer Write Req
class DBWResp(implicit p: Parameters) extends DSUBundle with HasIDBits                  // DataBuffer Write Resp
class DBOutData(beat: Int = 1)(implicit p: Parameters) extends DSUBundle with HasToIDBits {
    val data = UInt((beatBits*beat).W)
}
class DBInData(beat: Int = 1)(implicit p: Parameters) extends DSUBundle with HasToIDBits {
    val data = UInt((beatBits*beat).W)
}
class DBBundle(beat: Int = 1)(implicit p: Parameters) extends DSUBundle {
    val rcReq       = Decoupled(new DBRCReq())
    val wReq        = Decoupled(new DBWReq())
    val wResp       = Flipped(Decoupled(new DBWResp()))
    val dataFDB     = Flipped(Decoupled(new DBOutData(beat)))
    val dataTDB     = Decoupled(new DBInData(beat))
}


// ---------------------- ReqBuf Bundle ------------------- //
class RBFSMState(implicit p: Parameters) extends Bundle {
    // schedule
    val s_rReq2mp = Bool()
    val s_wReq2mp = Bool()

    // wait
    val w_rResp = Bool()
}


// --------------------- ReqArb Bundle ------------------- //
class BlockTableEntry(implicit p: Parameters) extends DSUBundle {
    val valid   = Bool()
    val tag     = UInt(blockTagBits.W)
    // TODO: block by way full
}


// -------------------- ReadCtl Bundle ------------------ //
object ReadState {
    val width      = 3
    val FREE       = "b000".U
    val GET_ID     = "b001".U
    val WAIT_ID    = "b010".U
    val SEND_REQ   = "b011".U
    val WAIT_RESP  = "b100".U
}

class ReadCtlTableEntry(implicit p: Parameters) extends DSUBundle with HasFromIDBits {
    val state = UInt(ReadState.width.W)
    val txnid = UInt(8.W)
    val addr = UInt(addressBits.W)
}





