package NHDSU.SLICE

import NHDSU._
import _root_.NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.sram.SRAMTemplate
import chisel3.util.random.LFSR
import xs.utils.PriorityMuxDefault
import freechips.rocketchip.util.ReplacementPolicy

class CDirMetaEntry(implicit p: Parameters) extends DSUBundle {
  val tag          = UInt(cTagBits.W)
  val bank         = UInt(bankBits.W)
  val metas        = Vec(dsuparam.nrCore, new CHIStateBundle())
}

class CDirRead(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasClientAddrBits {
  override def useAddrVal: Boolean = useAddr
  val mes = new DirReadBase(dsuparam.clientWays)
}

class CDirWrite(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasClientAddrBits {
  override def useAddrVal: Boolean = useAddr
  val wayOH       = UInt(dsuparam.clientWays.W)
  val metas       = Vec(dsuparam.nrCore, new CHIStateBundle())
  val replMesOpt  = if(!useRepl) None else Some(UInt(cReplWayBits.W))

  def toCDirMetaEntry() = {
    val entry = Wire(new CDirMetaEntry)
    entry.metas := metas
    entry.tag   := tag
    entry.bank  := bank
    entry
  }
}

class CDirResp(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasClientAddrBits {
  override def useAddrVal: Boolean = useAddr
  val wayOH      = UInt(dsuparam.clientWays.W)
  val metas      = Vec(dsuparam.nrCore, new CHIStateBundle())
  val hitVec     = Vec(dsuparam.nrCore, Bool())
  val replMesOpt = if(!useRepl) None else Some(UInt(cReplWayBits.W))
}

class ClientDirectory()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead   = Flipped(Decoupled(new CDirRead))
  val dirWrite  = Flipped(Decoupled(new CDirWrite))
  val dirResp   = Decoupled(new CDirResp)
})


  val ways    = dsuparam.clientWays
  val sets    = dsuparam.clientSets / dsuparam.nrClientDirBank
  val wayBits = log2Ceil(ways)
  val setBits = log2Ceil(sets)

// --------------------- Modules declaration ------------------------//
  val repl          = ReplacementPolicy.fromString(dsuparam.replacementPolicy, ways)

  val metaArray     = Module(new SRAMTemplate(new CDirMetaEntry(), sets, ways, singlePort = true, multicycle = dsuparam.dirMulticycle, shouldReset = true))

  val replArrayOpt  = if(!useRepl) None else Some(Module(new SRAMTemplate(UInt(repl.nBits.W), sets, way = 1, singlePort = true, shouldReset = true)))

  val readPipe      = Module(new Pipe(new CDirRead(), latency = dsuparam.dirMulticycle))

  val replPipeOpt   = if(!useRepl) None else Some(Module(new Pipe(UInt(repl.nBits.W), latency = dsuparam.dirMulticycle-1)))


// ----------------------- Reg/Wire declaration --------------------------//
  // s1
  val dirReadValid    = WireInit(false.B)
  // s2
  val valid_s2        = WireInit(false.B)
  val metaResp_s2     = Wire(Vec(ways, new CDirMetaEntry()))
  val dirRead_s2      = WireInit(0.U.asTypeOf(new CDirRead()))
  val replResp_s2     = WireInit(0.U(repl.nBits.W))
  // s3
  val valid_s3_g      = RegInit(false.B)
  val metaResp_s3_g   = Reg(Vec(ways, new CDirMetaEntry()))
  val dirRead_s3_g    = RegInit(0.U.asTypeOf(new CDirRead()))
  val replResp_s3_g   = RegInit(0.U(repl.nBits.W))
  val hitWayVec       = Wire(Vec(ways, Bool()))
  val selInvWayVec    = Wire(Vec(ways, Bool()))
  val replWay         = WireInit(0.U(wayBits.W))
  val invMetas        = Wire(Vec(dsuparam.nrCore, new CHIStateBundle()))


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
  metaArray.io.w.req.bits.data.foreach(_ := io.dirWrite.bits.toCDirMetaEntry())


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
  val stateHitVec = metaResp_s3_g.map(_.metas.map(!_.isInvalid).reduce(_ | _))
  val hitMetas    = metaResp_s3_g(OHToUInt(hitWayVec)).metas
  val hit         = hitWayVec.asUInt.orR
  val hitVec      = hitMetas.map(!_.isInvalid)
  hitWayVec       := tagHitVec.zip(bankHitVec.zip(stateHitVec)).map{ case(t, (b, s)) => t & b & s }


  /*
   * Selet one invalid way
   */
  val invWayVec   = stateHitVec.map(!_)
  val hasInvWay   = invWayVec.reduce(_ | _)
  invMetas.foreach(_.state := ChiState.I)
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
  io.dirResp.valid        := valid_s3_g
  io.dirResp.bits.hitVec  := hitVec
  // [Resp Mes]                       [Hit Way Mes]                      [Invalid Way Mes]                      [Unuse Way Mes]                     [Replace Way Mes]
  io.dirResp.bits.wayOH   := Mux(hit, hitWayVec.asUInt,   Mux(hasInvWay, selInvWayVec.asUInt, Mux(replConflict, UIntToOH(selUnuseWay),              UIntToOH(replWay))))
  io.dirResp.bits.tag     := Mux(hit, dirRead_s3_g.tag,   Mux(hasInvWay, 0.U,                 Mux(replConflict, metaResp_s3_g(selUnuseWay).tag,     metaResp_s3_g(replWay).tag)))
  io.dirResp.bits.set     := Mux(hit, dirRead_s3_g.set,   Mux(hasInvWay, 0.U,                 Mux(replConflict, dirRead_s3_g.set,                   dirRead_s3_g.set)))
  io.dirResp.bits.bank    := Mux(hit, dirRead_s3_g.bank,  Mux(hasInvWay, 0.U,                 Mux(replConflict, metaResp_s3_g(selUnuseWay).bank,    metaResp_s3_g(replWay).bank)))
  io.dirResp.bits.metas   := Mux(hit, hitMetas,           Mux(hasInvWay, invMetas,            Mux(replConflict, metaResp_s3_g(selUnuseWay).metas,   metaResp_s3_g(replWay).metas)))
  if(useRepl) { io.dirResp.bits.replMesOpt.get := replResp_s3_g }


// ------------------------------ Update Replace SRAM Mes -----------------------------------//
  /*
   * PLRU: update replacer only when read hit or write Dir
   */
  if (dsuparam.replacementPolicy == "plru") {
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