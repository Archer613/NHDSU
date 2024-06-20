package NHDSU

import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

case object DSUParamKey extends Field[DSUParam](DSUParam())

case class DSUParam(
                    nrCore: Int = 1,
                    nrReqBuf: Int = 16,
                    nrSnoopCtl: Int = 16,
                    ways: Int = 8,
                    sets: Int = 256,
                    blockBytes: Int = 64,
                    beatBytes: Int = 32,
                    addressBits: Int = 48,
                    enableSramClockGate: Boolean = true,
                    nrBank: Int = 1,
                    nrDataBufferEntry: Int = 16,
                    replacementPolicy: String = "plru",
                    // can receive or send chi lcrd num
                    nrRnTxLcrdMax: Int = 4,
                    nrRnRxLcrdMax: Int = 4
                  ) {
    require(nrCore > 0)
    require(nrBank > 0)
    require(nrRnTxLcrdMax <= 15)
    require(nrRnRxLcrdMax <= 15)
    require(replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru")
}

trait HasDSUParam {
    val p: Parameters
    val dsuparam = p(DSUParamKey)

    val nrBeat          = dsuparam.blockBytes/dsuparam.beatBytes
    val coreIdBits      = log2Ceil(dsuparam.nrCore)
    val reqBufIdBits    = log2Ceil(dsuparam.nrReqBuf)
    val snoopCtlIdBits  = log2Ceil(dsuparam.nrSnoopCtl)
    val dbIdBits        = log2Ceil(dsuparam.nrDataBufferEntry)
    val addressBits     = dsuparam.addressBits
    val dataBits        = dsuparam.blockBytes * 8
    val beatBits        = dsuparam.beatBytes * 8
    val bankBits        = log2Ceil(dsuparam.nrBank)
    val setBits         = log2Ceil(dsuparam.sets)
    val offsetBits      = log2Ceil(dsuparam.blockBytes)
    val tagBits         = dsuparam.addressBits - bankBits - setBits - offsetBits
    val wayBits         = log2Ceil(dsuparam.ways)
    val rnTxlcrdBits      = log2Ceil(dsuparam.nrRnTxLcrdMax)
    val rnRxxlcrdBits      = log2Ceil(dsuparam.nrRnRxLcrdMax)


    val chiBundleParams = CHIBundleParameters(
        nodeIdBits = 7,
        addressBits = addressBits,
        dataBits = dataBits,
        dataCheck = false
    )

    def parseAddress(x: UInt): (UInt, UInt, UInt, UInt) = {
        val offset = x
        val bank   = offset >> offsetBits
        val set    = bank >> bankBits
        val tag    = set >> setBits
        (tag(tagBits - 1, 0), bank(bankBits - 1, 0), set(setBits - 1, 0), offset(offsetBits - 1, 0))
    }


    def widthCheck(in: UInt, width: Int) = {
        assert(in.getWidth == width)
    }

}
