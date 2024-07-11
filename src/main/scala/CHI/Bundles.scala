package NHDSU.CHI

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import scala.collection.immutable.ListMap


// CHIBundle adapt to CHI-B
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

class CHIBundleDownstream(params: CHIBundleParameters, aggregateIO: Boolean = false) extends Record {
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

class CHIBundleUpstream(params: CHIBundleParameters, aggregateIO: Boolean = false) extends Record {
    val txreq: CHIChannelIO[CHIBundleREQ] = Flipped(CHIChannelIO(new CHIBundleREQ(params), aggregateIO))
    val txdat: CHIChannelIO[CHIBundleDAT] = Flipped(CHIChannelIO(new CHIBundleDAT(params), aggregateIO))
    val txrsp: CHIChannelIO[CHIBundleRSP] = Flipped(CHIChannelIO(new CHIBundleRSP(params), aggregateIO))

    val rxrsp: CHIChannelIO[CHIBundleRSP] = CHIChannelIO(new CHIBundleRSP(params), aggregateIO)
    val rxdat: CHIChannelIO[CHIBundleDAT] = CHIChannelIO(new CHIBundleDAT(params), aggregateIO)
    val rxsnp: CHIChannelIO[CHIBundleSNP] = CHIChannelIO(new CHIBundleSNP(params), aggregateIO)

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

class CHIBundleDecoupled(params: CHIBundleParameters) extends Bundle {
    val txreq = Decoupled(new CHIBundleREQ(params))
    val txdat = Decoupled(new CHIBundleDAT(params))
    val txrsp = Decoupled(new CHIBundleRSP(params))

    val rxrsp = Flipped(Decoupled(new CHIBundleRSP(params)))
    val rxdat = Flipped(Decoupled(new CHIBundleDAT(params)))
    val rxsnp = Flipped(Decoupled(new CHIBundleSNP(params)))
}

object CHIBundleDownstream {
    def apply(params: CHIBundleParameters, aggregateIO: Boolean = false): CHIBundleDownstream = new CHIBundleDownstream(params, aggregateIO)
}

object CHIBundleUpstream {
    def apply(params: CHIBundleParameters, aggregateIO: Boolean = false): CHIBundleUpstream = new CHIBundleUpstream(params, aggregateIO)
}

object CHIBundleDecoupled {
    def apply(params: CHIBundleParameters): CHIBundleDecoupled = new CHIBundleDecoupled(params)
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

object ChiResp {
    val width = 3

    def I = "b000".U(width.W)
    def SC = "b001".U(width.W)
    def UC = "b010".U(width.W)
    def UD = "b010".U(width.W)
    def SD = "b011".U(width.W)

    def PassDirty = "b100".U(width.W)

    def I_PD = "b100".U(width.W)
    def SC_PD = "b101".U(width.W)
    def UC_PD = "b110".U(width.W)
    def UD_PD = "b110".U(width.W)
    def SD_PD = "b111".U(width.W)

    def setPD(state: UInt, pd: Bool = true.B): UInt = {
        require(state.getWidth == width)
        state | Mux(pd, PassDirty, 0.U)
    }
}

trait HasChiResp { this: Bundle =>
    val state = UInt(ChiResp.width.W)

    val baseWidth = ChiResp.width-2

    def isInvalid = state(baseWidth, 0) === ChiResp.I(baseWidth, 0)
    def isShared = state(baseWidth, 0) === ChiResp.SC(baseWidth, 0) | state(baseWidth, 0) === ChiResp.SD(baseWidth, 0)
    def isUnique = state(baseWidth, 0) === ChiResp.UC(baseWidth, 0) | state(baseWidth, 0) === ChiResp.UD(baseWidth, 0)
    def isClean = state(baseWidth, 0) === ChiResp.SC(baseWidth, 0) | state(baseWidth, 0) === ChiResp.UC(baseWidth, 0)
    def isDirty = state(baseWidth, 0) === ChiResp.UD(baseWidth, 0) | state(baseWidth, 0) === ChiResp.SD(baseWidth, 0)
    def passDirty = state(ChiResp.width-1)
}

class CHIRespBundle extends Bundle with HasChiResp

object ChiState {
    val width = 3

    // [U/S] + [D/C] + [V/I]
    def I = "b000".U(width.W)
    def SC = "b001".U(width.W)
    def UC = "b101".U(width.W)
    def SD = "b011".U(width.W)
    def UD = "b111".U(width.W)
    def ERROR = "b110".U(width.W)
}

trait HasChiStates { this: Bundle =>
    val state = UInt(ChiState.width.W)

    /*
     * Coherence State
     * RN(isInvalid) -> HN(isInvalid / isShared / isUnique)
     * RN(isShared)  -> HN(isInvalid / isShared)
     * RN(isUnique)  -> HN(isInvalid)
     *
     * Dirty / Clean State
     * RN(isClean)   -> HN(isInvalid / isClean)
     * RN(isDirty)   -> HN(isInvalid / isDirty)
     */
    def isInvalid   = state(0) === ChiState.I(0)
    def isisShared  = state(ChiState.width-1) === 0.U & !isInvalid
    def isUnique    = state(ChiState.width-1) === 1.U & !isInvalid
    def isClean     = state(ChiState.width-2) === 0.U & !isInvalid
    def isDirty     = state(ChiState.width-2) === 1.U & !isInvalid
    def isError     = isInvalid & state =/= ChiState.I // when state(0) === 0.U, state must be 0.U
}

class CHIStateBundle extends Bundle with HasChiStates

object CHIChannel {
    val width = 3

    /*
     *  TXREQ   TXRSP   TXDAT
     * -----------------------
     * |        DSU          |
     * -----------------------
     *  RXSNP   RXRSP   RXDAR
     */

    def TXREQ = "b001".U(width.W)
    def TXRSP = "b010".U(width.W)
    def TXDAT = "b011".U(width.W)
    def RXSNP = "b100".U(width.W)
    def RXRSP = "b101".U(width.W)
    def RXDAT = "b110".U(width.W)
    def CHNLSELF = "b111".U(width.W)
}

trait HasCHIChannel {
    this: Bundle =>
    val channel = UInt(CHIChannel.width.W)

    def isTxReq = channel === CHIChannel.TXREQ
    def isTxRsp = channel === CHIChannel.TXRSP
    def isTxDat = channel === CHIChannel.TXDAT
    def isRxSnp = channel === CHIChannel.RXSNP
    def isRxRsp = channel === CHIChannel.RXRSP
    def isRxDat = channel === CHIChannel.RXDAT
    def isChnlSelf = channel === CHIChannel.CHNLSELF

    def isChnlError = !(isTxReq | isTxRsp | isTxDat | isRxSnp | isRxRsp | isRxDat | isChnlSelf)
}
