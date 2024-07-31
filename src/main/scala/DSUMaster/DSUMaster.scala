package NHDSU.DSUMASTER

import NHDSU._
import NHDSU.CHI.{CHIBundleDownstream, CHILinkCtrlIO}
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

class DSUMaster()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // CHI
    val chi           = CHIBundleDownstream(chiBundleParams)
    val chiLinkCtrl   = new CHILinkCtrlIO()
    // mainpipe
    val clTask        = Decoupled(new WCBTBundle())
    val mpTask        = Flipped(Decoupled(new TaskBundle())) // Consider splitting the Bundle into rReq and wbReq
    val mpResp        = Decoupled(new TaskBundle())
    // dataBuffer
    val dbSigs        = new MsDBBundle()
  })

  // TODO: Delete the following code when the coding is complete
  io.chi <> DontCare

// --------------------- Modules declaration ------------------------//
  val chiCtrl = Module(new ProtocolLayerCtrl())
  val rxDat = Module(new DsuChiRxDat())
  val rxRsp = Module(new DsuChiRxRsp())
  val txDat = Module(new DsuChiTxDat())
  val txReq = Module(new DsuChiTxReq())
  val readCtl = Module(new ReadCtl())


// --------------------- Wire declaration ------------------------//
  val wbReq = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle())))
  val rReq = WireInit(0.U.asTypeOf(Decoupled(new TaskBundle())))

  val txReqWb = WireInit(0.U.asTypeOf(Decoupled(new DsuChiTxReqBundle())))

  dontTouch(wbReq)
  dontTouch(rReq)

// --------------------- Connection ------------------------//
  /*
   * id map in HN <-> SN
   *
   * ReadReq:
   * CHI:
   * *** Requester(TxnID = A)   ----[ReadNoSnp]---->    Completer(TxnID = A)
   * *** Requester(TxnID = A)   <----[CompData]-----    Completer(TxnID = A)
   * Architecture:
   * *** DataBuffer                   ----[wResp(dbid = A)]---->   ReadCtl(fsmReg.txnid = A)
   * *** ReadCtl(fsmReg.txnid = A)    ----[task(txnid = A)]---->   ChiTxReq
   * *** ChiRxDat                     ----[resp(txnid = A)]---->   ReadCtl(fsmReg.txnid = A)
   * *** ReadCtl(fsmReg.txnid = A)    ----[mpResp(dbid = A)]--->   MainPipe
   *
   *
   * WriteReq:
   * A' is used to identify the request as a WriteBack
   * A' = Cat(1'b1, A(7.W))
   * A  = Cat(btSet, btWay)
   * CHI:
   * *** Requester(TxnID = A')           ----[WriteNoSnp]---->   Completer(TxnID = A')
   * *** Requester(TxnID = A', DBID = B) <---[CompDBIDResp]---   Completer(TxnID = A', DBID = B)
   * *** Requester(TxnID = B)            ----[NCBWrData_I]--->   Completer(TxnID = B)
   * Architecture:
   * *** MainPipe                     ----[msTask(dbid = A')]--->              ChiTxReq
   * *** ChiRxRsp                     ----[resp(txnid = A', dbid = B)]---->    ChiTxDat
   *
   */


  /*
   * Convert wbReq to txReqWb
   */
  txReqWb.valid       := wbReq.valid
  txReqWb.bits.opcode := wbReq.bits.opcode
  txReqWb.bits.addr   := wbReq.bits.addr
  val replTxnid       = WireInit(0.U((chiTxnidBits - 1).W))
  replTxnid           := Cat(parseBTAddress(wbReq.bits.addr)._2, wbReq.bits.btWay)
  txReqWb.bits.txnid  := Cat(1.U, replTxnid)
  wbReq.ready         := txReqWb.ready


  /*
   * connect io.chi <-> chiXXX <-> dataBuffer/readCtl
   */
  chiCtrl.io.chiLinkCtrl <> io.chiLinkCtrl
  chiCtrl.io.rxAllLcrdRetrun := rxDat.io.allLcrdRetrun & rxRsp.io.allLcrdRetrun
  chiCtrl.io.readCtlFsmVal := readCtl.io.readCtlFsmVal

  rxDat.io.chi <> io.chi.rxdat
  rxDat.io.rxState := chiCtrl.io.rxState
  rxDat.io.resp <> readCtl.io.rxDatResp
  rxDat.io.dataTDB <> io.dbSigs.dataTDB

  rxRsp.io.chi <> io.chi.rxrsp
  rxRsp.io.rxState := chiCtrl.io.rxState
  rxRsp.io.resp2rc <> readCtl.io.rxRspResp
  rxRsp.io.resp2dat <> txDat.io.rspResp

  txDat.io.chi <> io.chi.txdat
  txDat.io.txState := chiCtrl.io.txState
  txDat.io.dataFDB <> io.dbSigs.dataFDB
  txDat.io.clTask <> io.clTask

  txReq.io.chi <> io.chi.txreq
  txReq.io.txState := chiCtrl.io.txState
  // txReq.io.task: priority is txReqWb
  txReq.io.task.valid := txReqWb.valid | readCtl.io.txReqRead.valid
  txReq.io.task.bits := Mux(txReqWb.valid, txReqWb.bits, readCtl.io.txReqRead.bits)
  txReqWb.ready := txReq.io.task.ready
  readCtl.io.txReqRead.ready := txReq.io.task.ready & !txReqWb.valid

  // readCtl
  readCtl.io.mpTask <> rReq
  readCtl.io.dbWReq <> io.dbSigs.wReq
  readCtl.io.dbWResp <> io.dbSigs.wResp
  readCtl.io.mpResp <> io.mpResp


  // req sel by io.mpTask.bits.isWB
  wbReq.valid := io.mpTask.valid & io.mpTask.bits.isWB
  rReq.valid := io.mpTask.valid & !io.mpTask.bits.isWB
  wbReq.bits := io.mpTask.bits
  rReq.bits := io.mpTask.bits
  io.mpTask.ready := Mux(io.mpTask.bits.isWB, wbReq.ready, rReq.ready)


}