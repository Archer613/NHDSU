package DONGJIANG.RNSLAVE

import DONGJIANG. _
import DONGJIANG.CHI._
import DONGJIANG.IdL0._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class ChiTxDat(rnSlvId: Int)(implicit p: Parameters) extends DJModule {
  val nodeParam = djparam.rnNodeMes(rnSlvId)

// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val chi           = Flipped(CHIChannelIO(new CHIBundleDAT(chiParams), nodeParam.aggregateIO))
    val txState       = Input(UInt(LinkStates.width.W))
    val allLcrdRetrun = Output(Bool()) // Deactive Done
    val flit          = Decoupled(new CHIBundleDAT(chiParams))
    val dataTDB       = Decoupled(new RnDBInData)
    val reqBufDBIDVec = Vec(nodeParam.nrReqBuf, Flipped(Valid(new Bundle {
      val bankId      = UInt(bankBits.W)
      val dbid        = UInt(dbIdBits.W)
    })))
  })

// --------------------- Modules declaration --------------------- //
  val txDat   = Module(new InboundFlitCtrl(gen = new CHIBundleDAT(chiParams), lcrdMax = 2, nodeParam.aggregateIO))


// ------------------- Reg/Wire declaration ---------------------- //
  val flit    = Wire(Decoupled(new CHIBundleDAT(chiParams)))
  val selBuf  = Wire(new Bundle { val bankId = UInt(bankBits.W); val dbid = UInt(dbIdBits.W) })


// --------------------- Logic ----------------------------------- //
  /*
   * Connect txDat
   */
  txDat.io.txState  := io.txState
  io.allLcrdRetrun  := txDat.io.allLcrdRetrun
  txDat.io.chi      <> io.chi
  txDat.io.flit     <> flit

  /*
   * Connect io.flit
   */
  io.flit.valid     := flit.fire
  io.flit.bits      := flit.bits
  io.flit.bits.data := DontCare

  /*
   * Select reqBuf bankId and dbid
   */
  selBuf := io.reqBufDBIDVec(flit.bits.txnID(rnReqBufIdBits - 1, 0)).bits

  /*
   * Connec dataTDB
   */
  flit.ready                := io.dataTDB.ready
  io.dataTDB.valid          := io.flit.valid
  io.dataTDB.bits.data      := io.flit.bits.data
  io.dataTDB.bits.dataID    := io.flit.bits.dataID
  io.dataTDB.bits.dbid      := selBuf.dbid
  io.dataTDB.bits.to.idL0   := SLICE
  io.dataTDB.bits.to.idL1   := selBuf.bankId
  io.dataTDB.bits.to.idL2   := DontCare


// --------------------- Assertion ------------------------------- //
  assert(!io.flit.valid | io.flit.ready)
  assert(!io.flit.valid | io.reqBufDBIDVec(flit.bits.txnID(rnReqBufIdBits - 1, 0)).valid)

}