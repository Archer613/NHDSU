package NHDSU

import NHDSU.CHI._
import NHDSU.CPUSALVE._
import NHDSU.SLICE._
import NHDSU.DSUMASTER._
import chisel3.{Flipped, _}
import chisel3.util.{ValidIO, _}
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import Utils.IDConnector._
import Utils.FastArb._


class IdMap(implicit p: Parameters) extends DSUModule {
    val io = IO(new Bundle {
        val bankVal = Input(Vec(dsuparam.nrBank, Bool()))
        val inBank  = Input(UInt(bankBits.W))
        val outBank = Output(UInt(bankBits.W))
    })
    val outBank = WireInit(0.U(bankBits.W))
    val bankaValNum = WireInit(PopCount(io.bankVal).asUInt)

    if (dsuparam.nrBank == 4) {
        switch(bankaValNum) {
            // Use Bank [0]
            is(1.U) { outBank :=  0.U }
            // Use Bank [0 1]
            is(2.U) { outBank === io.inBank(0) }
            // Use Bank [0 1 2 3]
            is(4.U) { outBank === io.inBank }
        }
    } else if (dsuparam.nrBank == 2) {
        switch(bankaValNum) {
            // Use Bank [0]
            is(1.U) { outBank === 0.U }
            // Use Bank [0 1]
            is(2.U) { outBank === io.inBank(0) }
        }
    } else {
        // Use Bank [0]
        outBank === 0.U
    }
    io.outBank := outBank
    assert(bankaValNum === 1.U | bankaValNum === 2.U | bankaValNum === 4.U)
}




class Xbar()(implicit p: Parameters) extends DSUModule {
// ------------------------------------------ IO declaration ----------------------------------------------//
    val io = IO(new Bundle {
        val bankVal = Input(Vec(dsuparam.nrBank, Bool()))
        // snpCtrl
        val snpTask = new Bundle {
            val in = Vec(dsuparam.nrBank, Flipped(Decoupled(new TaskBundle())))
            val out = Vec(dsuparam.nrCore, Decoupled(new TaskBundle()))
        }
        val snpResp = new Bundle {
            val in = Vec(dsuparam.nrCore, Flipped(Decoupled(new RespBundle())))
            val out = Vec(dsuparam.nrBank, ValidIO(new RespBundle()))
        }
        // mainpipe
        val mpTask = new Bundle {
            val in = Vec(dsuparam.nrCore, Flipped(Decoupled(new TaskBundle())))
            val out = Vec(dsuparam.nrBank, Decoupled(new TaskBundle()))
        }
        val mpResp = new Bundle {
            val in = Vec(dsuparam.nrBank, Flipped(Decoupled(new RespBundle())))
            val out = Vec(dsuparam.nrCore, ValidIO(new RespBundle()))
        }
        // dataBuffer
        val dbSigs = new Bundle {
            val in = Vec(dsuparam.nrCore, Flipped(new CpuDBBundle()))
            val out = Vec(dsuparam.nrBank, new CpuDBBundle())
        }
    })

    // ------------------------------------------ Modules declaration And Connection ----------------------------------------------//
    val taskIdMaps = Seq.fill(dsuparam.nrCore) { Module(new IdMap()) }
    val wReqIdMaps = Seq.fill(dsuparam.nrCore) { Module(new IdMap()) }

    // --------------------- Wire declaration ------------------------//
    val mpTaskRemap     = Wire(Vec(dsuparam.nrCore, Decoupled(new TaskBundle())))
    val mpTaskRedir     = Wire(Vec(dsuparam.nrCore, Vec(dsuparam.nrBank, Decoupled(new TaskBundle()))))
    val mpRespRedir     = Wire(Vec(dsuparam.nrBank, Vec(dsuparam.nrCore, Decoupled(new RespBundle()))))
    val snpTaskRedir    = Wire(Vec(dsuparam.nrBank, Vec(dsuparam.nrCore, Decoupled(new TaskBundle()))))
    val snpRespRedir    = Wire(Vec(dsuparam.nrCore, Vec(dsuparam.nrBank, Decoupled(new RespBundle()))))
    val wReqRemap       = Wire(Vec(dsuparam.nrCore, Decoupled(new CpuDBWReq())))
    val dbSigsRedir     = Wire(new Bundle {
            val wReq        = Vec(dsuparam.nrCore, Vec(dsuparam.nrBank, Decoupled(new CpuDBWReq())))
            val wResp       = Vec(dsuparam.nrBank, Vec(dsuparam.nrCore, Decoupled(new CpuDBWResp())))
            val dataFromDB  = Vec(dsuparam.nrBank, Vec(dsuparam.nrCore, Decoupled(new CpuDBOutData())))
            val dataToDB    = Vec(dsuparam.nrCore, Vec(dsuparam.nrBank, Decoupled(new CpuDBInData())))
    })


    // --------------------- Connection ------------------------//
    // mpTask idL1 reMap
    mpTaskRemap.zip(io.mpTask.in).foreach{ case(reMap, in) => reMap <> in }
    taskIdMaps.zipWithIndex.foreach {
        case(m, i) =>
            m.io.bankVal <> io.bankVal
            m.io.inBank := io.mpTask.in(i).bits.to.idL1
            mpTaskRemap(i).bits.to.idL1 := m.io.outBank
    }
    // wReq idL1 reMap
    wReqRemap.zip(io.dbSigs.in.map(_.wReq)).foreach { case(reMap, in) => reMap <> in }
    wReqIdMaps.zipWithIndex.foreach {
        case (m, i) =>
            m.io.bankVal <> io.bankVal
            m.io.inBank := io.dbSigs.in(i).wReq.bits.to.idL1
            wReqRemap(i).bits.to.idL1 :=  m.io.outBank
    }

    /*
    * connect cpuSalves <--[ctrl signals]--> slices
    */
    // mpTask ---[idSel]---[arb]---> mainPipe
    mpTaskRemap.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, mpTaskRedir(i), level = 1) }
    io.mpTask.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(mpTaskRedir.map(_(i)), m, Some("mpTaskArb")) }

    // mainPipe ---[fastArb]---[idSel]---> cpuSlaves
    io.mpResp.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, mpRespRedir(i), level = 1) }
    io.mpResp.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Val(mpRespRedir.map(_(i)), m, Some("mpRespArb")) }

    // snpTask ---[fastArb]---[idSel]---> cpuSlaves
    io.snpTask.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, snpTaskRedir(i), level = 1) }
    io.snpTask.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(snpTaskRedir.map(_(i)), m, Some("snpTaskArb")) }

    // snpResp ---[fastArb]---[idSel]---> snpCtrls
    io.snpResp.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, snpRespRedir(i), level = 1) }
    io.snpResp.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Val(snpRespRedir.map(_(i)), m, Some("snpRespArb")) }

    /*
    * connect cpuSalves <--[db signals]--> slices
    */
    // wReq ---[fastArb]---[idSel]---> dataBuffer
    wReqRemap.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.wReq(i), level = 1) }
    io.dbSigs.out.map(_.wReq).zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(dbSigsRedir.wReq.map(_(i)), m, Some("dbWReqArb")) }

    // wResp ---[fastArb]---[idSel]---> cpuSlaves
    io.dbSigs.out.map(_.wResp).zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.wResp(i), level = 1) }
    io.dbSigs.in.map(_.wResp).zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(dbSigsRedir.wResp.map(_(i)), m, Some("dbWRespArb")) }

    // dataFDB ---[fastArb]---[idSel]---> cpuSlaves
    io.dbSigs.out.map(_.dataFDB).zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.dataFromDB(i), level = 1) }
    io.dbSigs.in.map(_.dataFDB).zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(dbSigsRedir.dataFromDB.map(_(i)), m, Some("dataFDBArb")) }

    // dataTDB ---[fastArb]---[idSel]---> dataBuffer
    io.dbSigs.in.map(_.dataTDB).zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.dataToDB(i), level = 1) }
    io.dbSigs.out.map(_.dataTDB).zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(dbSigsRedir.dataToDB.map(_(i)), m, Some("dataTDBArb")) }



}