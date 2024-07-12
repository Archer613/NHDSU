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
  })

  // TODO: Delete the following code when the coding is complete
  io.taskSnp <> DontCare
  io.taskCpu <> DontCare
  io.taskMs <> DontCare
  io.dirRead <> DontCare
  io.mpTask <> DontCare
  io.dirRstFinish <> DontCare


// --------------------- Modules declaration ------------------------//




// --------------------- Reg/Wire declaration ------------------------//
  // blockTable
  val blockTableReg = RegInit(VecInit(Seq.fill(nrBlockSets) {
    VecInit(Seq.fill(nrBlockWays) {0.U.asTypeOf(new BlockTableEntry())})
  }))
  // clean/write blockTable
  val taskClean_s0       = Wire(Bool())
  val btWCVal            = Wire(Bool()) // Clean/Write block table set
  val btWCSet            = Wire(UInt(blockSetBits.W)) // Clean/Write block table set
  val btWTag            = Wire(UInt(blockTagBits.W)) // Write block table tag
  // select a way to store block mes
  val btRSet = Wire(UInt(blockSetBits.W)) // Read block table set
  val btRTag = Wire(UInt(blockTagBits.W)) // Read block table tag
  val invWayVec = Wire(Vec(nrBlockWays, Bool()))
  val blockWayNext = Wire(UInt(blockWayBits.W))
  // need to block cpu task
  val blockCpuTaskVec = Wire(Vec(nrBlockWays, Bool()))
  val blockCpuTask = WireInit(false.B)
  // s0
  val task_s0 = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val canGo_s0 = WireInit(false.B)
  val taskSelVec        = Wire(Vec(3, Bool()))
  val taskCleanSelVec   = Wire(Vec(3, Bool()))
  // s1
  val canGo_s1          = WireInit(false.B)
  val task_s1_g         = RegInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val dirAlreadyReadReg = RegInit(false.B)

  dontTouch(invWayVec)
  dontTouch(taskClean_s0)
  dontTouch(btWCVal)
  dontTouch(btWCSet)
  dontTouch(btRTag)
  dontTouch(btRSet)
  dontTouch(btWTag)
  dontTouch(task_s0)
  dontTouch(taskSelVec)
  dontTouch(blockCpuTask)
  dontTouch(blockCpuTaskVec)

// ------------------------ S0: Decide which task can enter mainpipe --------------------------//
  /*
   * Determine whether it need to block cputask
   * TODO: Add retry queue to
   */
  btRTag := io.taskCpu.bits.addr(addressBits - 1, blockSetBits + offsetBits)
  btRSet := io.taskCpu.bits.addr(blockTagBits - 1,  offsetBits)
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
  btWCVal := (task_s0.valid & canGo_s0 & task_s0.bits.writeBt) | taskClean_s0
  btWTag  := task_s0.bits.addr(addressBits - 1, blockSetBits + offsetBits)
  btWCSet := task_s0.bits.addr(blockTagBits - 1, offsetBits)

  when(btWCVal) {
    val writeTable = blockTableReg(btWCSet)(task_s0.bits.btWay)
    writeTable.valid := !taskClean_s0
    writeTable.tag := Mux(!taskClean_s0, btWTag, writeTable.tag)
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
  val btCTag = task_s0.bits.addr(addressBits - 1, blockSetBits + offsetBits)
  assert(Mux(taskClean_s0, blockTableReg(btWCSet)(task_s0.bits.btWay).valid, true.B), "Clean block table must be valid")
  assert(Mux(taskClean_s0, blockTableReg(btWCSet)(task_s0.bits.btWay).tag === btCTag, true.B), "Clean block table tag must match cleanTask.tag")
  assert(Mux(taskClean_s0, !task_s0.valid, true.B), "When clean block table, task_s0 cant be valid")
  assert(Mux(taskClean_s0 | task_s0.valid, !(task_s0.bits.cleanBt & task_s0.bits.writeBt), true.B), "Task writeBT and cleanBT cant be valid at the same time")
  assert(Mux(taskClean_s0 | task_s0.valid, !task_s0.bits.isChnlError, true.B), "Channel cant be error value")
  assert(Mux(io.taskCpu.valid, io.taskCpu.bits.from.idL0 === IdL0.CPU, true.B), "taskCpu should from CPU")
  assert(Mux(io.taskMs.valid, io.taskMs.bits.from.idL0 === IdL0.MASTER, true.B), "taskMs should from MASTER")
  assert(Mux(io.taskSnp.valid, io.taskSnp.bits.from.idL0 === IdL0.SLICE, true.B), "taskSnp should from SLICE")

}