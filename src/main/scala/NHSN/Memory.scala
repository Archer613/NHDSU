package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU._
import NHDSU.CHI._
import NHDSU.CHI.CHIOp._

class Memory(implicit p : Parameters) extends DSUModule {

    val io = IO(new Bundle {
        val readValid         = Vec(dsuparam.nrBank, Input(Bool()))
        val readAddr          = Vec(dsuparam.nrBank, Input(UInt(addressBits.W)))
        val readReady         = Vec(dsuparam.nrBank, Output(Bool()))

        val writeValid        = Vec(dsuparam.nrBank, Input(Bool()))
        val writeAddr         = Vec(dsuparam.nrBank, Input(UInt(addressBits.W)))
        val writeData         = Vec(dsuparam.nrBank, Input(UInt(dataBits.W)))
        val writeReady        = Vec(dsuparam.nrBank, Output(Bool()))
    })
}
