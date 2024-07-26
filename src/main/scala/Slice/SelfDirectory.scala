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

class SDirMetaEntry(implicit p: Parameters) extends DSUBundle with HasChiStates {
  val tag          = UInt(sTagBits.W)
  val bank         = UInt(bankBits.W)
}

class SDirRead(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasSelfAddrBits {
  override def useAddrVal: Boolean = useAddr
  val mes = new DirReadBase(dsuparam.ways)
}

class SDirWrite(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasSelfAddrBits with HasChiStates {
  override def useAddrVal: Boolean = useAddr
  val wayOH = UInt(dsuparam.ways.W)

  def toSDirMetaEntry() = {
    val entry = Wire(new SDirMetaEntry)
    entry.state  := state
    entry.bank   := bank
    entry.tag    := tag
    entry
  }
}

class SDirResp(useAddr: Boolean = false)(implicit p: Parameters) extends DSUBundle with HasSelfAddrBits with HasChiStates {
  override def useAddrVal: Boolean = useAddr
  val wayOH = UInt(dsuparam.ways.W)
  val hit   = Bool()
}

class SelfDirectory()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead   = Flipped(Decoupled(new SDirRead))
  val dirWrite  = Flipped(Decoupled(new SDirWrite))
  val dirResp   = Decoupled(new SDirResp)


  val resetFinish = Output(Bool())
  })
  dontTouch(io)

  val ways                   = dsuparam.ways
  val sets                   = dsuparam.sets/dsuparam.nrSelfDirBank
  val repl                   = ReplacementPolicy.fromString(dsuparam.replacementPolicy, ways)

 // --------------------- Modules and SRAM declaration ------------------------//
  val metaArray = Module(new SRAMTemplate(new SDirMetaEntry, dsuparam.sets / dsuparam.nrSelfDirBank, dsuparam.ways,
    singlePort = true))
  val replacer_sram_opt = if(dsuparam.replacementPolicy == "random") None else
    Some(Module(new SRAMTemplate(UInt(repl.nBits.W), sets, 1, singlePort = true,
    shouldReset = true)))
  

  // metaArray.io <> DontCare


// ----------------------- Reg/Wire declaration --------------------------//

  /* 
  Basic Reg or Wire
   */
  
  
  val metaWen                = io.dirWrite.fire || !io.resetFinish
  val resetIdx               = RegInit((sets - 1).U)
  val resetFinish            = RegInit(false.B) 

  /* 
  Stage 2 Wire and Reg
   */

  val refillReqValid_s2      = RegNext(io.dirRead.bits.mes.refill && io.dirRead.fire) 
  val reqRead_s2_reg         = RegEnable(io.dirRead.bits, 0.U.asTypeOf(io.dirRead.bits), io.dirRead.fire)
  val reqReadValid_s2        = RegNext(io.dirRead.fire)
  val metaRead               = Wire(Vec(ways, new SDirMetaEntry))

  /* 
  Stage 3 Wire and Reg
   */

  val reqReadValid_s3       = RegNext(reqReadValid_s2)
  val refillReqValid_s3     = RegNext(refillReqValid_s2)
  val reqRead_s3_reg        = RegEnable(reqRead_s2_reg, 0.U.asTypeOf(reqRead_s2_reg), reqReadValid_s2)
  val metaAll_s3            = RegEnable(metaRead, 0.U.asTypeOf(metaRead), reqReadValid_s2)

   /* 
   replace logic
    */

  val replaceWay            = WireInit(UInt(sWayBits.W), 0.U)
  val replaceWen            = WireInit(false.B)
  val hit_s3                = WireInit(false.B)



// -----------------------------------------------------------------------------------------
// Stage 1 (dir read) / (dir write)
// -----------------------------------------------------------------------------------------
  io.dirRead.ready         := metaArray.io.r.req.ready && !io.dirWrite.fire  && io.resetFinish && !(refillReqValid_s3 && !hit_s3 || reqReadValid_s3 && hit_s3 )
  io.dirWrite.ready        := metaArray.io.w.req.ready && io.resetFinish
  

// -----------------------------------------------------------------------------------------
// Stage 2(dir read)
// -----------------------------------------------------------------------------------------
  metaRead                 := metaArray.io.r(io.dirRead.fire, io.dirRead.bits.set).resp.data
  metaArray.io.w(
    metaWen,
    Mux(io.resetFinish, io.dirWrite.bits.toSDirMetaEntry(), 0.U.asTypeOf(new SDirMetaEntry)),
    Mux(io.resetFinish, io.dirWrite.bits.set, resetIdx),
    Mux(io.resetFinish, io.dirWrite.bits.wayOH, Fill(ways, "b1".U))
  )

// -----------------------------------------------------------------------------------------
// Stage 3(dir read)
// -----------------------------------------------------------------------------------------
  /* 
  Hit logic
   */

  val tagMatchVec               = metaAll_s3.map(_.tag(sTagBits - 1, 0) === reqRead_s3_reg.tag)
  val bankMatchVec              = metaAll_s3.map(_.bank === reqRead_s3_reg.bank)
  val metaValidVec              = metaAll_s3.map(!_.isInvalid)
  val metaInvalidVec            = metaAll_s3.map(_.isInvalid)
  val has_invalid_way           = Cat(metaInvalidVec).orR
  val invalid_way               = PriorityMuxDefault(metaInvalidVec.zipWithIndex.map(x => x._1 -> x._2.U(sWayBits.W)), 0.U)
  val hit_tag_bank              = tagMatchVec.zip(bankMatchVec).map(x => x._1 && x._2)
  val hit_vec                   = hit_tag_bank.zip(metaValidVec).map(x => x._1 && x._2)
  val hitWay                    = OHToUInt(hit_vec)

  /* 
  Replace logic
   */

  val useRandomWay              = (dsuparam.replacementPolicy == "random").asBool
  val noUseWayOH            = ~reqRead_s3_reg.mes.alreayUseWayOH
  val addUseWayOH           = reqRead_s3_reg.mes.alreayUseWayOH + 1.U

  val randomWay             = WireInit(0.U(cWayBits.W))
  when(reqRead_s3_reg.mes.alreayUseWayOH === 0.U(ways.W)){
    randomWay              := LFSR(log2Ceil(ways))
  }.otherwise{
    randomWay              := OHToUInt(noUseWayOH & addUseWayOH)
  }

  require(randomWay.getWidth == sWayBits)
  assert(randomWay <= ways.U)
  val chosenWay                 = Mux(has_invalid_way, invalid_way, Mux(useRandomWay, randomWay, replaceWay))
  val repl_sram_r               = if(dsuparam.replacementPolicy == "random") 0.U else replacer_sram_opt.get.io.r(io.dirRead.fire, io.dirRead.bits.set).resp.data(0)
  val repl_state_s3             = RegEnable(repl_sram_r, 0.U(repl.nBits.W), refillReqValid_s2)
  replaceWay                   := repl.get_replace_way(repl_state_s3)

  /* 
  Hit result
   */

   hit_s3                      := Cat(hit_vec).orR
  val set_s3                    = reqRead_s3_reg.set
  val way_s3                    = Mux(refillReqValid_s3 & !Cat(hit_vec).orR, chosenWay, hitWay)
  val wayOH_s3                  = UIntToOH(way_s3)
  val meta_s3                   = metaAll_s3(hitWay)

  /* 
  IO out logic
   */

  io.dirResp.bits.hit          := Mux(reqReadValid_s3, hit_s3, false.B)
  io.dirResp.bits.set          := reqRead_s3_reg.set
  io.dirResp.bits.bank         := reqRead_s3_reg.bank
  io.dirResp.bits.tag          := Mux(refillReqValid_s3 & !hit_s3, metaAll_s3(chosenWay).tag, reqRead_s3_reg.tag)
  // io.dirResp.bits.state        := meta_s3.state
  // io.dirResp.bits.state        := Mux(Cat(hit_vec).orR, meta_s3.state, 0.U.asTypeOf(meta_s3.state))
  io.dirResp.bits.state        := Mux(Cat(hit_vec).orR, meta_s3.state, metaAll_s3(way_s3).state)
  io.dirResp.bits.wayOH        := wayOH_s3
  io.dirResp.valid             := reqReadValid_s3



// -----------------------------------------------------------------------------------------
// Update replacer_sram_opt
// -----------------------------------------------------------------------------------------

  val updateHit                 = reqReadValid_s3 && hit_s3 
  val updateRefill              = refillReqValid_s3 && !hit_s3
  replaceWen                   := updateHit | updateRefill

  val touch_way_s3              = Mux(refillReqValid_s3 && !hit_s3, replaceWay, way_s3)
  val rrip_hit_s3               = Mux(refillReqValid_s3 && !hit_s3, false.B, hit_s3)

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
  }else if(dsuparam.replacementPolicy == "plru"){
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