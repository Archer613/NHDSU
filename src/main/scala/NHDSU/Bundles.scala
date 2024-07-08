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
    val opcode      = UInt(6.W)
    val addr        = UInt(addressBits.W)
    val dbid        = UInt(dbIdBits.W)
    val isR         = Bool()
    val isWB        = Bool() // write back
    val isClean     = Bool()
    val readDir     = Bool()
    val wirteSDir   = Bool()
    val wirteCDir   = Bool()
    val btWay       = UInt(blockWayBits.W) // block table
}


class RespBundle(implicit p: Parameters) extends DSUBundle with HasIDBits with HasCHIChannel{
    // TODO: RespBundle
    val opcode      = UInt(6.W)
    val resp        = UInt(3.W)
    val addr        = UInt(addressBits.W)
    val btWay       = UInt(blockWayBits.W) // block table
}


// ---------------------- DataBuffer Bundle ------------------- //
object DBState {
    val width       = 3
    val FREE        = "b000".U
    val ALLOC       = "b001".U
    val WRITTING    = "b010".U // Has been written some beats
    val WRITE_DONE  = "b011".U // Has been written all beats
    val READING     = "b100".U // Has been read some beat
    val READ_DONE   = "b101".U // Has been read all beat
}
class DBEntry(implicit p: Parameters) extends DSUBundle {
    val state       = UInt(DBState.width.W)
    val beatVals    = Vec(nrBeat, Bool())
    val beats       = Vec(nrBeat, UInt(beatBits.W))
}
trait HasDBRCOp extends DSUBundle { this: Bundle =>
    val isRead = Bool()
    val isClean = Bool()
}
// CPU DB Bundle
class DBRCReq(implicit p: Parameters) extends DSUBundle with HasIDBits with HasDBRCOp       // DataBuffer Read Req
class DBWReq(implicit p: Parameters) extends DSUBundle with HasIDBits                       // DataBuffer Write Req
class DBWResp(implicit p: Parameters) extends DSUBundle with HasIDBits                      // DataBuffer Write Resp
class DBOutData(beat: Int = 1)(implicit p: Parameters) extends DSUBundle with HasToIDBits {
    val data = UInt((beatBits*beat).W)
    val dataID = UInt(2.W)
    def beatNum: UInt = {
        if (dataBits / beatBits*beat == 1) { 0.U }
        else if(dataBits / beatBits*beat == 2) { OHToUInt(dataID) }
        else { dataID }
    }
}
class DBInData(beat: Int = 1)(implicit p: Parameters) extends DSUBundle with HasToIDBits {
    val data = UInt((beatBits*beat).W)
    val dataID = UInt(2.W)
    def beatNum: UInt = {
        if (dataBits / beatBits * beat == 1) { 0.U }
        else if (dataBits / beatBits * beat == 2) { OHToUInt(dataID) }
        else { dataID }
    }
}
class DBBundle(beat: Int = 1)(implicit p: Parameters) extends DSUBundle {
    val rcReq       = Decoupled(new DBRCReq())
    val wReq        = Decoupled(new DBWReq())
    val wResp       = Flipped(Decoupled(new DBWResp()))
    val dataFDB     = Flipped(Decoupled(new DBOutData(beat)))
    val dataTDB     = Decoupled(new DBInData(beat))
}
// TODO: Simplifying the Bundle for MASTER and DS
// TODO: MASTER DB Bundle
// TODO: DS DB Bundle


// ---------------------- ReqBuf Bundle ------------------- //
class RBFSMState(implicit p: Parameters) extends Bundle {
    // schedule
    val s_rReq2mp = Bool()
    val s_wReq2mp = Bool()
    val s_rResp   = Bool()

    // wait
    val w_rResp = Bool()
    val w_data  = Bool()
    val w_compAck = Bool()
}


// --------------------- ReqArb Bundle ------------------- //
class BlockTableEntry(implicit p: Parameters) extends DSUBundle {
    val valid   = Bool()
    val tag     = UInt(blockTagBits.W)
    // TODO: block by way full
}


// -------------------- ReadCtl Bundle ------------------ //
object RCState { // Read Ctl State
    val width      = 3
    val nrState    = 6
    val FREE       = "b000".U
    val GET_ID     = "b001".U
    val WAIT_ID    = "b010".U
    val SEND_REQ   = "b011".U
    val WAIT_RESP  = "b100".U
    val SEND_RESP  = "b101".U
}

class ReadCtlTableEntry(implicit p: Parameters) extends DSUBundle with HasFromIDBits {
    val opcode  = UInt(6.W)
    val state   = UInt(RCState.width.W)
    val txnid   = UInt(8.W)
    val addr    = UInt(addressBits.W)
    val btWay   = UInt(blockWayBits.W) // block table
}





