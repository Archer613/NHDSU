package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils._

class Slice()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    val valid         = Output(Bool())
    val sliceId       = Input(UInt(bankBits.W))
    // snpCtrl <-> cpuslave
    val snpTask       = Decoupled(new SnpTaskBundle())
    val snpResp       = Flipped(ValidIO(new SnpRespBundle()))
    // mainpipe <-> cpuslave
    val cpuClTask     = Flipped(Decoupled(new WCBTBundle()))
    val cpuTask       = Flipped(Decoupled(new TaskBundle()))
    val cpuResp       = Decoupled(new RespBundle())
    // dataBuffer <-> CPUSLAVE
    val dbSigs2Cpu    = Flipped(new CpuDBBundle())
    // dataBuffer <-> DSUMASTER
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
  dataBuffer.io.cpu2db <> io.dbSigs2Cpu
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
  reqArb.io.taskCpu <> io.cpuTask
  reqArb.io.taskMs <> io.msResp
  reqArb.io.mpTask <> mainPipe.io.arbTask
  reqArb.io.dirRstFinish :=  directory.io.resetFinish
  reqArb.io.txReqQFull := (mpReqQueue.entries.asUInt - mpReqQueue.io.count <= pipeDepth.asUInt)
  reqArb.io.wcBTReqVec(2) <> io.cpuClTask
  reqArb.io.wcBTReqVec(1) <> io.msClTask
  reqArb.io.wcBTReqVec(0) <> mainPipe.io.wcBTReq
  reqArb.io.snpFreeNum := snpCtl.io.freeNum
  reqArb.io.mpReleaseSnp := mainPipe.io.releaseSnp

  snpCtl.io.snpTask <> io.snpTask
  snpCtl.io.snpResp <> io.snpResp
  snpCtl.io.mpTask <> mainPipe.io.snpTask

  mainPipe.io.sliceId := io.sliceId
  mainPipe.io.cpuResp <> mpRespQueue.io.enq
  mainPipe.io.msTask <> mpReqQueue.io.enq

  mpRespQueue.io.deq <> io.cpuResp
  mpReqQueue.io.deq <> io.msTask

  io.valid := true.B
}