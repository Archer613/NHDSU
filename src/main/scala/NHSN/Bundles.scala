package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import scala.collection.immutable.ListMap

class CHIBundleREQ(params: CHIBundleParameters) extends Bundle {
    val channelName = "'REQ' channel"

    val qos            = UInt(4.W)
    val tgtID          = UInt(params.nodeIdBits.W)
    val srcID          = UInt(params.nodeIdBits.W)
    val txnID          = UInt(8.W)
    val returnNID      = UInt(params.nodeIdBits.W)
    // val stashNID       = returnNID
    val returnTxnID    = UInt(8.W)
    // val stashLPIDValid = returnTxnID(5)
    // val stashLPID      = returnNID(4,0)
    val opcode         = UInt(6.W)
    val size           = UInt(3.W)
    val addr           = UInt(params.addressBits.W)
    val ns             = Bool()
    val likelyShared   = Bool()
    val allowRetry     = Bool()
    val order          = UInt(2.W)
    val pCrdType       = UInt(4.W)
    val memAttr        = UInt(4.W)
    val snpAttr        = UInt(1.W)
    val lpID           = UInt(5.W)
    val excl           = Bool()
    // val snoopMe        = excl
    val expCompAck     = Bool()
    val traceTag       = Bool()
    val rsvdc          = UInt(4.W)
}

class CHIBundleRSP(params: CHIBundleParameters) extends Bundle {
    val channelName = "'RSP' channel"

    val qos      = UInt(4.W)
    val tgtID    = UInt(params.nodeIdBits.W)
    val srcID    = UInt(params.nodeIdBits.W)
    val txnID    = UInt(8.W)
    val opcode   = UInt(4.W)
    val respErr  = UInt(2.W)
    val resp     = UInt(3.W)
    val fwdState = UInt(3.W)
    // val dataPull = fwdState
    val dbID     = UInt(8.W)
    val pCrdType = UInt(4.W)
    val traceTag = Bool()
}

class CHIBundleSNP(params: CHIBundleParameters) extends Bundle {
    val channelName = "'SNP' channel"

    val qos            = UInt(4.W)
    val srcID          = UInt(params.nodeIdBits.W)
    val txnID          = UInt(8.W)
    val fwdNID         = UInt(params.nodeIdBits.W)
    val fwdTxnID       = UInt(8.W)
    // val stashLPIDValid = fwdTxnID(5)
    // val stashLPID      = fwdTxnID(4,0)
    // val vmIDExt        = fwdTxnID
    val opcode         = UInt(5.W)
    val addr           = UInt((params.addressBits - 3).W)
    val ns             = Bool()
    val doNotGoToSD    = Bool()
    // val doNotDataPull  = doNotGoToSD
    val retToSrc       = Bool()
    val traceTag       = Bool()
}

class CHIBundleDAT(params: CHIBundleParameters) extends Bundle {
    val channelName = "'DAT' channel"

    val qos        = UInt(4.W)
    val tgtID      = UInt(params.nodeIdBits.W)
    val srcID      = UInt(params.nodeIdBits.W)
    val txnID      = UInt(8.W)
    val homeNID    = UInt(params.nodeIdBits.W)
    val opcode     = UInt(3.W)
    val respErr    = UInt(2.W)
    val resp       = UInt(3.W)
    val fwdState   = UInt(3.W)
    // val dataPull   = fwdState
    // val dataSource = fwdState
    val dbID       = UInt(8.W)
    val ccID       = UInt(2.W)
    val dataID     = UInt(2.W)
    val traceTag   = Bool()
    val rsvdc      = UInt(4.W)
    val be         = UInt((params.dataBits / 8).W)
    val data       = UInt(params.dataBits.W) // TODO: parameterize this val
    val dataCheck  = if (params.dataCheck) Some(UInt((params.dataBits / 8).W)) else None
    val poison     = if (params.dataCheck) Some(UInt((params.dataBits / 64).W)) else None
}

class CHIChannelIO[T <: Data](gen: T, aggregateIO: Boolean = false) extends Bundle {
    val flitpend = Output(Bool())
    val flitv    = Output(Bool())
    val flit     = if (aggregateIO) Output(UInt(gen.getWidth.W)) else Output(gen)
    val lcrdv    = Input(Bool())
}

object CHIChannelIO {
    def apply[T <: Data](gen: T, aggregateIO: Boolean = false): CHIChannelIO[T] = new CHIChannelIO(gen, aggregateIO)
}

class CHIBundlestream(params: CHIBundleParameters, aggregateIO: Boolean = false) extends Record {
    val txreq: CHIChannelIO[CHIBundleREQ] = CHIChannelIO(new CHIBundleREQ(params), aggregateIO)
    val txdat: CHIChannelIO[CHIBundleDAT] = CHIChannelIO(new CHIBundleDAT(params), aggregateIO)
    val txrsp: CHIChannelIO[CHIBundleRSP] = CHIChannelIO(new CHIBundleRSP(params), aggregateIO)

    val rxrsp: CHIChannelIO[CHIBundleRSP] = Flipped(CHIChannelIO(new CHIBundleRSP(params), aggregateIO))
    val rxdat: CHIChannelIO[CHIBundleDAT] = Flipped(CHIChannelIO(new CHIBundleDAT(params), aggregateIO))
    val rxsnp: CHIChannelIO[CHIBundleSNP] = Flipped(CHIChannelIO(new CHIBundleSNP(params), aggregateIO))

    // @formatter:off
    val elements = ListMap(
        "txreq" -> txreq,
        "txdat" -> txdat,
        "txrsp" -> txrsp,
        "rxrsp" -> rxrsp,
        "rxdat" -> rxdat,
        "rxsnp" -> rxsnp
    )
    // @formatter:on
}



object CHIBundlestream {
    def apply(params: CHIBundleParameters, aggregateIO: Boolean = false): CHIBundlestream = new CHIBundlestream(params, aggregateIO)
}




class CHILinkCtrlIO extends Bundle {
    val txsactive = Output(Bool())
    val rxsactive = Input(Bool())

    val txactivereq = Output(Bool())
    val txactiveack = Input(Bool())

    val rxactivereq = Input(Bool())
    val rxactiveack = Output(Bool())
}

object LinkStates {
    val width = 2

    def STOP        = 0.U(width.W)
    def ACTIVATE    = 1.U(width.W)
    def RUN         = 2.U(width.W)
    def DEACTIVATE  = 3.U(width.W)

    def getLinkState(req: UInt, ack: UInt): UInt = {
        MuxLookup(Cat(req, ack), LinkStates.STOP)(Seq(
            Cat(true.B, false.B) -> LinkStates.ACTIVATE,
            Cat(true.B, true.B) -> LinkStates.RUN,
            Cat(false.B, true.B) -> LinkStates.DEACTIVATE,
            Cat(false.B, false.B) -> LinkStates.STOP
        ))
    }
}

class LinkState extends Bundle {
    val state = UInt(LinkStates.width.W)
}

