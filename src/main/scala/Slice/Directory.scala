package NHDSU.SLICE

import NHDSU._
import _root_.NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.ReplacementPolicy
import xs.utils.ParallelPriorityMux
import xs.utils.sram.SRAMTemplate
import xs.utils.perf.{DebugOptions, DebugOptionsKey}

// -------------------------------------------------------------------------------------------- //
// --------------------------------- Define trait and bundle ---------------------------------- //
// -------------------------------------------------------------------------------------------- //

trait HasAddrBits extends DSUBundle {
  this: Bundle =>
  def useAddrVal: Boolean
  def tagBits: Int
  def setBits: Int
  def dirBankBits: Int
  val addrOpt = if (useAddrVal) { Some(UInt(addressBits.W)) } else { None }
  val tagOpt  = if (!useAddrVal) { Some(UInt(tagBits.W)) } else { None }
  val setOpt  = if (!useAddrVal) { Some(UInt(setBits.W)) } else { None }
  val bankOpt = if (!useAddrVal) { Some(UInt(bankBits.W)) } else { None }

  def addr  = addrOpt.get
  def tag   = tagOpt.get
  def set   = setOpt.get
  def bank  = bankOpt.get

  def addr2parse(in: UInt): UInt = {
    val (tag, set, dirBank, bank, offset) = parseAddress(addrOpt.get, dirBankBits, setBits, tagBits)
    Cat(tag, set, bank, in(in.getWidth-addressBits-1, 0))
  }

  def parse2addr(in: UInt, dirBank: UInt): UInt = {
    val selfWidth = tagBits + setBits + dirBankBits
    val addr = Cat(tagOpt.get, setOpt.get, dirBank, bankOpt.get, 0.U(offsetBits.W))
    Cat(addr, in(in.getWidth - selfWidth - 1, 0))
  }
}

trait HasSelfAddrBits extends DSUBundle with HasAddrBits {
  override def tagBits: Int = sTagBits
  override def setBits: Int = sSetBits
  override def dirBankBits: Int = sDirBankBits
}

trait HasClientAddrBits extends DSUBundle with HasAddrBits {
  override def tagBits: Int = cTagBits
  override def setBits: Int = cSetBits
  override def dirBankBits: Int = cDirBankBits
}

class DirReadBase(ways: Int) extends Bundle {
  val alreayUseWayOH = UInt(ways.W)
  val refill = Bool() // unuse
}

class DirRead(implicit p: Parameters) extends DSUBundle {
  val self = new DirReadBase(dsuparam.ways)
  val client = new DirReadBase(dsuparam.clientWays)
  val addr = UInt(addressBits.W)
}

class DirResp(implicit p: Parameters) extends Bundle {
  val self = new SDirResp(useAddr = true)
  val client = new CDirResp(useAddr = true)
}


// -------------------------------------------------------------------------------------------- //
// ------------------------------------ Directory Logic --------------------------------------- //
// -------------------------------------------------------------------------------------------- //

class Directory()(implicit p: Parameters) extends DSUModule {
// --------------------- IO declaration ------------------------//
val io = IO(new Bundle {
  val dirRead    = Flipped(Decoupled(new DirRead()))
  val sDirWrite  = Flipped(Decoupled(new SDirWrite(useAddr = true)))
  val cDirWrite  = Flipped(Decoupled(new CDirWrite(useAddr = true)))
//  val sDirResp   = Decoupled(new SDirResp(useAddr = true))
//  val cDirResp   = Decoupled(new CDirResp(useAddr = true))
  val dirResp    = Decoupled(new DirResp())

  val resetFinish = Output(Bool())
})

  // TODO: Delete the following code when the coding is complete
  io.dirRead    <> DontCare
  io.sDirWrite  <> DontCare
  io.cDirWrite  <> DontCare
  io.dirResp    <> DontCare
  io.resetFinish <> DontCare

  // ------------------------------------------ Modules declaration  ----------------------------------------------//
  val selfDirs = Seq.fill(dsuparam.nrSelfDirBank) { Module(new SelfDirectory()) }
  val clientDirs = Seq.fill(dsuparam.nrClientDirBank) { Module(new ClientDirectory()) }

  selfDirs.foreach(_.io <> DontCare)
  clientDirs.foreach(_.io <> DontCare)

  // ------------------------------------------ Reg/Wire declaration -------------------------------------------//
  val sRDirBank       = WireInit(parseAddress(io.dirRead.bits.addr, sDirBankBits, sSetBits, sTagBits)._3)
  val cRDirBank       = WireInit(parseAddress(io.dirRead.bits.addr, cDirBankBits, cSetBits, cTagBits)._3)
  val sWDirBank       = WireInit(parseAddress(io.sDirWrite.bits.addrOpt.get, sDirBankBits, sSetBits, sTagBits)._3)
  val cWDirBank       = WireInit(parseAddress(io.cDirWrite.bits.addrOpt.get, cDirBankBits, cSetBits, cTagBits)._3)

  val sdReadHasAddr   = WireInit(0.U.asTypeOf(new SDirRead(useAddr = true)))
  val cdReadHasAddr   = WireInit(0.U.asTypeOf(new CDirRead(useAddr = true)))

  val sdReadFreeVec   = Wire(Vec(dsuparam.nrSelfDirBank, Bool()))
  val cdReadFreeVec   = Wire(Vec(dsuparam.nrClientDirBank, Bool()))

  val sdWriteFreeVec  = Wire(Vec(dsuparam.nrSelfDirBank, Bool()))
  val cdWriteFreeVec  = Wire(Vec(dsuparam.nrClientDirBank, Bool()))

  val sdRespValVec    = Wire(Vec(dsuparam.nrSelfDirBank, Bool()))
  val cdRespValVec    = Wire(Vec(dsuparam.nrClientDirBank, Bool()))
  val sdResp          = WireInit(0.U.asTypeOf(new SDirResp()))
  val cdResp          = WireInit(0.U.asTypeOf(new CDirResp()))

  dontTouch(sRDirBank)
  dontTouch(cRDirBank)
  dontTouch(sWDirBank)
  dontTouch(cWDirBank)
  dontTouch(sdReadFreeVec)
  dontTouch(cdReadFreeVec)
  dontTouch(sdWriteFreeVec)
  dontTouch(cdWriteFreeVec)

  // ------------------------------------------ Connection  ----------------------------------------------//
  /*
   * connect selfDirs/clientDirs.io.dirRead <-> io.dirRead
   */
  // self dir read bits
  sdReadHasAddr.mes := io.dirRead.bits.self
  sdReadHasAddr.addr := io.dirRead.bits.addr
  selfDirs.foreach(_.io.dirRead.bits := sdReadHasAddr.addr2parse(sdReadHasAddr.asUInt).asTypeOf(new SDirRead()))
  // client dir read bits
  cdReadHasAddr.mes := io.dirRead.bits.client
  cdReadHasAddr.addr := io.dirRead.bits.addr
  clientDirs.foreach(_.io.dirRead.bits := cdReadHasAddr.addr2parse(cdReadHasAddr.asUInt).asTypeOf(new CDirRead()))
  // read dir mux
  selfDirs.zipWithIndex.foreach {
    case(s, i) =>
      s.io.dirRead.valid := io.dirRead.valid & sRDirBank === i.U & cdReadFreeVec(cRDirBank)
      sdReadFreeVec(i) := s.io.dirRead.ready
  }
  clientDirs.zipWithIndex.foreach {
    case (c, i) =>
      c.io.dirRead.valid := io.dirRead.valid & cRDirBank === i.U & sdReadFreeVec(sRDirBank)
      cdReadFreeVec(i) := c.io.dirRead.ready
  }
  io.dirRead.ready := sdReadFreeVec(sRDirBank) & cdReadFreeVec(cRDirBank)



  /*
   * connect selfDirs.io.dirWrite <-> io.sDirWrite
   */
  // self dir write bits
  selfDirs.foreach(_.io.dirWrite.bits := io.sDirWrite.bits.addr2parse(io.sDirWrite.bits.asUInt).asTypeOf(new SDirWrite()))
  // write self dir mux
  selfDirs.zipWithIndex.foreach {
    case (s, i) =>
      s.io.dirWrite.valid := io.sDirWrite.valid & sWDirBank === i.U
      sdWriteFreeVec(i) := s.io.dirWrite.ready
  }
  io.sDirWrite.ready := sdWriteFreeVec(sWDirBank)


  /*
   * connect clientDirs.io.dirWrite <-> io.cDirWrite
   */
  // client dir write bits
  clientDirs.foreach(_.io.dirWrite.bits := io.cDirWrite.bits.addr2parse(io.cDirWrite.bits.asUInt).asTypeOf(new CDirWrite()))
  // write client dir mux
  clientDirs.zipWithIndex.foreach {
    case (s, i) =>
      s.io.dirWrite.valid := io.cDirWrite.valid & cWDirBank === i.U
      cdWriteFreeVec(i) := s.io.dirWrite.ready
  }
  io.cDirWrite.ready := cdWriteFreeVec(sWDirBank)


  /*
   * connect selfDirs/clientDirs.io.dirResp <-> io.dirResp
   * TODO: selfDir and clientDir can be read independent
   */
  // self
  sdRespValVec := selfDirs.map(_.io.dirResp.valid)
  sdResp := ParallelPriorityMux(sdRespValVec, selfDirs.map(_.io.dirResp.bits))
  io.dirResp.bits.self := sdResp.parse2addr(sdResp.asUInt, OHToUInt(sdRespValVec)).asTypeOf(new SDirResp(useAddr = true))
  selfDirs.foreach(_.io.dirResp.ready := io.dirResp.ready)

  // client
  cdRespValVec := clientDirs.map(_.io.dirResp.valid)
  cdResp := ParallelPriorityMux(cdRespValVec, clientDirs.map(_.io.dirResp.bits))
  io.dirResp.bits.client := cdResp.parse2addr(cdResp.asUInt, OHToUInt(cdRespValVec)).asTypeOf(new CDirResp(useAddr = true))
  clientDirs.foreach(_.io.dirResp.ready := io.dirResp.ready)

  // dirResp valid
  io.dirResp.valid := sdRespValVec.asUInt.orR & cdRespValVec.asUInt.orR


// ------------------------------------------ Assertion  ----------------------------------------------//
  assert(PopCount(selfDirs.map(_.io.dirRead.valid)) <= 1.U, "selfDirs dirRead: only one request can be entered at a time")
  assert(PopCount(clientDirs.map(_.io.dirRead.valid)) <= 1.U, "clientDirs dirRead: only one request can be entered at a time")
  assert(!(selfDirs.map(_.io.dirRead.fire).reduce(_ | _) ^ clientDirs.map(_.io.dirRead.fire).reduce(_ | _)), "selfDirs and clientDirs dirRead must be fire at the same time")

  assert(PopCount(selfDirs.map(_.io.dirWrite.valid)) <= 1.U, "selfDirs dirWrite: only one request can be entered at a time")
  assert(PopCount(clientDirs.map(_.io.dirWrite.valid)) <= 1.U, "clientDirs dirWrite: only one request can be entered at a time")

  assert(PopCount(selfDirs.map(_.io.dirResp.valid)) <= 1.U, "selfDirs dirResp: only one resp can be output at a time")
  assert(PopCount(clientDirs.map(_.io.dirResp.valid)) <= 1.U, "clientDirs dirResp: only one resp can be output at a time")
  assert(Mux(selfDirs.map(_.io.dirResp.valid).reduce(_ | _), selfDirs.map(_.io.dirResp.ready).reduce(_ & _), true.B), "selfDirs dirResp ready must be true when resp valid")
  assert(Mux(clientDirs.map(_.io.dirResp.valid).reduce(_ | _), clientDirs.map(_.io.dirResp.ready).reduce(_ & _), true.B), "clientDirs dirResp ready must be true when resp valid")
}