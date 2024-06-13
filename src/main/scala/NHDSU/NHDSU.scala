package NHDSU

import NHDSU.CHI._
import NHDSU.CPUSALVE._
import NHDSU.SLICE._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import Utils.IDConnector._
import Utils.FastArb._

abstract class DSUModule(implicit val p: Parameters) extends Module with HasDSUParam
abstract class DSUBundle(implicit val p: Parameters) extends Bundle with HasDSUParam

class NHDSU()(implicit p: Parameters) extends DSUModule {
// ------------------------------------------ IO declaration ----------------------------------------------//
    val io = IO(new Bundle {
        val rnChi = Vec(dsuparam.nrCore, CHIBundleUpstream(chiBundleParams))
        val rnChiLinkCtrl = Vec(dsuparam.nrCore, Flipped(new CHILinkCtrlIO()))
        val snChi = Vec(dsuparam.nrBank, CHIBundleDownstream(chiBundleParams))
        val snChiLinkCtrl = Vec(dsuparam.nrBank, new CHILinkCtrlIO())
    })

    // TODO: Delete the following code when the coding is complete
    io.rnChi.foreach(_ := DontCare)
    io.rnChiLinkCtrl.foreach(_ := DontCare)
    io.snChi.foreach(_ := DontCare)
    io.snChiLinkCtrl.foreach(_ := DontCare)
    dontTouch(io)

//
//    ------------               ----------------------------------------------------------
//    | CPUSLAVE | <---> | <---> |      |   Dir   |      |  SnpCtl  |                     |
//    ------------       |       |      -----------      ------------      ---------      |        ----------
//                       |       | ---> | Arbiter | ---> | MainPipe | ---> | Queue | ---> |  <---> | Master |
//    ------------       |       |      -----------      ------------      ---------      |        ----------
//    | CPUSLAVE | <---> | <---> |      |   DB    | <--> |    DS    |                     |
//    ------------       |       ----------------------------------------------------------
//                       |                              Slice
//                      XBar
//                       |
//    ------------       |       ----------------------------------------------------------
//    | CPUSLAVE | <---> | <---> |      |   Dir   |      |  SnpCtl  |                     |
//    ------------       |       |      -----------      ------------      ---------      |        ----------
//                       |       | ---> | Arbiter | ---> | MainPipe | ---> | Queue | ---> |  <---> | Master |
//    ------------       |       |      -----------      ------------      ---------      |        ----------
//    | CPUSLAVE | <---> | <---> |      |   DB    | <--> |    DS    |                     |
//    ------------              -----------------------------------------------------------
//                                                      Slice


    // ------------------------------------------ Modules declaration And Connection ----------------------------------------------//
    // Modules declaration
    val cpuSalves = Seq.fill(dsuparam.nrCore) { Module(new CpuSlave()) }
    val slices = Seq.fill(dsuparam.nrBank) { Module(new Slice()) }

    cpuSalves.foreach(_.io <> DontCare)
    slices.foreach(_.io <> DontCare)

    // --------------------- Wire declaration ------------------------//
    val mpTask = Wire(Decoupled(new TaskBundle()))
    val mpResp = Wire(Valid(new TaskRespBundle()))
    val snpTask = Wire(Decoupled(new TaskBundle()))
    val snpResp = Wire(Valid(new TaskRespBundle()))
    val dbSigs = Wire(new Bundle {
        val req         = Valid(new DBReq())
        val wResp       = Valid(new DBResp())
        val dataFromDB  = Valid(new DBOutData())
        val dataToDB    = Valid(new DBInData())
    })

    // --------------------- Connection ------------------------//
    /*
    * connect cpuSalves <--[ctrl signals]--> slices
    */
    // mpTask ---[fastArb]---[idSel]---> mainPipe
    fastArbDec2Dec(cpuSalves.map(_.io.mpTask), mpTask, Some("mpTaskArb"))
    idSelDec2DecVec(mpTask, slices.map(_.io.cpuTask), level = 2)

    // mainPipe ---[fastArb]---[idSel]---> cpuSlaves
    fastArbDec2Val(slices.map(_.io.cpuResp), mpResp, Some("mpRespArb"))
    idSelVal2ValVec(mpResp, cpuSalves.map(_.io.mpResp), level = 2)

    // snpTask ---[fastArb]---[idSel]---> cpuSlaves
    fastArbDec2Dec(slices.map(_.io.snpTask), snpTask, Some("snpTaskArb"))
    idSelDec2DecVec(snpTask, cpuSalves.map(_.io.snpTask), level = 2)

    // snpResp ---[fastArb]---[idSel]---> snpCtrls
    fastArbDec2Val(cpuSalves.map(_.io.snpResp), snpResp, Some("snpRespArb"))
    idSelVal2ValVec(snpResp, slices.map(_.io.snpResp), level = 2)

    /*
    * connect cpuSalves <--[ctrl signals]--> slices
    */
    // req ---[fastArb]---[idSel]---> dataBuffer
    fastArbDec2Val(cpuSalves.map(_.io.dbSigs.req), dbSigs.req, Some("dbReqArb"))
    idSelVal2ValVec(dbSigs.req, slices.map(_.io.dbSigs2Cpu.req), level = 2)

    // resp ---[fastArb]---[idSel]---> cpuSlaves
    fastArbDec2Val(slices.map(_.io.dbSigs2Cpu.wResp), dbSigs.wResp, Some("dbRespArb"))
    idSelVal2ValVec(dbSigs.wResp, cpuSalves.map(_.io.dbSigs.wResp), level = 2)

    // dataFDB ---[fastArb]---[idSel]---> cpuSlaves
    fastArbDec2Val(slices.map(_.io.dbSigs2Cpu.dataFromDB), dbSigs.dataFromDB, Some("dataFDBArb"))
    idSelVal2ValVec(dbSigs.dataFromDB, cpuSalves.map(_.io.dbSigs.dataFromDB), level = 2)

    // dataTDB ---[fastArb]---[idSel]---> dataBuffer
    fastArbDec2Val(cpuSalves.map(_.io.dbSigs.dataToDB), dbSigs.dataToDB, Some("dataTDBArb"))
    idSelVal2ValVec(dbSigs.dataToDB, slices.map(_.io.dbSigs2Cpu.dataToDB), level = 2)












    
}


object DSU extends App {
    val config = new Config((_, _, _) => {
        case DSUParamKey     => DSUParam()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new NHDSU()(config), name = "DSU", split = false)
}