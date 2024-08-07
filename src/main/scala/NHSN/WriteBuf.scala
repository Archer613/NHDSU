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
      val fsmFull                  = Output(Bool())
      val dataRegEnq               = Input(Bool())
      val rspQueueFull             = Input(Bool())
      val datQueueFull             = Input(Bool())
    })
 // -------------------------- Wire/Reg define -----------------------------//
  val fsmReg                       = RegInit(VecInit(Seq.fill(nrReadCtlEntry) { 0.U.asTypeOf(new WriteBufTableEntry()) }))
  val stateIdleVec                 = Wire(Vec(nrReadCtlEntry, Bool()))
  val selIdleFsm                   = WireInit(0.U(rcEntryBits.W))

  val selWaitDatFsm                = WireInit(0.U(rcEntryBits.W))
  val selWaitDatVec                = Wire(Vec(nrReadCtlEntry,Bool()))
  val selWaitDat                   = OHToUInt(selWaitDatVec)
  val selSendDatVec                = Wire(Vec(nrReadCtlEntry,Bool()))
  val selSendDat                   = OHToUInt(selSendDatVec)

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
  selWaitDatVec.zip(fsmReg).foreach{ case(s, f)  => s := f.txnID === io.datFlit.bits.txnID & f.state === WrState.WAITDATA}
  selSendDatVec.zip(fsmReg).foreach { case(s, f) => s := f.txnID === io.datFlit.bits.txnID & f.state === WrState.SENDDAT1}

  
  when(io.datFlit.fire){
    
    switch(fsmReg(selWaitDat).state){
      is(WrState.WAITDATA){
        fsmReg(selWaitDat).state  := WrState.SENDDAT1
        writeData.addr            := fsmReg(selWaitDat).addr
        writeData.data            := io.datFlit.bits.data
        writeData.dataID          := io.datFlit.bits.dataID
        writeData.txnID           := fsmReg(selWaitDat).txnID
      }
    }
    switch(fsmReg(selSendDat).state){
      is(WrState.SENDDAT1){
        fsmReg(selSendDat).state  := WrState.IDLE
        writeData.addr            := fsmReg(selSendDat).addr
        writeData.data            := io.datFlit.bits.data
        writeData.dataID          := io.datFlit.bits.dataID
        writeData.txnID           := fsmReg(selSendDat).txnID
      }
    }
  }
  


/* 
 * Output
 */

  io.reqFlit.ready             := stateIdleVec.reduce(_ | _) & !io.dataRegEnq & !io.rspQueueFull & !io.datQueueFull
  io.datFlit.ready             := true.B
  io.wrDat.bits                := Mux(io.datFlit.fire, writeData, 0.U.asTypeOf((writeData)))
  io.wrDat.valid               := io.datFlit.valid
  io.fsmFull                   := !stateIdleVec.reduce(_ | _)


  
}