package NHSN

import chisel3._
import chisel3.util._

object ValueDefine {
    val width = 6

    def STRB_WIDTH: Int       = 16
    def DATA_WIDTH: Int       = 256
    def ADDRESS_WIDTH: Int    = 48
    def FIFO_DEPTH:Int        = 4
    def NODEID_WIDTH:Int      = 11
    def QOS_WIDTH:Int         = 4
    def TXNID_WIDTH:Int       = 8
    def SRCID_WIDTH:Int       = 7
    def TGTID_WIDTH:Int       = 7
    def SIZE_WIDTH:Int        = 3
    def ORDER_WIDTH:Int       = 2
    def LPID_WIDTH:Int        = 5
    def HOMENID_WIDTH:Int     = 7
    def RESPERR_WIDTH:Int     = 2
    def RESP_WIDTH:Int        = 3
    def FWDSTATE_WIDTH:Int    = 3
    def DBID_WIDTH:Int        = 8
    def CCID_WIDTH:Int        = 2
    def DATAID_WIDTH:Int      = 2
    def BE_WIDTH:Int          = 32
    def BEATS_WIDTH:Int       = 2

    def DATA_OPCODE_WIDTH:Int = 3
    def REQ_OPCODE_WIDTH:Int  = 6
    def RSP_OPCODE_WIDTH:Int  = 4
    def SNP_OPCODE_WIDTH:Int  = 5

    def FLIT_DAT_WIDTH: Int   = 357
    def FLIT_REQ_WIDTH:Int    = 129
    def FLIT_RSP_WIDTH:Int    = 59
    def FLIT_SNP_WIDTH:Int    = 92





    // def ReadClean             = 0x02.U(width.W)
    // def ReadOnce              = 0x03.U(width.W)
    // def ReadNoSnp             = 0x04.U(width.W)
    // def PCrdReturn            = 0x05.U(width.W)

    
  }


  case class CHIBundleParameters(
                                nodeIdBits: Int,
                                addressBits: Int,
                                dataBits: Int,
                                dataCheck: Boolean
                                // TODO: has snoop
                              ) {
    require(nodeIdBits >= 7 && nodeIdBits <= 11)
    require(addressBits >= 44 && addressBits <= 52)
    require(isPow2(dataBits))
    require(dataBits == 128 || dataBits == 256 || dataBits == 512)
}

object CHIBundleParameters {
    def apply(
               nodeIdBits: Int = 7,
               addressBits: Int = 44,
               dataBits: Int = 256,
               dataCheck: Boolean = false
             ): CHIBundleParameters = new CHIBundleParameters(
        nodeIdBits = nodeIdBits,
        addressBits = addressBits,
        dataBits = dataBits,
        dataCheck = dataCheck
    )
}