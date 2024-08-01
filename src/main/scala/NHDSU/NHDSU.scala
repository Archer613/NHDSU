package NHDSU

import NHDSU.CHI._
import NHDSU.RNSLAVE._
import NHDSU.SLICE._
import NHDSU.SNMASTER._
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

/*
 * System Architecture: (4 CORE and 2 bank)
 *
 *    ------------               ----------------------------------------------------------
 *    | RNSLAVE  | <---> | <---> |      |   Dir   |      |  SnpCtl  |                     |
 *    ------------       |       |      -----------      ------------      ---------      |        ----------
 *                       |       | ---> | Arbiter | ---> | MainPipe | ---> | Queue | ---> |  <---> | Master |
 *    ------------       |       |      -----------      ------------      ---------      |        ----------
 *    | RNSLAVE  | <---> | <---> |      |   DB    | <--> |    DS    |                     |
 *    ------------       |       ----------------------------------------------------------
 *                       |                              Slice
 *                      XBar
 *                       |
 *    ------------       |       ----------------------------------------------------------
 *    | RNSLAVE  | <---> | <---> |      |   Dir   |      |  SnpCtl  |                     |
 *    ------------       |       |      -----------      ------------      ---------      |        ----------
 *                       |       | ---> | Arbiter | ---> | MainPipe | ---> | Queue | ---> |  <---> | Master |
 *    ------------       |       |      -----------      ------------      ---------      |        ----------
 *    | RNSLAVE  | <---> | <---> |      |   DB    | <--> |    DS    |                     |
 *    ------------              -----------------------------------------------------------
 *                                                      Slice
 */




/*
 * System ID Map Table:
 * [Module]   |  [private ID]            |  [XBar ID]
 *
 *
 * RnSlave <-> Slice Ctrl Signals:
 * [rnTask]  |  [hasAddr]                |  from: [RN]    [coreId]  [reqBufId]    | to: [SLICE]    [sliceId] [DontCare]
 * [mpResp]  |  [hasAddr]   [hasWay]     |  from: None                            | to: [RN]       [coreId]  [reqBufId]
 * [snpTask] |  [hasAddr]                |  from: [SLICE]  [sliceId] [SnpCtlId]   | to: [RN]       [coreId]  [DontCare]
 * [rnResp]  |  [hasSet]    [hasWay]     |  from: None                            | to: [SLICE]    [sliceId] [SnpCtlId / DontCare]
 *
 *
 * RnSlave <-> Slice DB Signals:
 * [wReq]     |  [None]                  |  from: [RN]    [coreId]  [reqBufId]    | to: [SLICE]    [sliceId] [DontCare]
 * [wResp]    |  [hasDBID]               |  from: None                            | to: [RN]       [coreId]  [reqBufId]
 * [dataFDB]  |  [None]                  |  from: None                            | to: [RN]       [coreId]  [reqBufId]
 * [dataTDB]  |  [hasDBID]               |  from: None                            | to: [SLICE]    [sliceId] [DontCare]
 *
 *
 * Slice <-> Master Ctrl Signals:
 * [mpTask]   |  [hasAddr]   [hasWay]    |  from: None                            | to: None
 * [msResp]   |  [hasSet]    [hasWay]    |  from: None                            | to: None
 *
 *
 * Slice <-> Master DB Signals:
 * [wReq]     |  [hasRCID]               |  from: None                            | to: None
 * [wResp]    |  [hasDBID]   [hasRCID]   |  from: None                            | to: None
 * [dataFDB]  |  [hasSet]    [hasWay]    |  from: None                            | to: None
 * [dataTDB]  |  [hasDBID]               |  from: None                            | to: None
 *
 *
 * MainPipe S4 Commit <-> DB Signals:
 * [dbRCReq]  |  [hasSet]    [hasWay]    |  from: None                            | to: [RN]       [coreId]  [reqBufId]
 *
 *
 * DS <-> DB Signals:
 * [dbRCReq]  |  [hasSet]    [hasWay]   [hasDSID]   |  from: None                 | to: [RN]       [coreId]  [reqBufId] // Go to Master use Set and Way; Go to RN use to; Go to DS use DSID
 * [wReq]     |  [None]      [hasDSID]              |  from: None                 | to: None
 * [wResp]    |  [hasDBID]   [hasDSID]              |  from: None                 | to: None
 * [dataFDB]  |  [hasDSID]                          |  from: None                 | to: [RN]       [coreId]  [reqBufId]
 * [dataTDB]  |  [hasDBID]                          |  from: None                 | to: None
 *
 */


/*
 * CHI From Master:
 * TxReq: Txnid store in ReqBuf
 * TXDat:
 * TXRsp:
 *
 *
 * CHI To Slave:
 *
 *
 */



    // ------------------------------------------ Modules declaration And Connection ----------------------------------------------//
    // Modules declaration
    val rnSlaves = Seq.fill(dsuparam.nrCore) { Module(new RnSlave()) }
    val slices = Seq.fill(dsuparam.nrBank) { Module(new Slice()) }
    val dsuMasters = Seq.fill(dsuparam.nrBank) { Module(new SnMaster()) }
    val xbar = Module(new Xbar())

    rnSlaves.foreach(m => dontTouch(m.io))
    slices.foreach(m => dontTouch(m.io))
    dsuMasters.foreach(m => dontTouch(m.io))
    dontTouch(xbar.io)

    /*
    * Set rnSlaves.io.rnSlvId value
    */
    rnSlaves.map(_.io.rnSlvId).zipWithIndex.foreach { case(id, i) => id := i.U }

    /*
    * connect RN <--[CHI signals]--> rnSlaves
    * connect dsuMasters <--[CHI signals]--> SN
    */
    io.rnChi.zip(rnSlaves.map(_.io.chi)).foreach { case (r, c) => r <> c }
    io.rnChiLinkCtrl.zip(rnSlaves.map(_.io.chiLinkCtrl)).foreach { case (r, c) => r <> c }

    io.snChi.zip(dsuMasters.map(_.io.chi)).foreach { case (s, d) => s <> d }
    io.snChiLinkCtrl.zip(dsuMasters.map(_.io.chiLinkCtrl)).foreach { case (s, d) => s <> d }

    /*
    * connect rnSlaves <-----> xbar <------> slices
    */
    xbar.io.bankVal := slices.map(_.io.valid)

    xbar.io.snpTask.in <> slices.map(_.io.snpTask)
    xbar.io.snpTask.out <> rnSlaves.map(_.io.snpTask)

    xbar.io.snpResp.in <> rnSlaves.map(_.io.snpResp)
    xbar.io.snpResp.out <> slices.map(_.io.snpResp)

    xbar.io.mpTask.in <> rnSlaves.map(_.io.mpTask)
    xbar.io.mpTask.out <> slices.map(_.io.rnTask)

    xbar.io.clTask.in <> rnSlaves.map(_.io.clTask)
    xbar.io.clTask.out <> slices.map(_.io.rnClTask)

    xbar.io.mpResp.in <> slices.map(_.io.rnResp)
    xbar.io.mpResp.out <> rnSlaves.map(_.io.mpResp)

    xbar.io.dbSigs.in <> rnSlaves.map(_.io.dbSigs)
    xbar.io.dbSigs.out <> slices.map(_.io.dbSigs2Rn)

    /*
    * connect slices <--[ctrl/db signals]--> dsuMasters
    */
    slices.zipWithIndex.foreach{ case(s, i) => s.io.sliceId := i.U}
    slices.zip(dsuMasters).foreach {
        case (s, m) =>
            s.io.msTask <> m.io.mpTask
            s.io.msResp <> m.io.mpResp
            s.io.dbSigs2Ms <> m.io.dbSigs
            s.io.msClTask <> m.io.clTask
    }

}


object DSU extends App {
    val config = new Config((_, _, _) => {
        case DSUParamKey     => DSUParam()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new NHDSU()(config), name = "DSU", split = false)
}