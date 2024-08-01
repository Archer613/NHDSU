package DONGJIANG.SLICE

import DONGJIANG._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils._

class Slice()(implicit p: Parameters) extends DJModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val valid         = Output(Bool())
    val sliceId       = Input(UInt(bankBits.W))
    // snpCtrl <-> rnSlave
    val snpTask       = Decoupled(new SnpTaskBundle())
    val snpResp       = Flipped(ValidIO(new SnpRespBundle()))
    // mainpipe <-> rnSlave
    val rnClTask      = Flipped(Decoupled(new WCBTBundle()))
    val rnTask        = Flipped(Decoupled(new TaskBundle()))
    val rnResp        = Decoupled(new RespBundle())
    // dataBuffer <-> rnSlave
    val dbSigs2Rn     = Flipped(new RnDBBundle())
    // dataBuffer <-> SNMASTER
    val msClTask      = Flipped( Decoupled(new WCBTBundle()))
    val dbSigs2Ms     = Flipped(new MsDBBundle())
    val msTask        = Decoupled(new TaskBundle())
    val msResp        = Flipped(Decoupled(new TaskBundle()))
  })

// --------------------- Modules declaration ------------------------//
  val pipeDepth = 4 // 1(reqArb) + 3(mainPipe)
  val mpReqQDepth = pipeDepth + 2
  val mpRespQDepth = 2

  val dataBuffer = Module(new DataBuffer())
  val dataStorage = Module(new DataStorage())
  val directory = Module(new Directory())
  val mainPipe = Module(new MainPipe())
  val reqArb = Module(new RequestArbiter())
  val snpCtl = Module(new SnoopCtlWrapper())
  val mpReqQueue = Module(new Queue(gen = new TaskBundle(), entries = mpReqQDepth, pipe = true, flow = true))
  val mpRespQueue = Module(new Queue(gen = new RespBundle(),entries = mpRespQDepth, pipe = true, flow = true))

  dontTouch(dataBuffer.io)
  dontTouch(dataStorage.io)
  dontTouch(directory.io)
  dontTouch(mainPipe.io)
  dontTouch(reqArb.io)
  dontTouch(snpCtl.io)

// --------------------- Connection ------------------------//
  dataBuffer.io.rn2db <> io.dbSigs2Rn
  dataBuffer.io.ms2db <> io.dbSigs2Ms
  dataBuffer.io.mpRCReq <> mainPipe.io.dbRCReq
  dataBuffer.io.dsRCReq <> dataStorage.io.dbRCReq
  dataBuffer.io.ds2db <> dataStorage.io.dbSigs2DB

  dataStorage.io.mpReq <> mainPipe.io.dsReq

  directory.io.dirRead <> reqArb.io.dirRead
  directory.io.dirResp <> mainPipe.io.dirResp
  directory.io.sDirWrite <> mainPipe.io.sDirWrite
  directory.io.cDirWrite <> mainPipe.io.cDirWrite

  reqArb.io.taskSnp <> snpCtl.io.mpResp
  reqArb.io.taskRn <> io.rnTask
  reqArb.io.taskMs <> io.msResp
  reqArb.io.mpTask <> mainPipe.io.arbTask
  reqArb.io.dirRstFinish :=  directory.io.resetFinish
  reqArb.io.txReqQFull := (mpReqQueue.entries.asUInt - mpReqQueue.io.count <= pipeDepth.asUInt)
  reqArb.io.wcBTReqVec(2) <> io.rnClTask
  reqArb.io.wcBTReqVec(1) <> io.msClTask
  reqArb.io.wcBTReqVec(0) <> mainPipe.io.wcBTReq
  reqArb.io.snpFreeNum := snpCtl.io.freeNum
  reqArb.io.mpReleaseSnp := mainPipe.io.releaseSnp

  snpCtl.io.snpTask <> io.snpTask
  snpCtl.io.snpResp <> io.snpResp
  snpCtl.io.mpTask <> mainPipe.io.snpTask

  mainPipe.io.sliceId := io.sliceId
  mainPipe.io.rnResp <> mpRespQueue.io.enq
  mainPipe.io.msTask <> mpReqQueue.io.enq

  mpRespQueue.io.deq <> io.rnResp
  mpReqQueue.io.deq <> io.msTask

  io.valid := true.B
}