package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU.CHI._
import NHDSU._

class WrDat (implicit p : Parameters) extends DSUModule {
 // -------------------------- IO declaration -----------------------------//
    val io = IO(new Bundle {
      val writeDataIn              = Flipped(Decoupled(new WriteData(chiBundleParams)))
      val writeDataOut             = Decoupled(new WriteReg(chiBundleParams))
    })

 // ------------------------ Wire/Reg declaration ---------------------------//
  val writeData                    = WireInit(0.U.asTypeOf(io.writeDataOut.bits))
  val beat                         = Wire(UInt(2.W))
  val firstBeat                    = Wire(Bool())




// ------------------------------- Logic ------------------------------------//
  firstBeat                       := io.writeDataIn.bits.dataID === 0.U
  beat                            := Mux(firstBeat, 0.U, 1.U)
  writeData.addr                  := io.writeDataIn.bits.addr
  writeData.beat                  := beat
  writeData.data                  := io.writeDataIn.bits.data

/* 
 * Output logic
 */

  io.writeDataOut.bits             := Mux(io.writeDataIn.fire, writeData, 0.U.asTypeOf(writeData))
  io.writeDataOut.valid            := io.writeDataIn.fire
  io.writeDataIn.ready             := true.B

}