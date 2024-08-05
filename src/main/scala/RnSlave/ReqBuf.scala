package DONGJIANG.RNSLAVE

import DONGJIANG._
import DONGJIANG.CHI._
import chisel3._
import org.chipsalliance.cde.config._
import chisel3.util.{Cat, Decoupled, PopCount, RegEnable, Valid, ValidIO, log2Ceil}

class ReqBuf(rnSlvId: Int)(implicit p: Parameters) extends DJModule {
  val nodeParam = djparam.rnNodeMes(rnSlvId)

// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val reqBufId      = Input(UInt(rnReqBufIdBits.W))
    val free          = Output(Bool())
    // CHI
    val chi           = Flipped(CHIBundleDecoupled(chiParams))
    // slice ctrl signals
    val reqTSlice     = Decoupled(new RnReqOutBundle())
    val respFSlice    = Flipped(Decoupled(new RnRespInBundle()))
    val reqFSlice     = Decoupled(new RnReqInBundle())
    val respTSlice    = Flipped(Decoupled(new RnRespOutBundle()))
    // For txDat and rxDat sinasl
    val reqBufDBID    = Valid(new Bundle {
      val bankId      = UInt(bankBits.W)
      val dbid        = UInt(dbIdBits.W)
    })
    // slice DataBuffer signals
    val wReq          = Decoupled(new RnDBWReq())
    val wResp         = Flipped(Decoupled(new RnDBWResp()))
    val dataFDBVal    = Input(Bool())
    val dataTDBVal    = Input(Bool())
  })

  io <> DontCare

}