package NHSN

import chisel3._
import chisel3.util._
import NHDSU._
import NHDSU.CHI._
import NHDSU.DSUParam._
import org.chipsalliance.cde.config._


class WriteData(params: CHIBundleParameters) extends Bundle {

    val addr           = UInt(params.addressBits.W)
    val txnID          = UInt(8.W)
    val dataID         = UInt(2.W)
    val data           = UInt(params.dataBits.W)

}

object WrState { // Read Ctl State
    val width      = 2
    val nrState    = 4
    val IDLE       = "b00".U
    val WAITDATA   = "b01".U
    val SENDDAT1   = "b10".U
}

class WriteBufTableEntry(implicit p: Parameters) extends DSUBundle {
    val state   = UInt(WrState.width.W)
    val txnID   = UInt(8.W)
    val addr    = UInt(addressBits.W)
}