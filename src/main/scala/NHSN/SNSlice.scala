package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU.CHI._
import NHDSU._
import NHDSU.CHI.CHIOp._

class SNSlice (implicit p : Parameters) extends DSUModule {
 // -------------------------- IO declaration -----------------------------//
    val io = IO(new Bundle {
      val chi              = CHIBundleUpstream(chiBundleParams)
      val chiLinkCtrl      = Flipped(new CHILinkCtrlIO())
      val readReq          = Output(new ReadReg(chiBundleParams))
      val readResp         = Input(Vec(nrBeat,UInt(beatBits.W)))
      val writeMem         = Decoupled(new WriteReg(chiBundleParams))
    })

 // ------------------------ Module declaration --------------------------//
  val snChiCtrl             = Module(new ProtocolLayerCtrl())
  val snChiTxReq            = Module(new DSUChiTxReq())
  val snChiTxDat            = Module(new DSUChiTxDat())
  val snChiRxDat            = Module(new DSUChiRxDat())
  val snChiRxRsp            = Module(new DSUChiRxRsp())
  val writeBuffer           = Module(new WriteBuf())
  val writeDat              = Module(new WrDat())
  val rspGen                = Module(new RspGen())
  val datGen                = Module(new DatGen())




// ------------------------------ Logic --------------------------------//

  snChiCtrl.io.txAllLcrdRetrun   := snChiTxReq.io.lcrdReturn || snChiTxDat.io.lcrdReturn
  snChiCtrl.io.rspFlitBufNoEmpty := rspGen.io.bufNoEmpty
  snChiCtrl.io.datFlitBufNoEmpty := datGen.io.bufNoEmpty


  snChiTxReq.io.txState          := snChiCtrl.io.txState
  snChiTxDat.io.txState          := snChiCtrl.io.txState

  writeBuffer.io.reqFlit         <> snChiTxReq.io.flit
  writeBuffer.io.datFlit         <> snChiTxDat.io.flit
  writeBuffer.io.dataRegEnq      := datGen.io.regEnq
  writeBuffer.io.datQueueFull    := datGen.io.datQueueFull
  writeBuffer.io.rspQueueFull    := rspGen.io.rspQueueFull

  writeDat.io.writeDataIn        <> writeBuffer.io.wrDat

  rspGen.io.reqFlitIn            <> snChiTxReq.io.flit
  rspGen.io.fsmFull              := writeBuffer.io.fsmFull
  rspGen.io.datQueueFull         := datGen.io.datQueueFull
  rspGen.io.dataRegEnq           := datGen.io.regEnq

  datGen.io.readReqFlit          <> snChiTxReq.io.flit
  datGen.io.rspReg               := io.readResp
  datGen.io.fsmFull              := writeBuffer.io.fsmFull
  datGen.io.rspQueueFull         := rspGen.io.rspQueueFull

  snChiRxDat.io.flit             <> datGen.io.dataFlit
  snChiRxDat.io.rxState          := snChiCtrl.io.rxState
  snChiRxRsp.io.rxState          := snChiCtrl.io.rxState
  snChiRxRsp.io.flit             <> rspGen.io.rspFlitOut

/* 
 * Output logic
 */

  io.chiLinkCtrl          <> snChiCtrl.io.chiLinkCtrl
  io.chi.txreq            <> snChiTxReq.io.chi
  io.chi.txdat            <> snChiTxDat.io.chi
  io.chi.txrsp            <> DontCare
  io.chi.rxdat            <> snChiRxDat.io.chi
  io.chi.rxrsp            <> snChiRxRsp.io.chi
  io.chi.rxsnp            <> DontCare

  io.writeMem             <> writeDat.io.writeDataOut
  io.readReq              <> datGen.io.readReg
  


}
