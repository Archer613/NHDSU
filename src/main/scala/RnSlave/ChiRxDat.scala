package DONGJIANG.RNSLAVE

import DONGJIANG. _
import DONGJIANG.CHI._
import DONGJIANG.IdL0._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class ChiRxDat(rnSlvId: Int)(implicit p: Parameters) extends DJModule {
  val nodeParam = djparam.rnNodeMes(rnSlvId)

// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val chi           = CHIChannelIO(new CHIBundleDAT(chiParams), nodeParam.aggregateIO)
    val rxState       = Input(UInt(LinkStates.width.W))
    val flit          = Flipped(Decoupled(new CHIBundleDAT(chiParams)))
    val dataFDB       = Flipped(Decoupled(new RnDBOutData))
    val dataFDBVal    = Flipped(Valid(UInt(rnReqBufIdBits.W)))
  })

// --------------------- Modules declaration --------------------- //
  val rxDat   = Module(new OutboundFlitCtrl(gen = new CHIBundleDAT(chiParams), lcrdMax = nodeParam.nrRnRxLcrdMax, nodeParam.aggregateIO))


// ------------------- Reg/Wire declaration ---------------------- //
  val flit    = Wire(Decoupled(new CHIBundleDAT(chiParams)))


// --------------------- Logic ----------------------------------- //
  /*
   * Connect txDat
   */
  rxDat.io.rxState  := io.rxState
  rxDat.io.chi      <> io.chi
  rxDat.io.flit     <> flit

  /*
   * Set flit value
   */
  io.flit <> flit

  /*
   * Set dataFDB ready
   */
  io.dataFDB.ready := io.flit.fire

  /*
   * Set dataFDBVal Value
   */
  io.dataFDBVal.valid := io.dataFDB.valid
  io.dataFDBVal.bits  := io.dataFDB.bits.to.idL2



// --------------------- Assertion ------------------------------- //
  assert(!io.flit.valid | io.flit.ready)
}