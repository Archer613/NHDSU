package DONGJIANG

import DONGJIANG.CHI._
import DONGJIANG.RNSLAVE._
import DONGJIANG.SLICE._
import DONGJIANG.SNMASTER._
import chisel3.{Flipped, _}
import chisel3.util.{ValidIO, _}
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import Utils.IDConnector._
import Utils.FastArb._

// TODO: has some problem of XBar, req never enter slice_1

class IdMap(implicit p: Parameters) extends DJModule {
    val io = IO(new Bundle {
        val bankVal = Input(Vec(djparam.nrBank, Bool()))
        val inBank  = Input(UInt(bankBits.W))
        val outBank = Output(UInt(bankBits.W))
    })
    val outBank = WireInit(0.U(bankBits.W))
    val bankaValNum = WireInit(PopCount(io.bankVal).asUInt)

    if (djparam.nrBank == 4) {
        switch(bankaValNum) {
            // Use Bank [0]
            is(1.U) { outBank :=  0.U }
            // Use Bank [0 1]
            is(2.U) { outBank === io.inBank(0) }
            // Use Bank [0 1 2 3]
            is(4.U) { outBank === io.inBank }
        }
    } else if (djparam.nrBank == 2) {
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




class Xbar()(implicit p: Parameters) extends DJModule {
// ------------------------------------------ IO declaration ----------------------------------------------//
    val io = IO(new Bundle {
        val bankVal = Input(Vec(djparam.nrBank, Bool()))
        // snpCtrl
        val snpTask = new Bundle {
            val in = Vec(djparam.nrBank, Flipped(Decoupled(new SnpTaskBundle())))
            val out = Vec(djparam.nrCore, Decoupled(new SnpTaskBundle()))
        }
        val snpResp = new Bundle {
            val in = Vec(djparam.nrCore, Flipped(Decoupled(new SnpRespBundle())))
            val out = Vec(djparam.nrBank, ValidIO(new SnpRespBundle()))
        }
        // mainpipe
        val mpTask = new Bundle {
            val in = Vec(djparam.nrCore, Flipped(Decoupled(new TaskBundle())))
            val out = Vec(djparam.nrBank, Decoupled(new TaskBundle()))
        }
        val clTask = new Bundle {
            val in = Vec(djparam.nrCore, Flipped(Decoupled(new WCBTBundle())))
            val out = Vec(djparam.nrBank, Decoupled(new WCBTBundle()))
        }
        val mpResp = new Bundle {
            val in = Vec(djparam.nrBank, Flipped(Decoupled(new RespBundle())))
            val out = Vec(djparam.nrCore, ValidIO(new RespBundle()))
        }
        // dataBuffer
        val dbSigs = new Bundle {
            val in = Vec(djparam.nrCore, Flipped(new RnDBBundle()))
            val out = Vec(djparam.nrBank, new RnDBBundle())
        }
    })

    // ------------------------------------------ Modules declaration And Connection ----------------------------------------------//
    val taskIdMaps      = Seq.fill(djparam.nrCore) { Module(new IdMap()) }
    val clTaskIdMaps    = Seq.fill(djparam.nrCore) { Module(new IdMap()) }
    val wReqIdMaps      = Seq.fill(djparam.nrCore) { Module(new IdMap()) }

    // --------------------- Wire declaration ------------------------//
    val mpTaskRemap     = Wire(Vec(djparam.nrCore, Decoupled(new TaskBundle())))
    val mpTaskRedir     = Wire(Vec(djparam.nrCore, Vec(djparam.nrBank, Decoupled(new TaskBundle()))))
    val clTaskRemap     = Wire(Vec(djparam.nrCore, Decoupled(new WCBTBundle())))
    val clTaskRedir     = Wire(Vec(djparam.nrCore, Vec(djparam.nrBank, Decoupled(new WCBTBundle()))))
    val mpRespRedir     = Wire(Vec(djparam.nrBank, Vec(djparam.nrCore, Decoupled(new RespBundle()))))
    val snpTaskRedir    = Wire(Vec(djparam.nrBank, Vec(djparam.nrCore, Decoupled(new SnpTaskBundle()))))
    val snpRespRedir    = Wire(Vec(djparam.nrCore, Vec(djparam.nrBank, Decoupled(new SnpRespBundle()))))
    val wReqRemap       = Wire(Vec(djparam.nrCore, Decoupled(new RnDBWReq())))
    val dbSigsRedir     = Wire(new Bundle {
            val wReq        = Vec(djparam.nrCore, Vec(djparam.nrBank, Decoupled(new RnDBWReq())))
            val wResp       = Vec(djparam.nrBank, Vec(djparam.nrCore, Decoupled(new RnDBWResp())))
            val dataFromDB  = Vec(djparam.nrBank, Vec(djparam.nrCore, Decoupled(new RnDBOutData())))
            val dataToDB    = Vec(djparam.nrCore, Vec(djparam.nrBank, Decoupled(new RnDBInData())))
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
    // clean Task idL1 reMap
    clTaskRemap.zip(io.clTask.in).foreach { case (reMap, in) => reMap <> in }
    clTaskIdMaps.zipWithIndex.foreach {
        case (m, i) =>
            m.io.bankVal <> io.bankVal
            m.io.inBank := io.clTask.in(i).bits.to.idL1
            clTaskRemap(i).bits.to.idL1 := m.io.outBank
    }
    // wReq idL1 reMap
    wReqRemap.zip(io.dbSigs.in.map(_.wReq)).foreach { case(reMap, in) => reMap <> in }
    wReqIdMaps.zipWithIndex.foreach {
        case (m, i) =>
            m.io.bankVal <> io.bankVal
            m.io.inBank := io.dbSigs.in(i).wReq.bits.to.idL1
            wReqRemap(i).bits.to.idL1 :=  m.io.outBank
    }

    // TODO: Add Queue

    /*
    * connect rnSlaves <--[ctrl signals]--> slices
    */
    // mpTask ---[idSel]---[arb]---> mainPipe
    mpTaskRemap.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, mpTaskRedir(i), level = 1) }
    io.mpTask.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(mpTaskRedir.map(_(i)), m, Some("mpTaskArb")) }

    // mpTask ---[idSel]---[arb]---> mainPipe
    clTaskRemap.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, clTaskRedir(i), level = 1) }
    io.clTask.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(clTaskRedir.map(_(i)), m, Some("cleanTaskArb")) }

    // mainPipe ---[fastArb]---[idSel]---> rnSlaves
    io.mpResp.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, mpRespRedir(i), level = 1) }
    io.mpResp.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Val(mpRespRedir.map(_(i)), m, Some("mpRespArb")) }

    // snpTask ---[fastArb]---[idSel]---> rnSlaves
    io.snpTask.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, snpTaskRedir(i), level = 1) }
    io.snpTask.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(snpTaskRedir.map(_(i)), m, Some("snpTaskArb")) }

    // snpResp ---[fastArb]---[idSel]---> snpCtrls
    io.snpResp.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, snpRespRedir(i), level = 1) }
    io.snpResp.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Val(snpRespRedir.map(_(i)), m, Some("snpRespArb")) }

    /*
    * connect rnSlaves <--[db signals]--> slices
    */
    // wReq ---[fastArb]---[idSel]---> dataBuffer
    wReqRemap.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.wReq(i), level = 1) }
    io.dbSigs.out.map(_.wReq).zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(dbSigsRedir.wReq.map(_(i)), m, Some("dbWReqArb")) }

    // wResp ---[fastArb]---[idSel]---> rnSlaves
    io.dbSigs.out.map(_.wResp).zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.wResp(i), level = 1) }
    io.dbSigs.in.map(_.wResp).zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(dbSigsRedir.wResp.map{ case a => Queue(a(i), entries = 1) }, m, Some("dbWRespArb")) }

    // dataFDB ---[fastArb]---[idSel]---> rnSlaves
    io.dbSigs.out.map(_.dataFDB).zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.dataFromDB(i), level = 1) }
    io.dbSigs.in.map(_.dataFDB).zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(dbSigsRedir.dataFromDB.map(_(i)), m, Some("dataFDBArb")) }

    // dataTDB ---[fastArb]---[idSel]---> dataBuffer
    io.dbSigs.in.map(_.dataTDB).zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.dataToDB(i), level = 1) }
    io.dbSigs.out.map(_.dataTDB).zipWithIndex.foreach { case (m, i) => fastArbDec2Dec(dbSigsRedir.dataToDB.map(_(i)), m, Some("dataTDBArb")) }



}