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
    // snpCtrl <-> cpuslave
    val snpTask       = Decoupled(new TaskBundle())
    val snpResp       = Flipped(ValidIO(new TaskRespBundle()))
    // mainpipe <-> cpuslave
    val cpuTask       = Flipped(Decoupled(new TaskBundle()))
    val cpuResp       = Decoupled(new TaskRespBundle())
    // dataBuffer <-> CPUSLAVE
    val dbSigs2Cpu    = new Bundle {
      val req           = Flipped(ValidIO(new DBReq()))
      val wResp         = Decoupled(new DBResp())
      val dataFromDB    = Decoupled(new DBOutData())
      val dataToDB       = Flipped(ValidIO(new DBInData()))
    }
    // dataBuffer <-> DSUMASTER
    val dbSigs2Ms     = new Bundle {
      val req           = Flipped(ValidIO(new DBReq()))
      val wResp         = ValidIO(new DBResp())
      val dataFromDB    = Decoupled(new DBOutData())
      val dataToDB      = Flipped(ValidIO(new DBInData()))
    }
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
  val mpRespQueue = Module(new Queue(gen = new TaskRespBundle(),entries = mpRespQDepth, pipe = true, flow = true))

  dontTouch(dataBuffer.io)
  dontTouch(dataStorage.io)
  dontTouch(directory.io)
  dontTouch(mainPipe.io)
  dontTouch(reqArb.io)
  dontTouch(snpCtl.io)

// --------------------- Connection ------------------------//
  dataBuffer.io.dbSigs2Cpu <> io.dbSigs2Cpu
  dataBuffer.io.dbSigs2Ms <> io.dbSigs2Ms
  dataBuffer.io.mpReq <> mainPipe.io.dbReq
  dataBuffer.io.dbSigs2DS <> dataStorage.io.dbSigs2DB

  dataStorage.io.mpReq <> mainPipe.io.dsReq

  directory.io.dirRead <> reqArb.io.dirRead
  directory.io.sDirResp <> mainPipe.io.sDirResp
  directory.io.sDirWrite <> mainPipe.io.sDirWrite
  directory.io.cDirResp <> mainPipe.io.cDirResp
  directory.io.cDirWrite <> mainPipe.io.cDirWrite

  reqArb.io.taskSnp <> snpCtl.io.mpResp
  reqArb.io.taskCpu <> io.cpuTask
  reqArb.io.taskMs <> io.msResp
  reqArb.io.mpTask <> mainPipe.io.arbTask
  reqArb.io.dirRstFinish :=  directory.io.resetFinish
  reqArb.io.txReqQFull := (mpReqQueue.entries.asUInt - mpReqQueue.io.count <= pipeDepth.asUInt)

  snpCtl.io.snpTask <> io.snpTask
  snpCtl.io.snpResp <> io.snpResp
  snpCtl.io.mpTask <> mainPipe.io.snpTask

  mainPipe.io.cpuResp <> mpRespQueue.io.enq
  mainPipe.io.msTask <> mpReqQueue.io.enq

  mpRespQueue.io.deq <> io.cpuResp
  mpReqQueue.io.deq <> io.msTask

  io.valid := true.B
}