package DONGJIANG.RNMASTER

import DONGJIANG._
import DONGJIANG.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils._
import Utils.FastArb._
import Utils.IDConnector._

abstract class RnNodeBase(implicit p: Parameters) extends DJModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // slice ctrl signals
    val reqTSlice     = Decoupled(new RnReqOutBundle())
    val respFSlice    = Flipped(Decoupled(new RnRespInBundle()))
    val reqFSlice     = Flipped(Decoupled(new RnReqInBundle()))
    val respTSlice    = Decoupled(new RnRespOutBundle())
    // slice DataBuffer signals
    val dbSigs        = new RnDBBundle()
  })
}