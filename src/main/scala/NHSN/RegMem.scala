package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU.CHI._
import NHDSU._
import NHDSU.CHI.CHIOp._

class RegMem (implicit p : Parameters) extends DSUModule {
 // -------------------------- IO declaration -----------------------------//
    val io = IO(new Bundle {
      val readReq           = Vec(dsuparam.nrBank, Input(new ReadReg(chiBundleParams)))
      val respDat           = Vec(dsuparam.nrBank, Output(Vec(2,UInt(beatBits.W))))
      val writeReq          = Vec(dsuparam.nrBank, Flipped(Decoupled(new WriteReg(chiBundleParams))))
    })
 // -------------------------- Module define -----------------------------//
  val memReg                = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(8096)(0.U(256.W))))))

 // -------------------------- Wire/Reg define -----------------------------//

  val writeAddr             = Wire(Vec(dsuparam.nrBank, UInt(13.W)))
  val readAddr              = Wire(Vec(dsuparam.nrBank, UInt(13.W)))
  val data                  = Wire(Vec(dsuparam.nrBank, Vec(2, UInt(beatBits.W))))



 // ----------------------------- Logic ------------------------------------//
  for (i <- 0 until dsuparam.nrBank) {
    writeAddr(i)           := (io.writeReq(i).bits.addr >> 6.U)(12, 0)
    readAddr(i)            := (io.readReq(i).addr >> 6.U)(12, 0)
    io.writeReq(i).ready   := true.B

    when(io.writeReq(i).valid) {
      memReg(io.writeReq(i).bits.beat)(writeAddr(i)) := io.writeReq(i).bits.data
    }

    data(i)(0)             := memReg(0)(readAddr(i))
    data(i)(1)             := memReg(1)(readAddr(i))


    io.respDat(i)          := data(i)
  }

}
