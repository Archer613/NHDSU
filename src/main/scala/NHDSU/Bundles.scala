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
    val idL0 = UInt(IdL0.width.W) // Module: IDL0 [3.W]
    val idL1 = UInt(max(coreIdBits, bankBits).W) // SubModule: CpuSlaves, Slices [max:2.W]
    val idL2 = UInt(max(reqBufIdBits, snoopCtlIdBits).W) // SubSubModule: ReqBufs, SnpCtls [max:4.W]
}

trait HasFromIDBits extends DSUBundle { this: Bundle => val from = new IDBundle() }

trait HasToIDBits extends DSUBundle { this: Bundle => val to = new IDBundle() }

trait HasIDBits extends DSUBundle with HasFromIDBits with HasToIDBits

trait HasDBID extends DSUBundle { this: Bundle => val dbid = UInt(dbIdBits.W) }

class TaskBundle(implicit p: Parameters) extends DSUBundle with HasIDBits with HasCHIChannel with HasDBID {
    // constants related to CHI
    def tgtID       = 0.U(chiBundleParams.nodeIdBits.W)
    def srcID       = 0.U(chiBundleParams.nodeIdBits.W)
    // value in use
    val opcode      = UInt(6.W)
    val addr        = UInt(addressBits.W)
    val snpResp     = UInt(3.W) // resp.width = 3
    val isWB        = Bool() // write back
    val cleanBt     = Bool() // clean block table in ReqArb
    val writeBt     = Bool() // write block table in ReqArb
    val readDir     = Bool()
    val btWay       = UInt(blockWayBits.W) // block table
}


class RespBundle(implicit p: Parameters) extends DSUBundle with HasIDBits with HasCHIChannel{
    // TODO: RespBundle
    val opcode      = UInt(6.W)
    val resp        = UInt(3.W)
    val addr        = UInt(addressBits.W) // TODO: Del it
    val btWay       = UInt(blockWayBits.W) // block table
}


// ---------------------- DataBuffer Bundle ------------------- //
object DBState {
    val width       = 3
    // FREE -> ALLOC -> WRITTING -> WRITE_DONE -> READING(needClean) -> FREE
    // FREE -> ALLOC -> WRITTING -> WRITE_DONE -> READING(!needClean) -> READ_DONE -> READING(needClean) -> FREE
    val FREE        = "b000".U
    val ALLOC       = "b001".U
    val WRITTING    = "b010".U // Has been written some beats
    val WRITE_DONE  = "b011".U // Has been written all beats
    val READING     = "b100".U // Ready to read or already partially read
    val READ_DONE   = "b101".U // Has been read all beat
}
class DBEntry(implicit p: Parameters) extends DSUBundle with HasToIDBits {
    val state       = UInt(DBState.width.W)
    val beatVals    = Vec(nrBeat, Bool())
    val beatRNum    = UInt(log2Ceil(nrBeat).W)
    val needClean   = Bool()
    val beats       = Vec(nrBeat, UInt(beatBits.W))

    def getBeat     = beats(beatRNum)
    def toDataID: UInt = {
        if (nrBeat == 1) { 0.U }
        else if (nrBeat == 2) { Mux(beatRNum === 0.U, "b00".U, "b10".U) }
        else { beatRNum }
    }
}
trait HasDBRCOp extends DSUBundle { this: Bundle =>
//    val isRead = Bool() // Default Read
    val isClean = Bool()
}
// Base Data Bundle
trait HasDBData extends DSUBundle { this: Bundle =>
    val data = UInt(beatBits.W)
    val dataID = UInt(2.W)
    def beatNum: UInt = {
        if (nrBeat == 1) { 0.U }
        else if (nrBeat == 2) { Mux(dataID === 0.U, 0.U, 1.U) }
        else { dataID }
    }
}
// DataBuffer Read/Clean Req
class DBRCReq(implicit p: Parameters) extends DSUBundle with HasDBRCOp with HasDBID with HasToIDBits

// CPU Bundle
class CpuDBWReq(implicit p: Parameters) extends DSUBundle with HasIDBits                                // CPU  ---[wReq] ---> DB
class CpuDBWResp(implicit p: Parameters) extends DSUBundle with HasIDBits with HasDBID                  // DB   ---[wResp] --> CPU
class CpuDBOutData(implicit p: Parameters) extends DSUBundle with HasDBData with HasToIDBits            // DB   ---[Data] ---> CPU
class CpuDBInData(implicit p: Parameters) extends DSUBundle with HasDBData with HasIDBits with HasDBID  // CPU  ---[Data] ---> DB

class CpuDBBundle(implicit p: Parameters) extends DSUBundle {
    val wReq        = Decoupled(new CpuDBWReq)                      // from[CPU][coreID][reqBufID];     to[SLICE][sliceId][dontCare];
    val wResp       = Flipped(Decoupled(new CpuDBWResp))            // from[SLICE][sliceId][dontCare];  to[CPU][coreID][reqBufID];      hasDBID
    val dataFDB     = Flipped(Decoupled(new CpuDBOutData))          // from[None];                      to[CPU][coreID][reqBufID];
    val dataTDB     = Decoupled(new CpuDBInData)                    // from[None];                      to[SLICE][sliceId][dontCare];   hasDBID
}

// MASTER Bundle
class MsDBWReq(implicit p: Parameters) extends DSUBundle                                        // CPU  ---[wReq] ---> DB
class MsDBWResp(implicit p: Parameters) extends DSUBundle with HasDBID                          // DB   ---[wResp] --> CPU
class MsDBOutData(implicit p: Parameters) extends DSUBundle with HasDBData                      // DB   ---[Data] ---> CPU
class MsDBInData(implicit p: Parameters) extends DSUBundle with HasDBData with HasDBID          // CPU  ---[Data] ---> DB

class MsDBBundle(implicit p: Parameters) extends DSUBundle {
    val wReq        = Decoupled(new MsDBWReq)                        // from[None];  to[None];
    val wResp       = Flipped(Decoupled(new MsDBWResp))              // from[None];  to[None];   hasDBID
    val dataFDB     = Flipped(Decoupled(new MsDBOutData))            // from[None];  to[None];
    val dataTDB     = Decoupled(new MsDBInData)                      // from[None];  to[None];   hasDBID
}

// DS Bundle
class DsDBWReq(implicit p: Parameters) extends DSUBundle                                                // CPU  ---[wReq] ---> DB
class DsDBWResp(implicit p: Parameters) extends DSUBundle with HasDBID                                  // DB   ---[wResp] --> CPU
class DsDBOutData(implicit p: Parameters) extends DSUBundle with HasDBData                              // DB   ---[Data] ---> CPU
class DsDBInData(implicit p: Parameters) extends DSUBundle with HasDBData with HasToIDBits with HasDBID // CPU  ---[Data] ---> DB

class DsDBBundle(beat: Int = 1)(implicit p: Parameters) extends DSUBundle {
    val wReq        = Decoupled(new DsDBWReq)                        // from[None];  to[None];
    val wResp       = Flipped(Decoupled(new DsDBWResp))              // from[None];  to[None];   hasDBID
    val dataFDB     = Flipped(Decoupled(new DsDBOutData))            // from[None];  to[None];
    val dataTDB     = Decoupled(new DsDBInData)                      // from[None];  to[CPU/MASTER][coreID/dontCare][reqBufID/dontCare];    hasDBID
}


// ---------------------- ReqBuf Bundle ------------------- //
class RBFSMState(implicit p: Parameters) extends Bundle {
    // schedule
    val s_req2mp    = Bool() // expect write back req
    val s_wbReq2mp  = Bool()
    val s_resp      = Bool()
    val s_wbResp    = Bool()
    val s_clean     = Bool()

    // wait
    val w_mpResp    = Bool()
    val w_data      = Bool()
    val w_compAck   = Bool()
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





