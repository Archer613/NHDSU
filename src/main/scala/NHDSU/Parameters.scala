package NHDSU

import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

case object DSUParamKey extends Field[DSUParam](DSUParam())

case class IDMap
(
    RNID: Seq[Int] = Seq(0), // TODO: muticore with diff RNID
    HNID: Int = 0,
    SNID: Int = 0
)

case class DSUParam(
                    // base num
                    nrCore: Int = 1,
                    nrBank: Int = 2,
                    nrReqBuf: Int = 16,
                    nrSnoopCtl: Int = 16,
                    nrDataBufferEntry: Int = 16,
                    // dir & ds
                    nrSelfDirBank: Int = 2,
                    nrClientDirBank: Int = 2,
                    nrDSBank: Int = 2,
                    ways: Int = 8,
                    sets: Int = 64,
                    clientWays: Int = 8,
                    clientSets: Int = 8,
                    replacementPolicy: String = "plru",
                    // data
                    blockBytes: Int = 64,
                    beatBytes: Int = 32,
                    // addr
                    addressBits: Int = 48,
                    // sram
                    // enableSramClockGate: Boolean = true, // it will be always true
                    dirMulticycle: Int = 1,
                    dataMulticycle: Int = 2,
                    // chi
                    // can receive or send chi lcrd num
                    nrRnTxLcrdMax: Int = 4,
                    nrRnRxLcrdMax: Int = 4,
                    nrSnTxLcrdMax: Int = 4,
                    nrSnRxLcrdMax: Int = 4,
                    // CHI ID Map
                    idmap: IDMap = new IDMap()
                  ) {
    require(nrCore > 0)
    require(nrBank == 1 | nrBank == 2 | nrBank == 4)
    require(nrRnTxLcrdMax <= 15)
    require(nrRnRxLcrdMax <= 15)
    require(replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru")
}

trait HasDSUParam {
    val p: Parameters
    val dsuparam = p(DSUParamKey)

    // BASE
    val nrMPQBeat       = 4
    val nrBeat          = dsuparam.blockBytes/dsuparam.beatBytes
    val addressBits     = dsuparam.addressBits
    val dataBits        = dsuparam.blockBytes * 8
    val beatBits        = dsuparam.beatBytes * 8
    // ID
    val coreIdBits      = log2Ceil(dsuparam.nrCore)
    val reqBufIdBits    = log2Ceil(dsuparam.nrReqBuf)
    val snoopCtlIdBits  = log2Ceil(dsuparam.nrSnoopCtl)
    val dbIdBits        = log2Ceil(dsuparam.nrDataBufferEntry)
    // CHI
    val rnTxlcrdBits    = log2Ceil(dsuparam.nrRnTxLcrdMax) + 1
    val rnRxlcrdBits    = log2Ceil(dsuparam.nrRnRxLcrdMax) + 1
    val snTxlcrdBits    = log2Ceil(dsuparam.nrSnTxLcrdMax) + 1
    val snRxlcrdBits    = log2Ceil(dsuparam.nrSnRxLcrdMax) + 1
    // DIR BASE
    val bankBits        = log2Ceil(dsuparam.nrBank)
    val offsetBits      = log2Ceil(dsuparam.blockBytes)
    // SELF DIR: [sTag] + [sSet] + [sDirBank] + [bank] + [offset]
    // [sSet] + [sDirBank] = [setBis]
    val sWayBits        = log2Ceil(dsuparam.ways)
    val sDirBankBits    = log2Ceil(dsuparam.nrSelfDirBank)
    val sSetBits        = log2Ceil(dsuparam.sets/dsuparam.nrSelfDirBank)
    val sTagBits        = dsuparam.addressBits - sSetBits - sDirBankBits - bankBits - offsetBits
    // CLIENT DIR: [cTag] + [cSet] + [cDirBank] + [bank] + [offset]
    // [cSet] + [cDirBank] = [clientSetsBits]
    val cWayBits        = log2Ceil(dsuparam.clientWays)
    val cDirBankBits    = log2Ceil(dsuparam.nrClientDirBank)
    val cSetBits        = log2Ceil(dsuparam.clientSets / dsuparam.nrClientDirBank)
    val cTagBits        = dsuparam.addressBits - cSetBits - cDirBankBits - bankBits - offsetBits
    // DS
    val dsWayBits       = sWayBits
    val dsBankBits      = log2Ceil(dsuparam.nrDSBank)
    val dsSetBits       = log2Ceil(dsuparam.sets/dsuparam.nrDSBank)
    // BLOCK TABLE: [blockTag] + [blockSet] + [offset]
    val nrBlockWays     = dsuparam.ways * 2
    val nrBlockSets     = 16
    val blockWayBits    = log2Ceil(nrBlockWays)
    val blockSetBits    = log2Ceil(nrBlockSets)
    val blockTagBits    = dsuparam.addressBits - blockSetBits - offsetBits
    // ReadCtl
    val nrReadCtlEntry  = 8
    val rcEntryBits     = log2Ceil(nrReadCtlEntry)
    // CHI TXNID Width
    val txnidBits       = 8
    val dbidBits        = 8

    require(nrBlockSets <= dsuparam.sets)
    require(nrReadCtlEntry <= dsuparam.nrDataBufferEntry, "The maximum number of ReadCtl deal req logic is equal to nrDataBufferEntry")
    require(log2Ceil(dsuparam.nrReqBuf) <= txnidBits-1) // txnID width -1, retain the highest bit
    require(bankBits + dbIdBits <= dbidBits)

    val chiBundleParams = CHIBundleParameters(
        nodeIdBits = 7,
        addressBits = addressBits,
        dataBits = beatBits,
        dataCheck = false
    )

    def parseAddress(x: UInt, modBankBits: Int = 1, setBits: Int = 1, tagBits: Int = 1): (UInt, UInt, UInt, UInt, UInt) = {
        val offset  = x
        val bank    = offset    >> offsetBits
        val modBank = bank      >> bankBits
        val set     = modBank   >> modBankBits
        val tag     = set       >> setBits
        // return: [5:tag] [4:set] [3:modBank] [2:bank] [1:offset]
        (tag(tagBits - 1, 0), set(setBits - 1, 0), modBank(modBankBits - 1, 0), bank(bankBits - 1, 0), offset(offsetBits - 1, 0))
    }

    def toDataID(x: UInt): UInt = {
        if (nrBeat == 1) { x }
        else if (nrBeat == 2) { Mux(x === 0.U, 0.U, 2.U) }
        else { 0.U }
    }

    def widthCheck(in: UInt, width: Int) = {
        assert(in.getWidth == width)
    }

}
