package DONGJIANG

import DONGJIANG.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

case object DJParamKey extends Field[DJParam](DJParam())

case class IDMap
(
    RNID: Seq[Int] = Seq(0), // TODO: muticore with diff RNID
    HNID: Int = 0,
    SNID: Int = 0
)

case class DJParam(
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
                    ways: Int = 4,
                    sets: Int = 32,
                    clientWays: Int = 4,
                    clientSets: Int = 32,
                    replacementPolicy: String = "plru",
                    // data
                    blockBytes: Int = 64,
                    beatBytes: Int = 32,
                    // addr
                    addressBits: Int = 48,
                    // sram
                    // enableSramClockGate: Boolean = true, // it will be always true
                    dirMulticycle: Int = 1,
                    dataMulticycle: Int = 2, // TODO: data holdMcp
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
    require(clientWays >= 4)
    require(nrRnTxLcrdMax <= 15)
    require(nrRnRxLcrdMax <= 15)
    require(replacementPolicy == "random" || replacementPolicy == "plru" || replacementPolicy == "lru")
}

trait HasDJParam {
    val p: Parameters
    val djparam = p(DJParamKey)

    // BASE
    val mpBlockBySet    = true
    val nrMPQBeat       = 4
    val nrBeat          = djparam.blockBytes/djparam.beatBytes
    val addressBits     = djparam.addressBits
    val dataBits        = djparam.blockBytes * 8
    val beatBits        = djparam.beatBytes * 8
    // ID
    val coreIdBits      = log2Ceil(djparam.nrCore)
    val reqBufIdBits    = log2Ceil(djparam.nrReqBuf)
    val snoopCtlIdBits  = log2Ceil(djparam.nrSnoopCtl)
    val dbIdBits        = log2Ceil(djparam.nrDataBufferEntry)
    // CHI
    val rnTxlcrdBits    = log2Ceil(djparam.nrRnTxLcrdMax) + 1
    val rnRxlcrdBits    = log2Ceil(djparam.nrRnRxLcrdMax) + 1
    val snTxlcrdBits    = log2Ceil(djparam.nrSnTxLcrdMax) + 1
    val snRxlcrdBits    = log2Ceil(djparam.nrSnRxLcrdMax) + 1
    // DIR BASE
    val bankBits        = log2Ceil(djparam.nrBank)
    val offsetBits      = log2Ceil(djparam.blockBytes)
    // SELF DIR: [sTag] + [sSet] + [sDirBank] + [bank] + [offset]
    // [sSet] + [sDirBank] = [setBis]
    val sWayBits        = log2Ceil(djparam.ways)
    val sDirBankBits    = log2Ceil(djparam.nrSelfDirBank)
    val sSetBits        = log2Ceil(djparam.sets/djparam.nrSelfDirBank)
    val sTagBits        = djparam.addressBits - sSetBits - sDirBankBits - bankBits - offsetBits
    // CLIENT DIR: [cTag] + [cSet] + [cDirBank] + [bank] + [offset]
    // [cSet] + [cDirBank] = [clientSetsBits]
    val cWayBits        = log2Ceil(djparam.clientWays)
    val cDirBankBits    = log2Ceil(djparam.nrClientDirBank)
    val cSetBits        = log2Ceil(djparam.clientSets / djparam.nrClientDirBank)
    val cTagBits        = djparam.addressBits - cSetBits - cDirBankBits - bankBits - offsetBits
    // DS
    val dsWayBits       = sWayBits
    val dsBankBits      = log2Ceil(djparam.nrDSBank)
    val dsSetBits       = log2Ceil(djparam.sets/djparam.nrDSBank)
    // BLOCK TABLE: [blockTag] + [blockSet] + [bank] + [offset]
    val nrBlockWays     = djparam.ways * 2
    val nrBlockSets     = 16
    val blockWayBits    = log2Ceil(nrBlockWays) // 3
    val blockSetBits    = log2Ceil(nrBlockSets) // 4
    val blockTagBits    = djparam.addressBits - blockSetBits - bankBits - offsetBits
    val replTxnidBits   = blockSetBits + blockWayBits
    // ReadCtl
    val nrReadCtlEntry  = 8
    val rcEntryBits     = log2Ceil(nrReadCtlEntry)
    // CHI TXNID Width
    val chiTxnidBits    = 8
    val chiDbidBits     = 8
    require(blockWayBits + blockSetBits <= chiTxnidBits - 1, "HN -> SN WB Txnid = Cat(1'b1, blockSet, blockWay)")
    // replacement
    val useRepl         = djparam.replacementPolicy != "random"
    val sReplWayBits    = djparam.ways - 1;
    val cReplWayBits    = djparam.clientWays - 1
    require(djparam.replacementPolicy == "random" | djparam.replacementPolicy == "plru", "It should modify sReplWayBits and cReplWayBits when use replacement except of random or plru")
    // TIMEOUT CHECK CNT VALUE
    val TIMEOUT_RB      = 10000 // ReqBuf
    val TIMEOUT_DB      = 8000  // DataBuffer
    val TIMEOUT_BT      = 8000  // BlockTable
    val TIMEOUT_MP      = 8000  // MainPipe
    val TIMEOUT_SNP     = 8000  // SnoopCtl
    val TIMEOUT_DS      = 6000  // DataStorage
    val TIMEOUT_RC      = 6000  // ReadCtl
    val TIMEOUT_TXD     = 1000  // SnChiTxDat



    // requirement
    require(nrBlockSets <= djparam.sets)
    require(nrReadCtlEntry <= djparam.nrDataBufferEntry, "The maximum number of ReadCtl deal req logic is equal to nrDataBufferEntry")
    require(log2Ceil(djparam.nrReqBuf) <= chiTxnidBits-1) // txnID width -1, retain the highest bit
    require(bankBits + dbIdBits <= chiDbidBits)
    require(dbIdBits < chiTxnidBits - 1)

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

    def parseBTAddress(x: UInt): (UInt, UInt, UInt) = {
        val tag = WireInit(0.U(blockTagBits.W))
        val (tag_, set, modBank, bank, offset) = parseAddress(x, modBankBits = 0, setBits = blockSetBits, tagBits = blockTagBits)
        if (!mpBlockBySet) {
            tag := tag_ // TODO: When !mpBlockBySet it must support useWayOH Check and RetryQueue
        } else {
            require(sSetBits + sDirBankBits > blockSetBits)
            tag := tag_(sSetBits + sDirBankBits - 1 - blockSetBits, 0)
        }
        (tag, set, bank)
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
