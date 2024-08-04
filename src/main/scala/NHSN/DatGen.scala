package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU.CHI._
import NHDSU._
import NHDSU.CHI.CHIOp._

class DatGen (implicit p : Parameters) extends DSUModule {
  // -------------------------- IO declaration -----------------------------//
    val io = IO(new Bundle {
      val readReqFlit      = Flipped(Decoupled(new CHIBundleREQ(chiBundleParams)))
      val dataFlit         = Decoupled(new CHIBundleDAT(chiBundleParams))
      val readReg          = Output(new ReadReg(chiBundleParams))
      val rspReg           = Input(Vec(nrBeat,UInt(beatBits.W)))
      val bufNoEmpty       = Output(Bool())
    })
 // --------------------------- Module define ------------------------------//
  val queue                = Module(new Queue(new CHIBundleDAT(chiBundleParams), entries = dsuparam.nrSnTxLcrdMax, pipe = true, flow = false, hasFlush = false))

 // -------------------------- Wire/Reg define ----------------------------//
  val readMem              = WireInit(0.U.asTypeOf(io.readReg))
  val respMem              = Wire(Vec(nrBeat,UInt(beatBits.W)))
  val enqValid             = RegNext(io.readReqFlit.fire  & io.readReqFlit.bits.opcode === REQ.ReadNoSnp)

  val dataFlitEnq          = WireInit(0.U.asTypeOf(io.dataFlit.bits))
  val dataFlitEnqReg       = RegInit(0.U.asTypeOf(io.dataFlit.bits))

  val queueFull            = Wire(Bool())
  val queueEmpty           = Wire(Bool())



 // ------------------------------ Logic ---------------------------------//

  readMem.addr            := io.readReqFlit.bits.addr
  respMem                 := io.rspReg

  queueFull               := queue.io.count === dsuparam.nrSnTxLcrdMax.U
  queueEmpty              := queue.io.count === 0.U
/* 
 * Queue enq and deq logic
 */
  queue.io.deq.ready      := io.dataFlit.ready
  queue.io.enq.valid      := io.readReqFlit.valid & io.readReqFlit.bits.opcode === REQ.ReadNoSnp || enqValid


  dataFlitEnq.tgtID       := dsuparam.idmap.HNID.U
  dataFlitEnq.srcID       := dsuparam.idmap.SNID.U
  dataFlitEnq.data        := respMem(0)
  dataFlitEnq.dataID      := 0.U
  dataFlitEnq.opcode      := DAT.CompData
  dataFlitEnq.txnID       := io.readReqFlit.bits.txnID

  dataFlitEnqReg.tgtID    := dsuparam.idmap.HNID.U
  dataFlitEnqReg.srcID    := dsuparam.idmap.SNID.U
  dataFlitEnqReg.data     := respMem(1)
  dataFlitEnqReg.dataID   := 2.U
  dataFlitEnqReg.opcode   := DAT.CompData
  dataFlitEnqReg.txnID    := io.readReqFlit.bits.txnID

  // when(io.readReqFlit.fire & io.readReqFlit.bits.opcode === REQ.ReadNoSnp){
    queue.io.enq.bits     := dataFlitEnq
  // }
  when(enqValid){
    queue.io.enq.bits     := dataFlitEnqReg
  }



/* 
 * Output logic
 */

  io.readReg              := readMem
  io.dataFlit.valid       := !queueEmpty
  io.dataFlit.bits        := Mux(queue.io.deq.fire, queue.io.deq.bits, 0.U.asTypeOf(io.dataFlit.bits))
  io.readReqFlit.ready    := !queue.io.enq.valid & !queueFull
  io.bufNoEmpty           := !queueEmpty
}
