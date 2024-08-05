package DONGJIANG

import DONGJIANG.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import scala.collection.immutable.ListMap
import scala.math.{max, min}

// -------------------------------------------------------------- Decode Bundle ------------------------------------------------------------------------ //

class OperationsBundle extends Bundle {
    val Snoop       = Bool()
    val ReadDown    = Bool()
    val ReadDB      = Bool()
    val ReadDS      = Bool()
    val WriteDS     = Bool()
    val WSDir       = Bool()
    val WCDir       = Bool()
    val Atomic      = Bool()
    val WriteBack   = Bool()
}


object TaskType {
    val width       = 9
    val Snoop       = "b0_0000_0001".U
    val ReadDown    = "b0_0000_0010".U
    val ReadDB      = "b0_0000_0100".U
    val ReadDS      = "b0_0000_1000".U
    val WriteDS     = "b0_0001_0000".U
    val WSDir       = "b0_0010_0000".U
    val WCDir       = "b0_0100_0000".U
    val WriteBack   = "b0_1000_0000".U
    val Commit      = "b1_0000_0000".U
}

object RespType {
    val width = 1
    val TpyeSnoop       = "b0".U
    val TpyeReadDown    = "b1".U
}

// ---------------------------------------------------------------- Xbar Id Bundle ----------------------------------------------------------------------------- //

object IdL0 {
    val width      = 3
    val SLICE      = "b000".U
    val RNSLV      = "b001".U
    val RNMAS      = "b010".U
    val SNMAS      = "b011".U
    val CMO        = "b100".U
    val AXI        = "b101".U
}

class IDBundle(implicit p: Parameters) extends DJBundle {
    val idL0 = UInt(IdL0.width.W) // Module: IDL0 [3.W]
    val idL1 = UInt(max(rnNodeIdBits, bankBits).W) // SubModule: RnSlave, RnMaster, Slices
    val idL2 = UInt(max(rnReqBufIdBits, max(snReqBufIdBits, snpCtlIdBits)).W) // SubSubModule: RnReqBufs, SnReqBufs, SnpCtls

    def isSLICE  = idL0 === IdL0.SLICE
    def isRNSLV  = idL0 === IdL0.RNSLV
    def isRNMAS  = idL0 === IdL0.RNMAS
    def isSNMAS  = idL0 === IdL0.SNMAS
    def isCMO    = idL0 === IdL0.CMO
    def isAXI    = idL0 === IdL0.AXI
}

trait HasFromIDBits extends DJBundle { this: Bundle => val from = new IDBundle() }

trait HasToIDBits extends DJBundle { this: Bundle => val to = new IDBundle() }
class ToIDBitsBundle(implicit p: Parameters) extends DJBundle with HasToIDBits

trait HasIDBits extends DJBundle with HasFromIDBits with HasToIDBits

trait HasDBID extends DJBundle { this: Bundle => val dbid = UInt(dbIdBits.W) }

trait HasAddr extends DJBundle { this: Bundle => val addr = UInt(addressBits.W) }

trait HasMSHRSet extends DJBundle { this: Bundle => val mshrSet = UInt(mshrSetBits.W) }

trait HasMSHRWay extends DJBundle { this: Bundle => val mshrWay = UInt(mshrWayBits.W) }

// ---------------------------------------------------------------- Rn Req To Slice Bundle ----------------------------------------------------------------------------- //
trait HasRnReqOutBase extends DJBundle with HasAddr with HasIDBits { this: Bundle =>
    // CHI Mes
    val opcode      = UInt(6.W)
    // other
    val readSDir    = Bool()
    val readCDir    = Bool()
    val willSnp     = Bool()
}

trait HasRnSlvReqOutBundle extends DJBundle with HasDBID

trait HasRnMasReqOutBundle extends DJBundle {
    // CHI Id
    val SrcIDOpt = if (djparam.useDCT) Some(UInt(chiParams.nodeIdBits.W)) else None
    val TxnIDOpt = if (djparam.useDCT) Some(UInt(chiParams.nodeIdBits.W)) else None
    // Snp Mes
    val doNotGoToSD = Bool()
    val retToSrc    = Bool()
}

class RnReqOutBundle(implicit p: Parameters) extends DJBundle with HasRnReqOutBase with HasRnSlvReqOutBundle with HasRnMasReqOutBundle

// ---------------------------------------------------------------- Rn Resp From SLice Bundle ----------------------------------------------------------------------------- //

trait HasRnRespInBase extends DJBundle with HasToIDBits with HasMSHRWay with HasDBID with HasCHIChannel { this: Bundle =>
    // CHI Id
    val SrcIDOpt = if (djparam.useDCT) Some(UInt(chiParams.nodeIdBits.W)) else None
    val TxnIDOpt = if (djparam.useDCT) Some(UInt(chiParams.nodeIdBits.W)) else None
    // CHI Mes
    val opcode      = UInt(6.W)
    val resp        = UInt(ChiResp.width.W)
}

trait HasRnSlvRespInBundle extends DJBundle

trait HasRnMasRespInBundle extends DJBundle

class RnRespInBundle(implicit p: Parameters) extends DJBundle with HasRnRespInBase with HasRnSlvRespInBundle with HasRnMasRespInBundle

// ---------------------------------------------------------------- Rn Req From Slice Bundle ----------------------------------------------------------------------------- //
trait HasRnReqInBase extends DJBundle with HasAddr with HasIDBits { this: Bundle =>
    // CHI Mes
    val opcode      = UInt(6.W)
}

trait HasRnSlvReqInBundle extends DJBundle { this: Bundle =>
    // CHI Id
    val SrcIDOpt = if (djparam.useDCT) Some(UInt(chiParams.nodeIdBits.W)) else None
    val TxnIDOpt = if (djparam.useDCT) Some(UInt(chiParams.nodeIdBits.W)) else None
    // Snp Mes
    val doNotGoToSD = Bool()
    val retToSrc    = Bool()
}

trait HasRnMasReqInBundle extends DJBundle with HasMSHRWay { this: Bundle =>
    val TgtID = UInt(chiParams.nodeIdBits.W)
}

class RnReqInBundle(implicit p: Parameters) extends DJBundle with HasRnReqInBase with HasRnSlvReqInBundle with HasRnMasReqInBundle


// ---------------------------------------------------------------- Rn Resp To SLice Bundle ----------------------------------------------------------------------------- //
trait HasRnRespOutBase extends DJBundle with HasToIDBits with HasDBID { this: Bundle =>
    // CHI Mes
    val resp        = UInt(ChiResp.width.W)
}

trait HasRnSlvRespOutBundle extends DJBundle

trait HasRnMasRespOutBundle extends DJBundle with HasMSHRSet with HasMSHRWay

class RnRespOutBundle(implicit p: Parameters) extends DJBundle with HasRnRespOutBase with HasRnSlvRespOutBundle with HasRnMasRespOutBundle


// ---------------------------------------------------------------- DataBuffer Base Bundle ----------------------------------------------------------------------------- //
trait HasDBRCOp extends DJBundle { this: Bundle =>
    val isRead = Bool()
    val isClean = Bool()
}
// Base Data Bundle
trait HasDBData extends DJBundle { this: Bundle =>
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
trait HasDBRCReq extends DJBundle with HasDBRCOp with HasDBID

// ---------------------------------------------------------------- RN DataBuffer Base Bundle ----------------------------------------------------------------------------- //
class RnDBRCReq(implicit p: Parameters) extends DJBundle with HasDBRCReq with HasIDBits

class RnDBWReq(implicit p: Parameters) extends DJBundle with HasIDBits
class RnDBWResp(implicit p: Parameters) extends DJBundle with HasIDBits with HasDBID
class RnDBOutData(implicit p: Parameters) extends DJBundle with HasDBData with HasToIDBits
class RnDBInData(implicit p: Parameters) extends DJBundle with HasDBData with HasToIDBits with HasDBID

class RnDBBundle(implicit p: Parameters) extends DJBundle {
    val wReq        = Decoupled(new RnDBWReq)
    val wResp       = Flipped(Decoupled(new RnDBWResp))
    val dataFDB     = Flipped(Decoupled(new RnDBOutData))
    val dataTDB     = Decoupled(new RnDBInData)
}






