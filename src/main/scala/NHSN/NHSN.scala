package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU.CHI._
import NHDSU._
import NHDSU.CHI.CHIOp._

class NHSN (implicit p : Parameters) extends DSUModule {
 // -------------------------- IO declaration -----------------------------//
    val io = IO(new Bundle {
      val hnChi           = Vec(dsuparam.nrBank, CHIBundleUpstream(chiBundleParams))
      val hnLinkCtrl      = Vec(dsuparam.nrBank, Flipped(new CHILinkCtrlIO()))
    })
 // -------------------------- Module declaration -----------------------------//

  val snSlices            = Seq.fill(dsuparam.nrBank) { Module(new SNSlice())}
  val memReg              = Module(new RegMem())

 // -------------------------- Connect declaration -----------------------------//

  io.hnChi.zip(snSlices.map(_.io.chi)).foreach{ case(h, c) => h <> c }
  io.hnLinkCtrl.zip(snSlices.map(_.io.chiLinkCtrl)).foreach { case (h, c) => h <> c }

/* 
 * SNSlice is connected to RegMem
 */

  memReg.io.writeReq.zip(snSlices.map(_.io.writeMem)).foreach { case (m, s) => m <> s}
  memReg.io.readReq.zip(snSlices.map(_.io.readReq)).foreach { case (m, s) => m := s}
  (snSlices.map(_.io.readResp)).zip(memReg.io.respDat).foreach { case (s, m) => s := m}
}
