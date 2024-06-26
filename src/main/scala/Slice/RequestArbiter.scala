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
    // Lock signals from MainPipe
    val lockAddr      = Flipped(ValidIO(new Bundle {
      val set           = UInt(setBits.W)
      val tag           = UInt(tagBits.W)
    }))
    val lockWay       = Flipped(ValidIO(new Bundle {
      val wayOH         = UInt(dsuparam.ways.W)
      val set           = UInt(setBits.W)
    }))
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
  val blockTableReg = RegInit(VecInit(Seq.fill(nrBlockSets) {
    VecInit(Seq.fill(nrBlockWays) {0.U.asTypeOf(new BlockTableEntry())})
  }))
  val taskSelVec = Wire(Vec(3, Bool()))
  val invWayVec = Wire(Vec(nrBlockWays, Bool()))
  val blockWayNext = Wire(UInt(blockWayBits.W))
  val task_s0 = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val canGo_s0 = WireInit(false.B)
  val canGo_s1 = WireInit(false.B)
  val task_s1_g = RegInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val blockCpuTaskVec = Wire(Vec(nrBlockWays, Bool()))
  val blockCpuTask = WireInit(false.B)
  val dirAlreadyReadReg = RegInit(false.B)

  dontTouch(task_s0)
  dontTouch(taskSelVec)
  dontTouch(blockCpuTask)
  dontTouch(blockCpuTaskVec)

// ------------------------ S0: Decide which task can enter mainpipe --------------------------//
  /*
   * If task is clean, invalid blockTable
   */



  /*
   * Determine whether it need to block cputask
   */
  blockCpuTaskVec := blockTableReg(io.taskCpu.bits.set(blockSetBits-1, 0)).map {
    case b => b.valid & b.tag === io.taskCpu.bits.tag & b.set === io.taskCpu.bits.set(setBits-1, blockSetBits) & b.bank === io.taskCpu.bits.bank
  }
  invWayVec := blockTableReg(io.taskCpu.bits.set(blockSetBits - 1, 0)).map {
    case b => b.valid
  }
  blockWayNext := ParallelPriorityMux(invWayVec.zipWithIndex.map {
    case (b, i) => (b, (1 << i).U)
  })
  blockCpuTask := blockCpuTaskVec.asUInt.orR | invWayVec.asUInt.andR


  /*
   * Priority(!task.isClean): taskSnp > taskMs > taskCpu
   */
  taskSelVec(0) := io.taskSnp.valid & !io.taskSnp.bits.isClean
  taskSelVec(1) := io.taskMs.valid  & !io.taskMs.bits.isClean
  taskSelVec(2) := io.taskCpu.valid & !io.taskCpu.bits.isClean & !blockCpuTask

  task_s0.valid := taskSelVec.asUInt.orR
  task_s0.bits := ParallelPriorityMux(taskSelVec.asUInt, Seq(io.taskSnp.bits, io.taskMs.bits, io.taskCpu.bits))
  canGo_s0 := canGo_s1 | !task_s1_g.valid

  // set task block table value
  task_s0.bits.btWay := blockWayNext

  /*
   * task ready
   */
  io.taskSnp.ready := canGo_s0
  io.taskMs.ready  := !taskSelVec(0) & canGo_s0
  io.taskCpu.ready := !taskSelVec(1) & !taskSelVec(0) & canGo_s0

  /*
   * Write block table when task_s0.valid and canGo_s0
   */
  when(task_s0.valid & canGo_s0) {
    val writeTable = blockTableReg(task_s0.bits.set(blockSetBits-1, 0))(blockWayNext)
    writeTable.valid := true.B
    writeTable.tag := task_s0.bits.tag
    writeTable.set := task_s0.bits.set(setBits-1, blockSetBits)
    writeTable.bank := task_s0.bits.bank
  }



// ------------------------ S1: Read Dir and send task to MainPipe --------------------------//

  task_s1_g.valid := Mux(task_s0.valid, true.B, task_s1_g.valid & !io.mpTask.fire)
  task_s1_g.bits := Mux(task_s0.valid & canGo_s0 , task_s0.bits, task_s1_g.bits)
  canGo_s1 := io.mpTask.ready & (io.dirRead.ready | dirAlreadyReadReg | !task_s1_g.bits.readDir)

  /*
   * Send mpTask to mainpipe
   */
  io.mpTask.valid := task_s1_g.valid
  io.mpTask.bits := task_s1_g.bits

  /*
   * Read Directory
   */
  dirAlreadyReadReg := Mux(dirAlreadyReadReg, !io.mpTask.fire, io.dirRead.fire & !canGo_s1)
  io.dirRead.valid := task_s1_g.valid & !dirAlreadyReadReg & !task_s1_g.bits.readDir
  io.dirRead.bits := DontCare // TODO












}