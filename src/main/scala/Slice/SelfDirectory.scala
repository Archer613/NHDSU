package DONGJIANG.SLICE

import DONGJIANG._
import DONGJIANG.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import chisel3.util.random.LFSR
import freechips.rocketchip.util.ReplacementPolicy

class SDirMetaEntry(implicit p: Parameters) extends DJBundle with HasChiStates {
  val tag          = UInt(sTagBits.W)
  val bank         = UInt(bankBits.W)
}

class SDirRead(useAddr: Boolean = false)(implicit p: Parameters) extends DJBundle with HasSelfAddrBits {
  override def useAddrVal: Boolean = useAddr
  val mes = new DirReadBase(djparam.ways)
}

class SDirWrite(useAddr: Boolean = false)(implicit p: Parameters) extends DJBundle with HasSelfAddrBits with HasChiStates {
  override def useAddrVal: Boolean = useAddr
  val wayOH = UInt(djparam.ways.W)
  val replMesOpt = if(!useRepl) None else Some(UInt(sReplWayBits.W))

  def toSDirMetaEntry() = {
    val entry = Wire(new SDirMetaEntry)
    entry.state  := state
    entry.bank   := bank
    entry.tag    := tag
    entry
  }
}

class SDirResp(useAddr: Boolean = false)(implicit p: Parameters) extends DJBundle with HasSelfAddrBits with HasChiStates {
  override def useAddrVal: Boolean = useAddr
  val wayOH       = UInt(djparam.ways.W)
  val hit         = Bool()
  val replMesOpt  = if(!useRepl) None else Some(UInt(sReplWayBits.W))
}

class SelfDirectory()(implicit p: Parameters) extends DJModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead   = Flipped(Decoupled(new SDirRead))
  val dirWrite  = Flipped(Decoupled(new SDirWrite))
  val dirResp   = Decoupled(new SDirResp)
  })


  val ways    = djparam.ways
  val sets    = djparam.sets / djparam.nrSelfDirBank
  val wayBits = log2Ceil(ways)
  val setBits = log2Ceil(sets)

// --------------------- Modules declaration ------------------------//
  val repl          = ReplacementPolicy.fromString(djparam.replacementPolicy, ways)

  val metaArray     = Module(new SRAMTemplate(new SDirMetaEntry(), sets, ways, singlePort = true, multicycle = djparam.dirMulticycle, shouldReset = true))

  val replArrayOpt  = if(!useRepl) None else Some(Module(new SRAMTemplate(UInt(repl.nBits.W), sets, way = 1, singlePort = true, shouldReset = true)))

  val readPipe      = Module(new Pipe(new SDirRead(), latency = djparam.dirMulticycle))

  val replPipeOpt   = if(!useRepl) None else Some(Module(new Pipe(UInt(repl.nBits.W), latency = djparam.dirMulticycle-1)))


// ----------------------- Reg/Wire declaration --------------------------//
  // s1
  val dirReadValid    = WireInit(false.B)
  // s2
  val valid_s2        = WireInit(false.B)
  val metaResp_s2     = Wire(Vec(ways, new SDirMetaEntry()))
  val dirRead_s2      = WireInit(0.U.asTypeOf(new SDirRead()))
  val replResp_s2     = WireInit(0.U(repl.nBits.W))
  // s3
  val valid_s3_g      = RegInit(false.B)
  val metaResp_s3_g   = Reg(Vec(ways, new SDirMetaEntry()))
  val dirRead_s3_g    = RegInit(0.U.asTypeOf(new SDirRead()))
  val replResp_s3_g   = RegInit(0.U(repl.nBits.W))
  val hitVec          = Wire(Vec(ways, Bool()))
  val selInvWayVec    = Wire(Vec(ways, Bool()))
  val replWay         = WireInit(0.U(wayBits.W))


// ------------------------------ S1: Read / Write SRAM -----------------------------------//
  /*
   * Read SRAM
   */
  metaArray.io.r.req.valid        := dirReadValid
  metaArray.io.r.req.bits.setIdx  := io.dirRead.bits.set

  /*
   * Read Repl SRAM
   */
  if(useRepl) {
    replArrayOpt.get.io.r.req.valid       := io.dirRead.valid
    replArrayOpt.get.io.r.req.bits.setIdx := io.dirRead.bits.set
  }

  /*
   * Set Dir Read Valid and Ready
   */
  if(!useRepl) {
    dirReadValid      := io.dirRead.valid
    io.dirRead.ready  := metaArray.io.r.req.ready
  } else {
    dirReadValid      := io.dirRead.valid & !replArrayOpt.get.io.w.req.valid
    io.dirRead.ready  := metaArray.io.r.req.ready & !replArrayOpt.get.io.w.req.valid
  }

  /*
   * enter pipe
   */
  readPipe.io.enq.valid := io.dirRead.fire
  readPipe.io.enq.bits  := io.dirRead.bits

  /*
   * Write SRAM
   */
  io.dirWrite.ready                   := metaArray.io.w.req.ready
  metaArray.io.w.req.valid            := io.dirWrite.valid
  metaArray.io.w.req.bits.setIdx      := io.dirWrite.bits.set
  metaArray.io.w.req.bits.waymask.get := io.dirWrite.bits.wayOH
  metaArray.io.w.req.bits.data.foreach(_ := io.dirWrite.bits.toSDirMetaEntry())


// ------------------------------ S2: Wait SRAM Resp -----------------------------------//
  /*
   * Receive SRAM resp
   */
  metaResp_s2 := metaArray.io.r.resp.data

  /*
   * Receive pipe deq
   */
  valid_s2    := readPipe.io.deq.valid
  dirRead_s2  := readPipe.io.deq.bits

  /*
   * Receive sRepl resp
   */
  if (useRepl) {
    replPipeOpt.get.io.enq.valid  := RegNext(replArrayOpt.get.io.r.req.fire)
    replPipeOpt.get.io.enq.bits   := replArrayOpt.get.io.r.resp.data(0)
  }

  /*
   * Receive sRepl pipe deq
   */
  if (useRepl) {
    replResp_s2 := replPipeOpt.get.io.deq.bits
  }


// ------------------------------ S3: Output DirResp -----------------------------------//
  /*
   * Receive S2
   */
  valid_s3_g      := valid_s2
  metaResp_s3_g   := Mux(valid_s2, metaResp_s2, metaResp_s3_g)
  dirRead_s3_g    := Mux(valid_s2, dirRead_s2, dirRead_s3_g)
  replResp_s3_g   := Mux(valid_s2, replResp_s2, replResp_s3_g)


  /*
   * Get Hit Vec and Hit State
   */
  val tagHitVec   = metaResp_s3_g.map(_.tag === dirRead_s3_g.tag)
  val bankHitVec  = metaResp_s3_g.map(_.bank === dirRead_s3_g.bank)
  val stateHitVec = metaResp_s3_g.map(!_.isInvalid)
  val hit         = hitVec.asUInt.orR
  val hitState    = metaResp_s3_g(OHToUInt(hitVec)).state
  hitVec          := tagHitVec.zip(bankHitVec.zip(stateHitVec)).map{ case(t, (b, s)) => t & b & s }


  /*
   * Selet one invalid way
   */
  val invWayVec   = stateHitVec.map(!_)
  val hasInvWay   = invWayVec.reduce(_ | _)
  selInvWayVec    := PriorityEncoderOH(invWayVec)


  /*
   * Select one replace way
   */
  if (!useRepl) {
    replWay := LFSR(sWayBits) // random
  } else {
    replWay := repl.get_replace_way(replResp_s3_g) // replace
  }


  /*
   * repl way is conflict with unuse way
   */
  val replConflict  = dirRead_s3_g.mes.alreayUseWayOH(replWay) & dirRead_s3_g.mes.alreayUseWayOH.orR
  val selUnuseWay   = PriorityEncoder(dirRead_s3_g.mes.alreayUseWayOH.asBools.map(!_))


  /*
   * Output Resp
   */
  io.dirResp.valid      := valid_s3_g
  io.dirResp.bits.hit   := hit
  // [Resp Mes]                     [Hit Way Mes]                      [Invalid Way Mes]                        [Unuse Way Mes]                     [Replace Way Mes]
  io.dirResp.bits.wayOH := Mux(hit, hitVec.asUInt,      Mux(hasInvWay, selInvWayVec.asUInt,   Mux(replConflict, UIntToOH(selUnuseWay),              UIntToOH(replWay))))
  io.dirResp.bits.tag   := Mux(hit, dirRead_s3_g.tag,   Mux(hasInvWay, 0.U,                   Mux(replConflict, metaResp_s3_g(selUnuseWay).tag,     metaResp_s3_g(replWay).tag)))
  io.dirResp.bits.set   := Mux(hit, dirRead_s3_g.set,   Mux(hasInvWay, 0.U,                   Mux(replConflict, dirRead_s3_g.set,                   dirRead_s3_g.set)))
  io.dirResp.bits.bank  := Mux(hit, dirRead_s3_g.bank,  Mux(hasInvWay, 0.U,                   Mux(replConflict, metaResp_s3_g(selUnuseWay).bank,    metaResp_s3_g(replWay).bank)))
  io.dirResp.bits.state := Mux(hit, hitState,           Mux(hasInvWay, ChiState.I,            Mux(replConflict, metaResp_s3_g(selUnuseWay).state,   metaResp_s3_g(replWay).state)))
  if(useRepl) { io.dirResp.bits.replMesOpt.get := replResp_s3_g }


// ------------------------------ Update Replace SRAM Mes -----------------------------------//
  /*
   * PLRU: update replacer only when read hit or write dir
   */
  if (djparam.replacementPolicy == "plru") {
    replArrayOpt.get.io.w.req.valid               := io.dirWrite.fire | (io.dirResp.fire & hit)
    replArrayOpt.get.io.w.req.bits.setIdx         := Mux(io.dirWrite.fire, io.dirWrite.bits.set, dirRead_s3_g.set)
    replArrayOpt.get.io.w.req.bits.data.foreach(_ := Mux(io.dirWrite.fire,
                                                        repl.get_next_state(io.dirWrite.bits.replMesOpt.get, OHToUInt(io.dirWrite.bits.wayOH)),
                                                        repl.get_next_state(replResp_s3_g,                   OHToUInt(io.dirResp.bits.wayOH))))
  } else {
    assert(false.B, "Dont support replacementPolicy except plru")
  }


// ------------------------------ Assertion -----------------------------------//
  // s1
  if(useRepl) {
    assert(!(metaArray.io.r.req.fire ^ replArrayOpt.get.io.r.req.fire), "Must read meta and repl at the same time in S1")
  }
  assert(Mux(io.dirRead.valid, !io.dirRead.bits.mes.alreayUseWayOH.andR, true.B))
  // s2
  if (useRepl) {
    assert(!(readPipe.io.deq.valid ^ replPipeOpt.get.io.deq.valid), "Must get meta and repl at the same time in S2")
  }
  // s3
  assert(PopCount(hitVec) <= 1.U)
  assert(Mux(io.dirResp.valid, io.dirResp.ready, true.B))

}