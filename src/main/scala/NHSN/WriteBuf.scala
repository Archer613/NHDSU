package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU.CHI._
import NHDSU._
import NHDSU.CHI.CHIOp._


class WriteBuf (implicit p : Parameters) extends DSUModule {
 // -------------------------- IO declaration -----------------------------//
    val io = IO(new Bundle {
      val reqFlit                  = Flipped(Decoupled(new CHIBundleREQ(chiBundleParams)))
      val datFlit                  = Flipped(Decoupled(new CHIBundleDAT(chiBundleParams)))
      val wrDat                    = Decoupled(new WriteData(chiBundleParams))
    })
 // -------------------------- Wire/Reg define -----------------------------//
  val fsmReg                       = RegInit(VecInit(Seq.fill(nrReadCtlEntry) { 0.U.asTypeOf(new WriteBufTableEntry()) }))
  val stateIdleVec                 = Wire(Vec(nrReadCtlEntry, Bool()))
  val selIdleFsm                   = WireInit(0.U(rcEntryBits.W))

  val selWaitDatFsm                = WireInit(0.U(rcEntryBits.W))
  val selWaitDatVec                = Wire(Vec(nrReadCtlEntry,Bool()))
  val selWaitDat                   = OHToUInt(selWaitDatVec)

  val writeData                    = WireInit(0.U.asTypeOf(io.wrDat.bits))



// -------------------------- Logic -----------------------------//

  stateIdleVec.zip(fsmReg.map(_.state)).foreach{ case(s, f) => s := f === WrState.IDLE}
  selIdleFsm                      := PriorityEncoder(stateIdleVec)
/* 
 * Select a idle state of fsmReg to save key information
 */
  when(io.reqFlit.fire & io.reqFlit.bits.opcode === REQ.WriteNoSnpFull){
    fsmReg(selIdleFsm).state      := WrState.WAITDATA
    fsmReg(selIdleFsm).addr       := io.reqFlit.bits.addr
    fsmReg(selIdleFsm).txnID      := io.reqFlit.bits.txnID
  }
  selWaitDatVec.zip(fsmReg.map(_.txnID)).foreach{ case(s, f) => s := f === io.datFlit.bits.txnID}
  when(io.datFlit.fire){
    
    switch(fsmReg(selWaitDat).state){
      is(WrState.WAITDATA){
        fsmReg(selWaitDat).state  := WrState.SENDDAT1
        writeData.addr            := fsmReg(selWaitDat).addr
        writeData.data            := io.datFlit.bits.data
        writeData.dataID          := io.datFlit.bits.dataID
        writeData.txnID           := fsmReg(selWaitDat).txnID
      }
      is(WrState.SENDDAT1){
        fsmReg(selWaitDat).state  := WrState.IDLE
        writeData.addr            := fsmReg(selWaitDat).addr
        writeData.data            := io.datFlit.bits.data
        writeData.dataID          := io.datFlit.bits.dataID
        writeData.txnID           := fsmReg(selWaitDat).txnID
      }
    }
  }
  


/* 
 * Output
 */

  io.reqFlit.ready             := stateIdleVec.reduce(_ | _)
  io.datFlit.ready             := true.B
  io.wrDat.bits                := Mux(io.datFlit.fire, writeData, 0.U.asTypeOf((writeData)))
  io.wrDat.valid               := io.datFlit.valid


  
}