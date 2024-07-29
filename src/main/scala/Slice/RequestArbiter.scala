package NHDSU.SLICE

import NHDSU._
import chisel3.{UInt, _}
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.ParallelPriorityMux
import Utils.FastArb.fastPriorityArbDec2Val

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

    // Cpu / Master / MainPipe S3 clean or lock BlockTable
    val wcBTReqVec    = Vec(3, Flipped(Decoupled(new WCBTBundle())))
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
  // select a way to store block mes
  val invWayVec         = Wire(Vec(nrBlockWays, Bool()))
  val blockWayNext      = Wire(UInt(blockWayBits.W))
  // need to block cpu task
  val blockCpuTaskVec   = Wire(Vec(nrBlockWays, Bool()))
  val blockCpuTask      = WireInit(false.B)
  // s0
  val task_s0           = WireInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val canGo_s0          = WireInit(false.B)
  val taskSelVec        = Wire(Vec(3, Bool()))
  val wBTReq_s0         = WireInit(0.U.asTypeOf(Decoupled(new WCBTBundle())))
  val wcBTReq_s0        = WireInit(0.U.asTypeOf(Valid(new WCBTBundle())))
  // s1
  val canGo_s1          = WireInit(false.B)
  val task_s1_g         = RegInit(0.U.asTypeOf(Valid(new TaskBundle())))
  val dirAlreadyReadReg = RegInit(false.B)

  dontTouch(blockBySnp)
  dontTouch(invWayVec)
  dontTouch(task_s0)
  dontTouch(taskSelVec)
  dontTouch(blockCpuTask)
  dontTouch(blockCpuTaskVec)
  dontTouch(wcBTReq_s0)

  def parseBTAddress(x: UInt): (UInt, UInt, UInt) = {
    val tag = WireInit(0.U(blockTagBits.W))
    val (tag_, set, modBank, bank, offset) = parseAddress(x, modBankBits = 0, setBits = blockSetBits, tagBits = blockTagBits)
    if (!mpBlockBySet) {
      tag := tag_ // TODO: When !mpBlockBySet it must support useWayOH Check and RetryQueue
    } else {
      require(sSetBits + sDirBankBits > blockSetBits)
      tag := tag_(sSetBits + sDirBankBits - 1 - blockSetBits, 0)
    }
    (tag, set, bank)
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
  mpSnpUseNumReg  := mpSnpUseNumReg + (task_s0.valid & canGo_s0 & task_s0.bits.willSnp).asUInt - io.mpReleaseSnp.asUInt
  blockBySnp      := (io.snpFreeNum - mpSnpUseNumReg) === 0.U

  /*
   * Determine whether it need to block cputask
   * TODO: Add retry queue to
   */
  val(btRTag, btRSet, btRBank) = parseBTAddress(io.taskCpu.bits.addr)
  blockCpuTaskVec := blockTableReg(btRSet).map { case b => b.valid & b.tag === btRTag & b.bank === btRBank }
  invWayVec := blockTableReg(btRSet).map { case b => !b.valid }
  blockWayNext := PriorityEncoder(invWayVec)
  blockCpuTask := blockCpuTaskVec.asUInt.orR | !invWayVec.asUInt.orR

  /*
   * Priority(!task.isClean): taskSnp > taskMs > taskCpu
   * Priority(task.isClean): taskSnp > taskMs > taskCpu
   */
  taskSelVec(0) := io.taskSnp.valid
  taskSelVec(1) := io.taskMs.valid  & !blockBySnp
  taskSelVec(2) := io.taskCpu.valid & !blockCpuTask & !blockBySnp


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
  io.taskSnp.ready := canGo_s0  & task_s0.bits.from.isSLICE
  io.taskMs.ready  := canGo_s0 & !blockBySnp & task_s0.bits.from.isMASTER
  io.taskCpu.ready := canGo_s0 & !blockCpuTask & !blockBySnp & task_s0.bits.from.isCPU

  /*
   * Write/Clean block table when task_s0.valid and canGo_s0
   */
  // S0 Write
  wBTReq_s0.valid         := task_s0.valid & canGo_s0 & task_s0.bits.writeBt
  wBTReq_s0.bits.isClean  := false.B
  wBTReq_s0.bits.addr     := task_s0.bits.addr
  wBTReq_s0.bits.btWay    := task_s0.bits.btWay
  // sel one req to write or clean Req
  fastPriorityArbDec2Val(Seq(wBTReq_s0) ++ io.wcBTReqVec, wcBTReq_s0)

  blockTableReg.zipWithIndex.foreach {
    case(table, set) =>
      table.zipWithIndex.foreach {
        case(table, way) =>
          val (btTag, btSet, btBank) = parseBTAddress(wcBTReq_s0.bits.addr)
          val hit = wcBTReq_s0.valid & btSet === set.U & wcBTReq_s0.bits.btWay === way.U
          when(hit) {
            table.valid := !wcBTReq_s0.bits.isClean
            when(!wcBTReq_s0.bits.isClean) { table.tag := btTag; table.bank := btBank }
          }
          assert(Mux(hit & wcBTReq_s0.bits.isClean, table.valid, true.B), "Clean block table[0x%x][0x%x] must be valid", set.U, way.U)
          assert(Mux(hit & wcBTReq_s0.bits.isClean, table.tag === btTag, true.B) ,"Clean block table[0x%x][0x%x] tag[0x%x] must match cleanTask.tag[0x%x]", set.U, way.U, table.tag, btTag)
          assert(Mux(hit & wcBTReq_s0.bits.isClean, table.bank === btBank, true.B) ,"Clean block table[0x%x][0x%x] bank[0x%x] must match cleanTask.bank[0x%x]", set.U, way.U, table.bank, btBank)
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

  // block table
  assert(Mux(wBTReq_s0.valid, wBTReq_s0.ready, true.B))
  assert(PopCount(blockCpuTaskVec) <= 1.U)

  // snoop
  assert(Mux(mpSnpUseNumReg === dsuparam.nrSnoopCtl.U, Mux(task_s0.fire, task_s0.bits.willSnp, true.B), true.B))

  // TIMEOUT CHECK
  blockTableReg.zipWithIndex.foreach {
    case(vec, set) =>
      val cntVecReg = RegInit(VecInit(Seq.fill(nrBlockWays) { 0.U(64.W) }))
      cntVecReg.zip(vec.map(_.valid)).foreach { case (cnt, v) => cnt := Mux(!v, 0.U, cnt + 1.U) }
      cntVecReg.zipWithIndex.foreach { case (cnt, way) => assert(cnt < TIMEOUT_BT.U, "ReqArb blockTable[%d][%d] TIMEOUT", set.U, way.U) }
  }
}