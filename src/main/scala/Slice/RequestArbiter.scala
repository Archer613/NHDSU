package NHDSU.SLICE

import NHDSU._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.ParallelPriorityMux

class RequestArbiter()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
  val io = IO(new Bundle {
    // SnoopCtl task
    val taskSnp       = Flipped(Decoupled(new TaskBundle)) // Hard wire to MSHRs

    // CHI Channel task
    val taskCpu       = Flipped(Decoupled(new TaskBundle))
    val taskMs        = Flipped(Decoupled(new TaskBundle))

    // Send task to MainPipe
    val mpTask        = Decoupled(new TaskBundle)
    // TODO: Lock signals from MainPipe
    // Read directory
    val dirRead = Decoupled(new DirRead)
    // Directory reset finish
    val dirRstFinish  = Input(Bool())
    // Lock Signal from TxReqQueue
    val txReqQFull    = Input(Bool())
    // MainPipe S3 clean or lock BlockTable
    val mpBTReq       = Flipped(Decoupled(new Bundle {
        val addr      = UInt(addressBits.W)
        val btWay     = UInt(blockWayBits.W)
        val isClean   = Bool()
    }))
  })

  // TODO: Delete the following code when the coding is complete
  io.taskSnp <> DontCare
  io.taskCpu <> DontCare
  io.taskMs <> DontCare
  io.dirRead <> DontCare
  io.mpTask <> DontCare
  io.dirRstFinish <> DontCare


// --------------------- Modules declaration ------------------------//




// --------------------- Reg / Wire / Def declaration ------------------------//
  // blockTable
  val blockTableReg = RegInit(VecInit(Seq.fill(nrBlockSets) {
    VecInit(Seq.fill(nrBlockWays) {0.U.asTypeOf(new BlockTableEntry())})
  }))
  val taskClean_s0      = Wire(Bool())
  // clean/write blockTable s0
  val btWCVal_s0        = Wire(Bool()) // Clean/Write block table set
  val btWCSet_s0        = Wire(UInt(blockSetBits.W)) // Clean/Write block table set
  val btWTag_s0         = Wire(UInt(blockTagBits.W)) // Write block table tag
  val btWCWay_s0        = Wire(UInt(blockWayBits.W)) // Clean/Write block table way
  // clean/write blockTable s3
  val btWCVal_s3        = Wire(Bool()) // Clean/Write block table set
  val btWCSet_s3        = Wire(UInt(blockSetBits.W)) // Clean/Write block table set
  val btWTag_s3         = Wire(UInt(blockTagBits.W)) // Write block table tag
  val btWCWay_s3        = Wire(UInt(blockWayBits.W)) // Clean/Write block table way
  // select a way to store block mes
  val btRSet            = Wire(UInt(blockSetBits.W)) // Read block table set
  val btRTag            = Wire(UInt(blockTagBits.W)) // Read block table tag
  val invWayVec         = Wire(Vec(nrBlockWays, Bool()))
  val blockWayNext      = Wire(UInt(blockWayBits.W))
  // need to block cpu task
  val blockCpuTaskVec   = Wire(Vec(nrBlockWays, Bool()))
  val blockCpuTask      = WireInit(false.B)
  // s0
  val task_s0           = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val canGo_s0          = WireInit(false.B)
  val taskSelVec        = Wire(Vec(3, Bool()))
  val taskCleanSelVec   = Wire(Vec(3, Bool()))
  // s1
  val canGo_s1          = WireInit(false.B)
  val task_s1_g         = RegInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val dirAlreadyReadReg = RegInit(false.B)

  dontTouch(invWayVec)
  dontTouch(taskClean_s0)
  dontTouch(btWCVal_s0)
  dontTouch(btWCSet_s0)
  dontTouch(btWTag_s0)
  dontTouch(btWCVal_s3)
  dontTouch(btWCSet_s3)
  dontTouch(btWTag_s3)
  dontTouch(btRTag)
  dontTouch(btRSet)
  dontTouch(task_s0)
  dontTouch(taskSelVec)
  dontTouch(blockCpuTask)
  dontTouch(blockCpuTaskVec)

  def getBtTag(x: UInt): UInt = {
    if(!mpBlockBySet) {
      x(addressBits - 1, blockSetBits + offsetBits)
    } else {
      require(addressBits - sTagBits - 1 > blockSetBits + offsetBits)
      x(addressBits - sTagBits - 1, blockSetBits + offsetBits)
    }

  }
  def getBtSet(x: UInt): UInt = {
    x(blockTagBits - 1,  offsetBits)
  }
// ------------------------ S0: Decide which task can enter mainpipe --------------------------//
  /*
   * Determine whether it need to block cputask
   * TODO: Add retry queue to
   */
  btRTag := getBtTag(io.taskCpu.bits.addr)
  btRSet := getBtSet(io.taskCpu.bits.addr)
  blockCpuTaskVec := blockTableReg(btRSet).map { case b => b.valid & b.tag === btRTag }
  invWayVec := blockTableReg(btRSet).map { case b => !b.valid }
  blockWayNext := PriorityEncoder(invWayVec)
  blockCpuTask := blockCpuTaskVec.asUInt.orR | !invWayVec.asUInt.orR

  /*
   * Priority(!task.isClean): taskSnp > taskMs > taskCpu
   * Priority(task.isClean): taskSnp > taskMs > taskCpu
   */
  taskSelVec(0) := io.taskSnp.valid & !io.taskSnp.bits.cleanBt
  taskSelVec(1) := io.taskMs.valid  & !io.taskMs.bits.cleanBt
  taskSelVec(2) := io.taskCpu.valid & !io.taskCpu.bits.cleanBt & !blockCpuTask

  taskCleanSelVec(0) := io.taskSnp.valid & io.taskSnp.bits.cleanBt
  taskCleanSelVec(1) := io.taskMs.valid  & io.taskMs.bits.cleanBt
  taskCleanSelVec(2) := io.taskCpu.valid & io.taskCpu.bits.cleanBt

  taskClean_s0 := taskCleanSelVec.asUInt.orR

  /*
   * Priority: clean > !clean
   */
  task_s0.valid := taskSelVec.asUInt.orR
  task_s0.bits := Mux(taskClean_s0,
                  ParallelPriorityMux(taskCleanSelVec.asUInt, Seq(io.taskSnp.bits, io.taskMs.bits, io.taskCpu.bits)),
                  ParallelPriorityMux(taskSelVec.asUInt, Seq(io.taskSnp.bits, io.taskMs.bits, io.taskCpu.bits)))
  canGo_s0 := canGo_s1 | !task_s1_g.valid | taskClean_s0

  // set task block table value
  when(!taskClean_s0 & task_s0.bits.writeBt){ task_s0.bits.btWay := blockWayNext }

  /*
   * task ready
   */
  io.taskSnp.ready := canGo_s0 & task_s0.bits.from.idL0 === IdL0.SLICE
  io.taskMs.ready  := canGo_s0 & task_s0.bits.from.idL0 === IdL0.MASTER
  io.taskCpu.ready := canGo_s0 & (!blockCpuTask | io.taskCpu.bits.cleanBt) & task_s0.bits.from.idL0 === IdL0.CPU

  /*
   * Write/Clean block table when task_s0.valid and canGo_s0
   */
  btWCVal_s0 := (task_s0.valid & canGo_s0 & task_s0.bits.writeBt) | taskClean_s0
  btWTag_s0  := getBtTag(task_s0.bits.addr)
  btWCSet_s0 := getBtSet(task_s0.bits.addr)
  btWCWay_s0 := task_s0.bits.btWay

  btWCVal_s3 := io.mpBTReq.valid
  btWTag_s3  := getBtTag(io.mpBTReq.bits.addr)
  btWCSet_s3 := getBtSet(io.mpBTReq.bits.addr)
  // TODO: mp_s3 will block table
  assert(Mux(io.mpBTReq.valid, io.mpBTReq.bits.isClean, true.B))
  btWCWay_s3 := Mux(io.mpBTReq.bits.isClean, io.mpBTReq.bits.btWay, 0.U)
  io.mpBTReq.ready := true.B

  blockTableReg.zipWithIndex.foreach {
    case(table, set) =>
      table.zipWithIndex.foreach {
        case(table, way) =>
          val hit_s0 = set.U === btWCSet_s0 & way.U === task_s0.bits.btWay & btWCVal_s0
          val hit_s3 = set.U === btWCSet_s3 & way.U === io.mpBTReq.bits.btWay & btWCVal_s3
          when(hit_s0) {
            table.valid := !taskClean_s0
            when(!taskClean_s0) { table.tag := btWTag_s0 }
          }.elsewhen(hit_s3) {
            table.valid := !io.mpBTReq.bits.isClean
            when(!io.mpBTReq.bits.isClean) { table.tag := btWTag_s3 }
          }
          assert(!(hit_s0 & hit_s3))
      }
  }



// ------------------------ S1: Read Dir and send task to MainPipe --------------------------//

  task_s1_g.valid := Mux(task_s0.valid, true.B, task_s1_g.valid & !canGo_s1)
  task_s1_g.bits := Mux(task_s0.valid & canGo_s0 , task_s0.bits, task_s1_g.bits)
  canGo_s1 := io.mpTask.ready & (io.dirRead.ready | dirAlreadyReadReg | !task_s1_g.bits.readDir)

  /*
   * Send mpTask to mainpipe
   */
  io.mpTask.valid := task_s1_g.valid & canGo_s1
  io.mpTask.bits := task_s1_g.bits

  /*
   * Read Directory
   */
  dirAlreadyReadReg := Mux(dirAlreadyReadReg, !io.mpTask.fire, io.dirRead.fire & !canGo_s1)
  io.dirRead.valid := task_s1_g.valid & !dirAlreadyReadReg & task_s1_g.bits.readDir
  io.dirRead.bits.addr := task_s1_g.bits.addr
  io.dirRead.bits.self.alreayUseWayOH := 0.U // TODO
  io.dirRead.bits.self.refill := true.B
  io.dirRead.bits.client.alreayUseWayOH := 0.U // TODO
  io.dirRead.bits.client.refill := true.B




// ------------------------ Assertion --------------------------//
  // s0
  val btCTag_s0 = getBtTag(task_s0.bits.addr)
  assert(Mux(taskClean_s0, blockTableReg(btWCSet_s0)(btWCWay_s0).valid, true.B), "Clean block table must be valid")
  assert(Mux(taskClean_s0, blockTableReg(btWCSet_s0)(btWCWay_s0).tag === btCTag_s0, true.B), "Clean block table tag[0x%x] must match cleanTask.tag[0x%x]", blockTableReg(btWCSet_s0)(btWCWay_s0).tag, btCTag_s0)
  assert(Mux(taskClean_s0, !task_s0.valid, true.B), "When clean block table, task_s0 cant be valid")
  assert(Mux(taskClean_s0 | task_s0.valid, !(task_s0.bits.cleanBt & task_s0.bits.writeBt), true.B), "Task writeBT and cleanBT cant be valid at the same time")
  assert(Mux(taskClean_s0 | task_s0.valid, !task_s0.bits.isChnlError, true.B), "Channel cant be error value")
  // s3
  val btCTag_s3 = getBtTag(io.mpBTReq.bits.addr)
  assert(Mux(io.mpBTReq.fire & io.mpBTReq.bits.isClean, blockTableReg(btWCSet_s3)(btWCWay_s3).valid, true.B), "Clean block table must be valid")
  assert(Mux(io.mpBTReq.fire & io.mpBTReq.bits.isClean, blockTableReg(btWCSet_s3)(btWCWay_s3).tag === btCTag_s3, true.B), "Clean block table tag must match cleanTask.tag")
  // io.taskXXX
  assert(Mux(taskClean_s0 | task_s0.valid, !task_s0.bits.isChnlError, true.B), "Channel cant be error value")
  assert(Mux(io.taskCpu.valid, io.taskCpu.bits.from.idL0 === IdL0.CPU, true.B), "taskCpu should from CPU")
  assert(Mux(io.taskMs.valid, io.taskMs.bits.from.idL0 === IdL0.MASTER, true.B), "taskMs should from MASTER")
  assert(Mux(io.taskSnp.valid, io.taskSnp.bits.from.idL0 === IdL0.SLICE, true.B), "taskSnp should from SLICE")

  // TIMEOUT CHECK
  blockTableReg.zipWithIndex.foreach {
    case(vec, set) =>
      val cntVecReg = RegInit(VecInit(Seq.fill(nrBlockWays) { 0.U(64.W) }))
      cntVecReg.zip(vec.map(_.valid)).foreach { case (cnt, v) => cnt := Mux(!v, 0.U, cnt + 1.U) }
      cntVecReg.zipWithIndex.foreach { case (cnt, way) => assert(cnt < 5000.U, "ReqArb blockTable[%d][%d] TIMEOUT", set.U, way.U) }
  }
}