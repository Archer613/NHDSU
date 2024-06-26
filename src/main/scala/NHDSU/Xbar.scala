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
        val in = Flipped(Decoupled(new TaskBundle()))
        val out = Decoupled(new TaskBundle())
    })

    io.in <> io.out
    val bankaValNum = WireInit(PopCount(io.bankVal).asUInt)

    val inBank = parseAddress(io.in.bits.addr)._2

    if (dsuparam.nrBank == 4) {
        switch(bankaValNum) {
            // Use Bank [0]
            is(1.U) {
                io.out.bits.to.idL1 === 0.U
            }
            // Use Bank [0 1]
            is(2.U) {
                io.out.bits.to.idL1 === inBank(0)
            }
            // Use Bank [0 1 2 3]
            is(4.U) {
                io.out.bits.to.idL1 === inBank
            }
        }
    }else if (dsuparam.nrBank == 2) {
        switch(bankaValNum) {
            // Use Bank [0]
            is(1.U) {
                io.out.bits.to.idL1 === 0.U
            }
            // Use Bank [0 1]
            is(2.U) {
                io.out.bits.to.idL1 === inBank(0)
            }
        }
    }else {
        // Use Bank [0]
        io.out.bits.to.idL1 === 0.U
    }

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
            val in = Vec(dsuparam.nrCore, Flipped(Decoupled(new TaskRespBundle())))
            val out = Vec(dsuparam.nrBank, ValidIO(new TaskRespBundle()))
        }
        // mainpipe
        val mpTask = new Bundle {
            val in = Vec(dsuparam.nrCore, Flipped(Decoupled(new TaskBundle())))
            val out = Vec(dsuparam.nrBank, Decoupled(new TaskBundle()))
        }
        val mpResp = new Bundle {
            val in = Vec(dsuparam.nrBank, Flipped(Decoupled(new TaskRespBundle())))
            val out = Vec(dsuparam.nrCore, ValidIO(new TaskRespBundle()))
        }
        // dataBuffer
        val dbSigs = new Bundle {
            val req = new Bundle {
                val in = Vec(dsuparam.nrCore, Flipped(Decoupled(new DBReq())))
                val out = Vec(dsuparam.nrBank, ValidIO(new DBReq()))
            }
            val wResp = new Bundle {
                val in = Vec(dsuparam.nrBank, Flipped(Decoupled(new DBResp())))
                val out = Vec(dsuparam.nrCore, ValidIO(new DBResp()))
            }
            val dataFromDB = new Bundle {
                val in = Vec(dsuparam.nrBank, Flipped(Decoupled(new DBOutData())))
                val out = Vec(dsuparam.nrCore, ValidIO(new DBOutData()))
            }
            val dataToDB = new Bundle {
                val in = Vec(dsuparam.nrCore, Flipped(Decoupled(new DBInData())))
                val out = Vec(dsuparam.nrBank, ValidIO(new DBInData()))
            }
        }
    })

    // ------------------------------------------ Modules declaration And Connection ----------------------------------------------//
    val idMaps = Seq.fill(dsuparam.nrCore) { Module(new IdMap()) }

    // --------------------- Wire declaration ------------------------//
    val mpTaskRemap     = Wire(Vec(dsuparam.nrCore, Decoupled(new TaskBundle())))
    val mpTaskRedir     = Wire(Vec(dsuparam.nrCore, Vec(dsuparam.nrBank, Decoupled(new TaskBundle()))))
    val mpRespRedir     = Wire(Vec(dsuparam.nrBank, Vec(dsuparam.nrCore, Decoupled(new TaskRespBundle()))))
    val snpTaskRedir    = Wire(Vec(dsuparam.nrBank, Vec(dsuparam.nrCore, Decoupled(new TaskBundle()))))
    val snpRespRedir    = Wire(Vec(dsuparam.nrCore, Vec(dsuparam.nrBank, Decoupled(new TaskRespBundle()))))
    val dbSigsRedir     = Wire(new Bundle {
            val req         = Vec(dsuparam.nrCore, Vec(dsuparam.nrBank, Decoupled(new DBReq())))
            val wResp       = Vec(dsuparam.nrBank, Vec(dsuparam.nrCore, Decoupled(new DBResp())))
            val dataFromDB  = Vec(dsuparam.nrBank, Vec(dsuparam.nrCore, Decoupled(new DBOutData())))
            val dataToDB    = Vec(dsuparam.nrCore, Vec(dsuparam.nrBank, Decoupled(new DBInData())))
    })


    // --------------------- Connection ------------------------//
    // mpTask idL1 map
    idMaps.zipWithIndex.foreach {
        case(m, i) =>
            m.io.bankVal <> io.bankVal
            m.io.in <> io.mpTask.in(i)
            m.io.out <> mpTaskRemap(i)
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
    // req ---[fastArb]---[idSel]---> dataBuffer
    io.dbSigs.req.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.req(i), level = 1) }
    io.dbSigs.req.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Val(dbSigsRedir.req.map(_(i)), m, Some("dbReqArb")) }

    // resp ---[fastArb]---[idSel]---> cpuSlaves
    io.dbSigs.wResp.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.wResp(i), level = 1) }
    io.dbSigs.wResp.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Val(dbSigsRedir.wResp.map(_(i)), m, Some("dbRespArb")) }

    // dataFDB ---[fastArb]---[idSel]---> cpuSlaves
    io.dbSigs.dataFromDB.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.dataFromDB(i), level = 1) }
    io.dbSigs.dataFromDB.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Val(dbSigsRedir.dataFromDB.map(_(i)), m, Some("dataFDBArb")) }

    // dataTDB ---[fastArb]---[idSel]---> dataBuffer
    io.dbSigs.dataToDB.in.zipWithIndex.foreach { case (m, i) => idSelDec2DecVec(m, dbSigsRedir.dataToDB(i), level = 1) }
    io.dbSigs.dataToDB.out.zipWithIndex.foreach { case (m, i) => fastArbDec2Val(dbSigsRedir.dataToDB.map(_(i)), m, Some("dataTDBArb")) }



}