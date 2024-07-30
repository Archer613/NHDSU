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
    val idL2 = UInt(max(reqBufIdBits, max(snoopCtlIdBits, dbIdBits)).W) // SubSubModule: ReqBufs, SnpCtls, DataBufs [max:4.W]

    def isSLICE  = idL0 === IdL0.SLICE
    def isCPU    = idL0 === IdL0.CPU
    def isCMO    = idL0 === IdL0.CMO
    def isAXI    = idL0 === IdL0.AXI
    def isMASTER = idL0 === IdL0.MASTER
}

trait HasFromIDBits extends DSUBundle { this: Bundle => val from = new IDBundle() }

trait HasToIDBits extends DSUBundle { this: Bundle => val to = new IDBundle() }

trait HasIDBits extends DSUBundle with HasFromIDBits with HasToIDBits

trait HasDBID extends DSUBundle { this: Bundle => val dbid = UInt(dbIdBits.W) }

class TaskBundle(implicit p: Parameters) extends DSUBundle with HasIDBits with HasDBID {
    // constants related to CHI
    def tgtID       = 0.U(chiBundleParams.nodeIdBits.W)
    def srcID       = 0.U(chiBundleParams.nodeIdBits.W)
    // value in use
    val opcode      = UInt(6.W)
    val addr        = UInt(addressBits.W)
    val resp        = UInt(3.W) // snpResp or wbReq; resp.width = 3
    val isWB        = Bool() // write back
    val isSnpHlp    = Bool() // req is from snoop helper
    val writeBt     = Bool() // write block table in ReqArb
    val readDir     = Bool()
    val btWay       = UInt(blockWayBits.W) // block table
    val willSnp     = Bool()
    val snpHasData  = Bool()
}

class RespBundle(implicit p: Parameters) extends DSUBundle with HasToIDBits with HasCHIChannel{
    val opcode      = UInt(6.W)
    val resp        = UInt(3.W)
    val addr        = UInt(addressBits.W) // TODO: Del it
    val btWay       = UInt(blockWayBits.W) // block table
    val cleanBt     = Bool()
}

trait HasSnpTask extends DSUBundle { this: Bundle =>
    val opcode          = UInt(6.W)
    val addr            = UInt(addressBits.W)
    val snpDoNotGoToSD  = Bool()
    val snpRetToSrc     = Bool()
}

class SnpTaskBundle(implicit p: Parameters) extends DSUBundle with HasSnpTask with HasIDBits
class MpSnpTaskBundle(implicit p: Parameters) extends DSUBundle with HasSnpTask with HasFromIDBits {
    val srcOp       = UInt(6.W)
    val hitVec      = Vec(dsuparam.nrCore, Bool())
    val isSnpHlp    = Bool()
    val btWay       = UInt(blockWayBits.W) // block table

}

class SnpRespBundle(implicit p: Parameters) extends DSUBundle with HasIDBits with HasDBID {
    val resp    = UInt(3.W) // snpResp; resp.width = 3
    val hasData = Bool()
}

// ---------------------- DataBuffer Bundle ------------------- //
object DBState {
    val width       = 3
    // FREE -> ALLOC -> WRITING -> WRITE_DONE -> FREE
    // FREE -> ALLOC -> WRITING -> WRITE_DONE -> READING(needClean) -> READ(needClean) -> FREE
    // FREE -> ALLOC -> WRITING -> WRITE_DONE -> READING(!needClean) -> READ(!needClean) -> READ_DONE -> READING(needClean) -> READ(needClean) -> FREE
    val FREE        = "b000".U
    val ALLOC       = "b001".U
    val WRITTING    = "b010".U // Has been written some beats
    val WRITE_DONE  = "b011".U // Has been written all beats
    val READ        = "b100".U // Ready to read
    val READING     = "b101".U // Already partially read
    val READ_DONE   = "b110".U // Has been read all beat
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
    val isRead = Bool()
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
    def isLast: Bool = beatNum === (nrBeat - 1).U
}
// DataBuffer Read/Clean Req
class DBRCReq(implicit p: Parameters) extends DSUBundle with HasDBRCOp with HasDBID with HasToIDBits

// CPU Bundle
class CpuDBWReq(implicit p: Parameters) extends DSUBundle with HasIDBits                                  // CPU  ---[wReq] ---> DB
class CpuDBWResp(implicit p: Parameters) extends DSUBundle with HasIDBits with HasDBID                    // DB   ---[wResp] --> CPU
class CpuDBOutData(implicit p: Parameters) extends DSUBundle with HasDBData with HasToIDBits              // DB   ---[Data] ---> CPU
class CpuDBInData(implicit p: Parameters) extends DSUBundle with HasDBData with HasToIDBits with HasDBID  // CPU  ---[Data] ---> DB

class CpuDBBundle(implicit p: Parameters) extends DSUBundle {
    val wReq        = Decoupled(new CpuDBWReq)                      // from[CPU][coreID][reqBufID];     to[SLICE][sliceId][dontCare];
    val wResp       = Flipped(Decoupled(new CpuDBWResp))            // from[SLICE][sliceId][dontCare];  to[CPU][coreID][reqBufID];      hasDBID
    val dataFDB     = Flipped(Decoupled(new CpuDBOutData))          // from[None];                      to[CPU][coreID][reqBufID];
    val dataTDB     = Decoupled(new CpuDBInData)                    // from[None];                      to[SLICE][sliceId][dontCare];   hasDBID
}

// MASTER Bundle
class MsDBWReq(implicit p: Parameters) extends DSUBundle                                        // MS  ---[wReq] ---> DB
class MsDBWResp(implicit p: Parameters) extends DSUBundle with HasDBID                          // DB  ---[wResp] --> MS
class MsDBOutData(implicit p: Parameters) extends DSUBundle with HasDBData with HasDBID         // DB  ---[Data] ---> MS
class MsDBInData(implicit p: Parameters) extends DSUBundle with HasDBData with HasDBID          // MS  ---[Data] ---> DB

class MsDBBundle(implicit p: Parameters) extends DSUBundle {
    val wReq        = Decoupled(new MsDBWReq)                        // from[None];  to[None];
    val wResp       = Flipped(Decoupled(new MsDBWResp))              // from[None];  to[None];   hasDBID
    val dataFDB     = Flipped(Decoupled(new MsDBOutData))            // from[None];  to[None];   hasDBID
    val dataTDB     = Decoupled(new MsDBInData)                      // from[None];  to[None];   hasDBID
}

// DS Bundle
class DsDBWReq(implicit p: Parameters) extends DSUBundle                                                // DS  ---[wReq] ---> DB
class DsDBWResp(implicit p: Parameters) extends DSUBundle with HasDBID                                  // DB  ---[wResp] --> DS
class DsDBOutData(implicit p: Parameters) extends DSUBundle with HasDBData with HasDBID                 // DB  ---[Data] ---> DS
class DsDBInData(implicit p: Parameters) extends DSUBundle with HasDBData with HasDBID                  // DS  ---[Data] ---> DB

class DsDBBundle(beat: Int = 1)(implicit p: Parameters) extends DSUBundle {
    val wReq        = Decoupled(new DsDBWReq)                        // from[None];  to[None];
    val wResp       = Flipped(Decoupled(new DsDBWResp))              // from[None];  to[None];   hasDBID
    val dataFDB     = Flipped(Decoupled(new DsDBOutData))            // from[None];  to[None];   hasDBID
    val dataTDB     = Decoupled(new DsDBInData)                      // from[None];  to[None];   hasDBID // TODO: Add to[CPU/MS], omit read requests
}


// ---------------------- ReqBuf Bundle ------------------- //
class RBFSMState(implicit p: Parameters) extends Bundle {
    // schedule
    val s_snp       = Bool()
    val s_snpResp   = Bool()
    val s_req2mp    = Bool() // expect write back req
    val s_wbReq2mp  = Bool()
    val s_resp      = Bool()
    val s_clean     = Bool()
    val s_getDBID   = Bool()
    val s_dbidResp  = Bool()

    // wait
    val w_snpResp   = Bool()
    val w_mpResp    = Bool()
    val w_dbid      = Bool()
    val w_dbData    = Bool()
    val w_rnData    = Bool()
    val w_compAck   = Bool()
}


// --------------------- ReqArb Bundle ------------------- //
class BlockTableEntry(implicit p: Parameters) extends DSUBundle {
    val valid   = Bool()
    val tag     = UInt(blockTagBits.W)
    val bank    = UInt(bankBits.W)
    // TODO: block by way full
}

class WCBTBundle(implicit p: Parameters) extends DSUBundle with HasToIDBits {
    val addr    = UInt(addressBits.W)
    val btWay   = UInt(blockWayBits.W) // block table
    val isClean = Bool()
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


// ------------------- DSReqEntry -------------------- //
// TODO: Can it get id in advance
// State Read DB: FREE -> RC_DB2DS -> WRITE_DS -> FREE
// State Write DB: FREE -> GET_ID -> READ_DS -> WRITE_DB -> RC_DB2OTH -> FREE
// State Read & Write DB: FREE -> GET_ID -> READ_DS -> WRITE_DB -> RC_DB2OTH -> RC_DB2DS -> WRITE_DS -> FREE
object DSState {
    val width       = 3
    val nrState     = 7
    val FREE        = "b000".U
    val GET_ID      = "b001".U
    val READ_DS     = "b010".U
    val WRITE_DB    = "b011".U
    val RC_DB2OTH   = "b100".U
    val RC_DB2DS    = "b101".U
    val WRITE_DS    = "b110".U
}

class DSReqEntry(implicit p: Parameters) extends DSUBundle with HasToIDBits {
    val set     = UInt(dsSetBits.W)
    val bank    = UInt(dsBankBits.W)
    val wayOH   = UInt(dsuparam.ways.W)
    val state   = UInt(DSState.width.W)
    val ren     = Bool() // read DS
    val wen     = Bool() // write DS
    val rDBID   = UInt(dbIdBits.W) // read DataBuffer DBID
    val wDBID   = UInt(dbIdBits.W) // write DataBuffer DBID
    val rBeatNum = UInt(log2Ceil(nrBeat).W) // read DS beat num
    val sBeatNum = UInt(log2Ceil(nrBeat).W) // send to DB beat num
}


