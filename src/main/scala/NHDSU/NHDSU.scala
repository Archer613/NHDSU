package NHDSU

import NHDSU.CHI._
import NHDSU.CPUSALVE._
import NHDSU.SLICE._
import NHDSU.DSUMASTER._
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
    val dsuMasters = Seq.fill(dsuparam.nrBank) { Module(new DSUMaster()) }
    val xbar = Module(new Xbar())

    cpuSalves.foreach(m => dontTouch(m.io))
    slices.foreach(m => dontTouch(m.io))
    dsuMasters.foreach(m => dontTouch(m.io))
    dontTouch(xbar.io)

    /*
    * Set cpuSalves.io.cpuSlvId value
    */
    cpuSalves.map(_.io.cpuSlvId).zipWithIndex.foreach { case(id, i) => id := i.U }

    /*
    * connect RN <--[CHI signals]--> cpuSlaves
    * connect dsuMasters <--[CHI signals]--> SN
    */
    io.rnChi.zip(cpuSalves.map(_.io.chi)).foreach { case (r, c) => r <> c }
    io.rnChiLinkCtrl.zip(cpuSalves.map(_.io.chiLinkCtrl)).foreach { case (r, c) => r <> c }

    io.snChi.zip(dsuMasters.map(_.io.chi)).foreach { case (s, d) => s <> d }
    io.snChiLinkCtrl.zip(dsuMasters.map(_.io.chiLinkCtrl)).foreach { case (s, d) => s <> d }

    /*
    * connect cpuslaves <-----> xbar <------> slices
    */
    xbar.io.bankVal := slices.map(_.io.valid)

    xbar.io.snpTask.in <> slices.map(_.io.snpTask)
    xbar.io.snpTask.out <> cpuSalves.map(_.io.snpTask)

    xbar.io.snpResp.in <> cpuSalves.map(_.io.snpResp)
    xbar.io.snpResp.out <> slices.map(_.io.snpResp)

    xbar.io.mpTask.in <> cpuSalves.map(_.io.mpTask)
    xbar.io.mpTask.out <> slices.map(_.io.cpuTask)

    xbar.io.mpResp.in <> slices.map(_.io.cpuResp)
    xbar.io.mpResp.out <> cpuSalves.map(_.io.mpResp)

    xbar.io.dbSigs.req.in <> cpuSalves.map(_.io.dbSigs.req)
    xbar.io.dbSigs.req.out <> slices.map(_.io.dbSigs2Cpu.req)

    xbar.io.dbSigs.wResp.in <> slices.map(_.io.dbSigs2Cpu.wResp)
    xbar.io.dbSigs.wResp.out <> cpuSalves.map(_.io.dbSigs.wResp)

    xbar.io.dbSigs.dataFromDB.in <> slices.map(_.io.dbSigs2Cpu.dataFromDB)
    xbar.io.dbSigs.dataFromDB.out <> cpuSalves.map(_.io.dbSigs.dataFromDB)

    xbar.io.dbSigs.dataToDB.in <> cpuSalves.map(_.io.dbSigs.dataToDB)
    xbar.io.dbSigs.dataToDB.out <> slices.map(_.io.dbSigs2Cpu.dataToDB)

    /*
    * connect slices <--[ctrl/db signals]--> dsuMasters
    */
    slices.zip(dsuMasters).foreach {
        case (s, m) =>
            s.io.msTask <> m.io.mpTask
            s.io.msResp <> m.io.mpResp
            s.io.dbSigs2Ms <> m.io.dbSigs
    }

}


object DSU extends App {
    val config = new Config((_, _, _) => {
        case DSUParamKey     => DSUParam()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new NHDSU()(config), name = "DSU", split = false)
}