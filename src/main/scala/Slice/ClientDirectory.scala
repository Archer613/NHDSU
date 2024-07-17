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
  val wayOH = UInt(dsuparam.clientWays.W)
  val metas = Vec(dsuparam.nrCore, new CHIStateBundle())

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
  val wayOH = UInt(dsuparam.clientWays.W)
  val metas = Vec(dsuparam.nrCore, new CHIStateBundle())
  val hitVec = Vec(dsuparam.nrCore, Bool())
}

class ClientDirectory()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead   = Flipped(Decoupled(new CDirRead))
  val dirWrite  = Flipped(Decoupled(new CDirWrite))
  val dirResp   = Decoupled(new CDirResp)

  // TODO: update replacer SRAM

  val resetFinish = Output(Bool())
})
  dontTouch(io)

  val repl                   = ReplacementPolicy.fromString(dsuparam.replacementPolicy, dsuparam.clientWays)
  val ways                   = dsuparam.clientWays
  val sets                   = dsuparam.clientSets/dsuparam.nrClientDirBank

// --------------------- Modules and SRAM declaration ------------------------//
  val clientArray = Module(new SRAMTemplate(new CDirMetaEntry, dsuparam.clientSets/dsuparam.nrClientDirBank, dsuparam.clientWays,
    singlePort = true))

  val replacer_sram_opt = if(dsuparam.replacementPolicy == "random") None else 
    Some(Module(new SRAMTemplate(UInt(repl.nBits.W), dsuparam.clientSets, 1, singlePort = true,
    shouldReset = true)))


// --------------------- Reg/Wire declaration ------------------------//

  /* 
  Basic logic
   */

  val metaWen                = io.dirWrite.fire || !io.resetFinish
  val resetIdx               = RegInit((sets - 1).U)
  val resetFinish            = RegInit(false.B)

  /*
  Stage 2
  */

  val stateRead              = Wire(Vec(ways, new CDirMetaEntry))
  val refillReqValid_s2      = RegNext(io.dirRead.bits.mes.refill && io.dirRead.fire) 
  val reqRead_s2_reg         = RegEnable(io.dirRead.bits, 0.U.asTypeOf(io.dirRead.bits), io.dirRead.fire)
  val reqReadValid_s2        = RegNext(io.dirRead.fire)

  /* 
  Stage 3
   */
  val reqReadValid_s3       = RegNext(reqReadValid_s2)
  val refillReqValid_s3     = RegNext(refillReqValid_s2)
  val reqRead_s3_reg        = RegEnable(reqRead_s2_reg, 0.U.asTypeOf(reqRead_s2_reg), reqReadValid_s2)
  val stateAll_s3           = RegEnable(stateRead, 0.U.asTypeOf(stateRead), reqReadValid_s2)

  /* 
  Replace logic
   */
  val replaceWay            = WireInit(UInt(cWayBits.W),0.U)
  val replaceWen            = WireInit(false.B)


// -----------------------------------------------------------------------------------------
// Stage 1 (dir read) / (dir write)
// -----------------------------------------------------------------------------------------
  io.dirRead.ready          := clientArray.io.r.req.ready && !io.dirWrite.fire && io.resetFinish
  io.dirWrite.ready         := clientArray.io.w.req.ready && io.resetFinish
  

// -----------------------------------------------------------------------------------------
// Stage 2(dir read)
// -----------------------------------------------------------------------------------------
  stateRead                 := clientArray.io.r(io.dirRead.fire, io.dirRead.bits.set).resp.data
  clientArray.io.w(
    metaWen,
    Mux(io.resetFinish, io.dirWrite.bits.toCDirMetaEntry(), 0.U.asTypeOf(new CDirMetaEntry)),
    Mux(io.resetFinish, io.dirWrite.bits.set, resetIdx),
    Mux(io.resetFinish, io.dirWrite.bits.wayOH, Fill(ways, "b1".U))
  )

// -----------------------------------------------------------------------------------------
// Stage 3(dir read)
// -----------------------------------------------------------------------------------------

  /* 
  Hit logic
   */

  val tagMatchVec           = stateAll_s3.map(_.tag(cTagBits - 1, 0) === reqRead_s3_reg.tag)
  val bankMatchVec          = stateAll_s3.map(_.bank === reqRead_s3_reg.bank)
  val hit_tag_bank_vec      = tagMatchVec.zip(bankMatchVec).map(x => x._1 && x._2)
  val hit_tag_bank          = Cat(hit_tag_bank_vec).orR
  val hit_tag_bank_way      = Mux(hit_tag_bank, OHToUInt(hit_tag_bank_vec), 0.U(cWayBits.W))
  val hitWayState           = stateAll_s3(hit_tag_bank_way).metas.map(!_.isInvalid)

  /* 
  Replace logic
   */

  val useRandomWay          = (dsuparam.replacementPolicy == "random").asBool
  val randomWay             = RegInit(0.U(cWayBits.W))
  randomWay                := Mux(refillReqValid_s2, LFSR(log2Ceil(ways))(cWayBits - 1, 0), 0.U(cWayBits.W))
  val metaInvalidVec        = stateAll_s3.map(_.metas.map(_.isInvalid))
  val has_invalid_way_vec   = metaInvalidVec.map(Cat(_).orR)
  val has_invalid_way       = Cat(has_invalid_way_vec).orR
  val invalid_way           = PriorityMuxDefault(has_invalid_way_vec.zipWithIndex.map(x => x._1 -> x._2.U(cWayBits.W)),0.U)

  when(reqRead_s3_reg.mes.alreayUseWayOH(randomWay) === true.B && refillReqValid_s3){
     randomWay             := Mux(refillReqValid_s3, LFSR(log2Ceil(ways))(cWayBits - 1, 0), 0.U(cWayBits.W))
  }
  require(randomWay.getWidth == cWayBits)
  assert(randomWay <= ways.U)

  val chosenWay             = Mux(has_invalid_way, invalid_way, Mux(useRandomWay, randomWay, replaceWay))
  val replacerReady         = if(dsuparam.replacementPolicy == "random") true.B else 
    replacer_sram_opt.get.io.r.req.ready
  val repl_sram_r           = replacer_sram_opt.get.io.r(io.dirRead.fire & io.dirRead.bits.mes.refill, io.dirRead.bits.set).resp.data(0)
  val repl_state_s3         = RegEnable(repl_sram_r, 0.U(repl.nBits.W), refillReqValid_s2)
  replaceWay               := repl.get_replace_way(repl_state_s3)

  val way_s3                = Mux(refillReqValid_s3 | !hit_tag_bank, chosenWay, hit_tag_bank_way)
  val failState             = VecInit(Seq.fill(dsuparam.nrCore)(false.B))


  /* 
  io out logic
   */

   io.dirResp.bits.hitVec  := hitWayState

    when(!hit_tag_bank || !reqReadValid_s3){
    io.dirResp.bits.hitVec := failState
  }

   io.dirResp.bits.tag     := Mux(refillReqValid_s3 & !hit_tag_bank, stateAll_s3(chosenWay).tag, reqRead_s3_reg.tag)
   io.dirResp.bits.set     := reqRead_s3_reg.set
   io.dirResp.bits.bank    := reqRead_s3_reg.bank
   io.dirResp.bits.wayOH   := UIntToOH(way_s3)
   // io.dirResp.bits.metas   := stateAll_s3(hit_tag_bank_way).metas
   // io.dirResp.bits.metas   := Mux(hit_tag_bank, stateAll_s3(hit_tag_bank_way).metas, 0.U.asTypeOf(io.dirResp.bits.metas))
   io.dirResp.bits.metas   := Mux(hit_tag_bank, stateAll_s3(hit_tag_bank_way).metas, stateAll_s3(way_s3).metas)
   io.dirResp.valid        := reqReadValid_s3

// -----------------------------------------------------------------------------------------
// Update replacer_sram_opt
// -----------------------------------------------------------------------------------------
  val set_s3                    = reqRead_s3_reg.set
  val updateHit                 = reqReadValid_s3 && hit_tag_bank
  val updateRefill              = refillReqValid_s3
  replaceWen                   := refillReqValid_s3

  val touch_way_s3              = Mux(refillReqValid_s3, replaceWay, way_s3)
  val rrip_hit_s3               = Mux(refillReqValid_s3, false.B, hit_tag_bank)

  if(dsuparam.replacementPolicy == "srrip"){
    val next_state_s3 = repl.get_next_state(repl_state_s3, touch_way_s3)
    val repl_init = Wire(Vec(ways, UInt(2.W)))
    repl_init.foreach(_ := 2.U(2.W))
    replacer_sram_opt.get.io.w(
      !resetFinish || replaceWen,
      Mux(resetFinish, next_state_s3, repl_init.asUInt),
      Mux(resetFinish, set_s3, resetIdx),
      1.U
    )
  } else if(dsuparam.replacementPolicy == "drrip"){
    //Set Dueling
    val PSEL = RegInit(512.U(10.W)) //32-monitor sets, 10-bits psel
    // track monitor sets' hit rate for each policy: srrip-0,128...3968;brrip-64,192...4032
    when(refillReqValid_s3 && (set_s3(6,0)===0.U) && !rrip_hit_s3){  //SDMs_srrip miss
      PSEL := PSEL + 1.U
    } .elsewhen(refillReqValid_s3 && (set_s3(6,0)===64.U) && !rrip_hit_s3){ //SDMs_brrip miss
      PSEL := PSEL - 1.U
    }
    // decide use which policy by policy selection counter, for insertion
    /* if set -> SDMs: use fix policy
       else if PSEL(MSB)==0: use srrip
       else if PSEL(MSB)==1: use brrip */
    val repl_type = WireInit(false.B)
    repl_type := Mux(set_s3(6,0)===0.U, false.B,
      Mux(set_s3(6,0)===64.U, true.B,
        Mux(PSEL(9)===0.U, false.B, true.B)))    // false.B - srrip, true.B - brrip
    val next_state_s3 = repl.get_next_state(repl_state_s3, touch_way_s3)

    val repl_init = Wire(Vec(ways, UInt(2.W)))
    repl_init.foreach(_ := 2.U(2.W))
    replacer_sram_opt.get.io.w(
      !resetFinish || replaceWen,
      Mux(resetFinish, next_state_s3, repl_init.asUInt),
      Mux(resetFinish, set_s3, resetIdx),
      1.U
    )
  } else {
    val next_state_s3 = repl.get_next_state(repl_state_s3, touch_way_s3)
    replacer_sram_opt.get.io.w(
      !resetFinish || replaceWen,
      Mux(resetFinish, next_state_s3, 0.U),
      Mux(resetFinish, set_s3, resetIdx),
      1.U
    )
  }

  /* 
  Reset logic
   */

  when(resetIdx === 0.U){
    resetFinish        := true.B
  }.otherwise{
    resetFinish        := false.B
    resetIdx           := resetIdx - 1.U 
  }
  io.resetFinish       := resetFinish
}