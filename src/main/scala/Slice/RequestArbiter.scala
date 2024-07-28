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
    val snpFreeNum    = Input(UInt((snoopCtlIdBits + 1).W))
    val taskSnp       = Flipped(Decoupled(new TaskBundle)) // Hard wire to MSHRs

    // CHI Channel task
    val taskCpu       = Flipped(Decoupled(new TaskBundle))
    val taskMs        = Flipped(Decoupled(new TaskBundle))

    // Send task to MainPipe
    val mpTask        = Decoupled(new TaskBundle)
    val mpReleaseSnp  = Input(Bool())
    // TODO: Dir Way Lock signals from MainPipe
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
  // SnpCtl block signals
  val mpSnpUseNumReg    = RegInit(0.U((snoopCtlIdBits + 1).W))
  val blockBySnp        = Wire(Bool())
  // blockTable
  val blockTableReg = RegInit(VecInit(Seq.fill(nrBlockSets) {
    VecInit(Seq.fill(nrBlockWays) {0.U.asTypeOf(new BlockTableEntry())})
  }))
  // write or clean blockTable mes
  val btWCVec           = Wire(Vec(3, new WCBlockTableMes()))
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
  val taskClean_s0      = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val canGo_s0          = WireInit(false.B)
  val taskSelVec        = Wire(Vec(3, Bool()))
  val taskCleanSelVec   = Wire(Vec(3, Bool()))
  // s1
  val canGo_s1          = WireInit(false.B)
  val task_s1_g         = RegInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val dirAlreadyReadReg = RegInit(false.B)

  dontTouch(blockBySnp)
  dontTouch(invWayVec)
  dontTouch(btRTag)
  dontTouch(btRSet)
  dontTouch(task_s0)
  dontTouch(taskSelVec)
  dontTouch(taskCleanSelVec)
  dontTouch(blockCpuTask)
  dontTouch(blockCpuTaskVec)

  def getBtTag(x: UInt): UInt = {
    if(!mpBlockBySet) {
      x(addressBits - 1, blockSetBits + offsetBits) // TODO: When !mpBlockBySet it must support useWayOH Check and RetryQueue
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
   * MainPipe S3 snpTask cant be block
   * So it need block task in arbiter which may require snoop
   *
   * is(CPU_REQ_OH)    { needSnoop_s3 := needSnp | needSnpHlp }
   * is(CPU_WRITE_OH)  { needSnoop_s3 := false.B }
   * is(MS_RESP_OH)    { needSnoop_s3 := needSnpHlp }
   * is(SNP_RESP_OH)   { needSnoop_s3 := false.B }
   */
  mpSnpUseNumReg  := mpSnpUseNumReg + (task_s0.fire & task_s0.bits.willSnp).asUInt - io.mpReleaseSnp.asUInt
  blockBySnp      := (io.snpFreeNum - mpSnpUseNumReg) === 0.U

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
  taskSelVec(1) := io.taskMs.valid  & !io.taskMs.bits.cleanBt   & !blockBySnp
  taskSelVec(2) := io.taskCpu.valid & !io.taskCpu.bits.cleanBt  & !blockCpuTask & !blockBySnp

  taskCleanSelVec(0) := io.taskSnp.valid & io.taskSnp.bits.cleanBt
  taskCleanSelVec(1) := io.taskMs.valid  & io.taskMs.bits.cleanBt
  taskCleanSelVec(2) := io.taskCpu.valid & io.taskCpu.bits.cleanBt


  /*
   * select clean task
   */
  taskClean_s0.valid := taskCleanSelVec.asUInt.orR
  taskClean_s0.bits := ParallelPriorityMux(taskCleanSelVec.asUInt, Seq(io.taskSnp.bits, io.taskMs.bits, io.taskCpu.bits))


  /*
   * select task
   */
  task_s0.valid := taskSelVec.asUInt.orR
  task_s0.bits := ParallelPriorityMux(taskSelVec.asUInt, Seq(io.taskSnp.bits, io.taskMs.bits, io.taskCpu.bits))
  canGo_s0 := canGo_s1 | !task_s1_g.valid

  // set task block table value
  when(task_s0.bits.writeBt){ task_s0.bits.btWay := blockWayNext }

  /*
   * task ready
   */
  io.taskSnp.ready := Mux(io.taskSnp.bits.cleanBt,  taskCleanSelVec(0), canGo_s0  & task_s0.bits.from.isSLICE)
  io.taskMs.ready  := Mux(io.taskMs.bits.cleanBt,   taskCleanSelVec(1), canGo_s0 & !blockBySnp & task_s0.bits.from.isMASTER)
  io.taskCpu.ready := Mux(io.taskCpu.bits.cleanBt,  taskCleanSelVec(2), canGo_s0 & !blockCpuTask & !blockBySnp & task_s0.bits.from.isCPU)

  /*
   * Write/Clean block table when task_s0.valid and canGo_s0
   */
  // S0 Clean
  btWCVec(0).wcVal    := taskClean_s0.valid
  btWCVec(0).isClean  := true.B
  btWCVec(0).btTag    := getBtTag(taskClean_s0.bits.addr)
  btWCVec(0).btSet    := getBtSet(taskClean_s0.bits.addr)
  btWCVec(0).btWay    := taskClean_s0.bits.btWay
  // S0 Write
  btWCVec(1).wcVal    := task_s0.valid & canGo_s0 & task_s0.bits.writeBt
  btWCVec(1).isClean  := false.B
  btWCVec(1).btTag    := getBtTag(task_s0.bits.addr)
  btWCVec(1).btSet    := getBtSet(task_s0.bits.addr)
  btWCVec(1).btWay    := task_s0.bits.btWay
  // S3 Write or Clean
  btWCVec(2).wcVal    := io.mpBTReq.valid
  btWCVec(2).isClean  := io.mpBTReq.bits.isClean
  btWCVec(2).btTag    := getBtTag(io.mpBTReq.bits.addr)
  btWCVec(2).btSet    := getBtSet(io.mpBTReq.bits.addr)
  btWCVec(2).btWay    := io.mpBTReq.bits.btWay
  io.mpBTReq.ready    := true.B

  blockTableReg.zipWithIndex.foreach {
    case(table, set) =>
      table.zipWithIndex.foreach {
        case(table, way) =>
          val hitVec = btWCVec.map { case b => b.wcVal & b.btSet === set.U & b.btWay === way.U }
          val hitMes = btWCVec(OHToUInt(hitVec))
          when(hitVec.reduce(_ | _)) {
            table.valid := !hitMes.isClean
            when(!hitMes.isClean) { table.tag := hitMes.btTag }
          }
          assert(Mux(hitVec.reduce(_ | _) & hitMes.isClean, table.valid, true.B), "Clean block table[0x%x][0x%x] must be valid", set.U, way.U)
          assert(Mux(hitVec.reduce(_ | _) & hitMes.isClean, table.tag === hitMes.btTag, true.B) ,"Clean block table[0x%x][0x%x] tag[0x%x] must match cleanTask.tag[0x%x]", set.U, way.U, table.tag, hitMes.btTag)
          assert(PopCount(hitVec) <= 1.U)
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
  // io.taskXXX
  assert(Mux(io.taskCpu.valid, io.taskCpu.bits.from.idL0 === IdL0.CPU, true.B), "taskCpu should from CPU")
  assert(Mux(io.taskMs.valid, io.taskMs.bits.from.idL0 === IdL0.MASTER, true.B), "taskMs should from MASTER")
  assert(Mux(io.taskSnp.valid, io.taskSnp.bits.from.idL0 === IdL0.SLICE, true.B), "taskSnp should from SLICE")

  // snoop
  assert(Mux(mpSnpUseNumReg === dsuparam.nrSnoopCtl.U, Mux(task_s0.fire, task_s0.bits.willSnp, true.B), true.B))

  // TIMEOUT CHECK
  blockTableReg.zipWithIndex.foreach {
    case(vec, set) =>
      val cntVecReg = RegInit(VecInit(Seq.fill(nrBlockWays) { 0.U(64.W) }))
      cntVecReg.zip(vec.map(_.valid)).foreach { case (cnt, v) => cnt := Mux(!v, 0.U, cnt + 1.U) }
      cntVecReg.zipWithIndex.foreach { case (cnt, way) => assert(cnt < 5000.U, "ReqArb blockTable[%d][%d] TIMEOUT", set.U, way.U) }
  }
}