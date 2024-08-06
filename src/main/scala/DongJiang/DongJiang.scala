package DONGJIANG

import DONGJIANG.CHI._
import DONGJIANG.RNSLAVE._
import DONGJIANG.RNMASTER._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog
import Utils.IDConnector._
import Utils.FastArb._

abstract class DJModule(implicit val p: Parameters) extends Module with HasDJParam
abstract class DJBundle(implicit val p: Parameters) extends Bundle with HasDJParam


class DongJiang()(implicit p: Parameters) extends DJModule {
/*
 * System Architecture: (3 RNSLAVE, 1 RNMASTER and 2 bank)
 *
 *    ------------               ----------------------------------------------------------
 *    | RNSLAVE  | <---> | <---> |      |   Dir   |      |  SnpCtl  |                     |
 *    ------------       |       |      -----------      ------------      ---------      |        ------------
 *                       |       | ---> | Arbiter | ---> | MainPipe | ---> | Queue | ---> |  <---> | SNMASTER |
 *    ------------       |       |      -----------      ------------      ---------      |        ------------
 *    | RNSLAVE  | <---> | <---> |      |   DB    | <--> |    DS    |                     |
 *    ------------       |       ----------------------------------------------------------
 *                       |                              Slice
 *                      XBar
 *                       |
 *    ------------       |       ----------------------------------------------------------
 *    | RNSLAVE  | <---> | <---> |      |   Dir   |      |  SnpCtl  |                     |
 *    ------------       |       |      -----------      ------------      ---------      |        ------------
 *                       |       | ---> | Arbiter | ---> | MainPipe | ---> | Queue | ---> |  <---> | SNMASTER |
 *    ------------       |       |      -----------      ------------      ---------      |        ------------
 *    | RNMASTER | <---> | <---> |      |   DB    | <--> |    DS    |                     |
 *    ------------              -----------------------------------------------------------
 *                                                      Slice
 */




/*
 * System ID Map Table:
 * [Module]     |  [private ID]            |  [XBar ID]
 *
 * ****************************************************************************************************************************************************
 *
 * RnSlave <-> Slice Ctrl Signals:
 * [reqTSlice]  |  [hasAddr]               |  from: [RNSLV]  [nodeId]  [reqBufId]   | to: [SLICE]    [sliceId] [DontCare]
 * [respFSlice] |  [hasAddr]   [hasWay]    |  from: None                            | to: [RNSLV]    [nodeId]  [reqBufId]
 * [reqFSlice]  |  [hasAddr]               |  from: [SLICE]  [sliceId] [SnpCtlId]   | to: [RNSLV]    [nodeId'] [DontCare]             // nodeId' reMap in Xbar
 * [respTSlice] |  [HasDBID]               |  from: None                            | to: [SLICE]    [sliceId] [SnpCtlId / DontCare]
 *
 *
 * RnSlave <-> Slice DB Signals:
 * [wReq]       |  [None]                  |  from: [RNSLV]  [nodeId]  [reqBufId]   | to: [SLICE]    [sliceId] [DontCare]
 * [wResp]      |  [hasDBID]               |  from: [SLICE]  [sliceId] [DontCare]   | to: [RNSLV]    [nodeId]  [reqBufId]
 * [dataFDB]    |  [None]                  |  from: None                            | to: [RNSLV]    [nodeId]  [reqBufId]
 * [dataTDB]    |  [hasDBID]               |  from: None                            | to: [SLICE]    [sliceId] [DontCare]
 *
 * ****************************************************************************************************************************************************
 *
 * RnMaster <-> Slice Ctrl Signals:
 * [reqTSlice]  |  [hasAddr]               |  from: [RNMAS]  [nodeId] [reqBufId]    | to: [SLICE]    [sliceId] [DontCare]
 * [respFSlice] |  [hasAddr]   [hasWay]    |  from: None                            | to: [RNMAS]    [nodeId]  [reqBufId]
 * [reqFSlice]  |  [hasAddr]   [hasWay]    |  from: [SLICE]  [sliceId] [SnpCtlId]   | to: [RNMAS]    [nodeId]  [DontCare]
 * [updTSlice]  |  [hasSet]    [hasWay]    |  from: None                            | to: [SLICE]    [sliceId] [DontCare]
 *              |  [HasDBID]               |
 *
 *
 * RnMaster <-> Slice DB Signals:(Same as RnSlave expect dbRCReq)
 * [dbRCReq]    |  [hasDBID]               |  from: [RNMAS]  [nodeId]   [reqBufId]  | to: [SLICE]    [sliceId] [DontCare]
 * [wReq]       |  [None]                  |  from: [RNMAS]  [nodeId]   [reqBufId]  | to: [SLICE]    [sliceId] [DontCare]
 * [wResp]      |  [hasDBID]               |  from: [SLICE]  [sliceId]  [DontCare]  | to: [RN]       [nodeId]  [reqBufId]
 * [dataFDB]    |  [None]                  |  from: None                            | to: [RN]       [nodeId]  [reqBufId]
 * [dataTDB]    |  [hasDBID]               |  from: None                            | to: [SLICE]    [sliceId] [DontCare]
 *
 * ****************************************************************************************************************************************************
 *
 * Slice <-> SnMaster Ctrl Signals:
 * [reqFSlice]  |  [hasAddr]   [hasWay]    |  from: None                            | to: None
 * [updTSlice]  |  [hasSet]    [hasWay]    |  from: None                            | to: None
 *
 *
 * Slice <-> SnMaster DB Signals:
 * [wReq]       |  [hasRCID]               |  from: None                            | to: None
 * [wResp]      |  [hasDBID]   [hasRCID]   |  from: None                            | to: None
 * [dataFDB]    |  [hasSet]    [hasWay]    |  from: None                            | to: None
 * [dataTDB]    |  [hasDBID]               |  from: None                            | to: None
 *
 * ****************************************************************************************************************************************************
 *
 * MainPipe S4 Commit <-> DB Signals:
 * [dbRCReq]    |                                     |  from: None                 | to: [RNSLV/RNMAS]         [nodeId]  [reqBufId]
 *
 *
 * MainPipe S4 Commit <-> DS Signals:
 * [dsRWReq]    |  [hasSet]    [hasWay]   [hasDSID]   |  from: None                 | to: [RNSLV/RNMAS]         [nodeId]  [reqBufId]
 *
 *
 * DS <-> DB Signals:
 * [dbRCReq]    |  [hasSet]    [hasWay]   [hasDSID]   |  from: None                 | to: [RNSLV/RNMAS/SNMAS]   [nodeId]  [reqBufId] // Go to SN use Set and Way; Go to RN use to; Go to DS use DSID
 * [wReq]       |  [None]      [hasDSID]              |  from: None                 | to: None
 * [wResp]      |  [hasDBID]   [hasDSID]              |  from: None                 | to: None
 * [dataFDB]    |  [hasDSID]                          |  from: None                 | to: None
 * [dataTDB]    |  [hasDBID]                          |  from: None                 | to: None
 *
 */


/*
 * CHI ID Map Table:
 *
 * *********************************************************** RNSLAVE ***************************************************************************
 *
 * tgtNodeID    <-> Get from Slice req
 * nodeID       <-> RnSlave
 * reqBufId     <-> ReqBuf
 * fwdNId       <-> Get from Slice req
 * fwdTxnID     <-> Get from Slice req
 *
 *
 *
 * { Read / Dataless / Atomic / CMO }   TxReq: Store { TgtID_g = TgtID    |  SrcID_g = SrcID     |   TxnID_g = TxnID     |                      }
 * { CompAck                        }   TxRsp: Match {                    |                      |   TxnID  == reqBufId  |                      }
 * { CompData                       }   RxDat: Send  { TgtID   = SrcID_g  |  SrcID   = TgtID_g   |   TxnID   = TxnID_g   |  DBID    = reqBufId  }
 * { Comp                           }   RxRsp: Send  { TgtID   = SrcID_g  |  SrcID   = TgtID_g   |   TxnID   = TxnID_g   |                      }
 *
 *
 * { Write                          }   TxReq: Store { TgtID_g = TgtID    |  SrcID_g = SrcID     |   TxnID_g = TxnID     |                      }
 * { WriteData                      }   TxDat: Match {                    |                      |   TxnID  == reqBufId  |                      }
 * { CompDBIDResp                   }   RxRsp: Send  { TgtID   = SrcID_g  |  SrcID   = TgtID_g   |   TxnID   = TxnID_g   |  DBID    = reqBufId  }
 *
 *
 * { SnoopResp                      }   TxRsp: Match {                    |                      |   TxnID  == reqBufId  |                      }
 * { SnoopRespData                  }   TxDat: Match {                    |                      |   TxnID  == reqBufId  |                      }
 * { Snoop                          }   RxSnp: Send  { TgtID  = tgtNodeID |  SrcID   = nodeID    |   TxnID   = reqBufId  |                      }
 *
 *
 * { SnpRespFwded                   }   TxRsp: Match {                    |                      |   TxnID  == reqBufId  |                      }
 * { SnpRespDataFwded               }   TxDat: Match {                    |                      |   TxnID  == reqBufId  |                      }
 * { SnoopFwd                       }   RxSnp: Send  {                    |  SrcID   = nodeID    |   TxnID   = reqBufId  |                      |   FwdNId  = fwdNId    |   FwdTxnID    = fwdTxnID }
 *
 *
 *
 * *********************************************************** RNMASTRE *************************************************************************
 *
 * tgtNodeID    <-> Get from Slice req
 * nodeID       <-> RnMaster
 * reqBufId     <-> ReqBuf
 *
 *
 * { Read / Dataless / Atomic / CMO }   TxReq: Send  { TgtID = tgtNodeID  |  SrcID   = nodeID    |   TxnID   = reqBufId  |                      }
 * { CompAck                        }   TxRsp: Send  { TgtID = HomeNID_g  |  SrcID   = nodeID    |   TxnID   = DBID_g    |                      }
 * { CompData                       }   RxDat: M & S {                    |                      |   TxnID  == reqBufId  |  DBID_g  = DBID      |   HomeNID_g   = HomeNID   }
 * { Comp                           }   RxRsp: Match {                    |                      |   TxnID   = reqBufId  |  DBID_g  = DBID      |   HomeNID_g   = SrcID     }
 *
 *
 * { Write                          }   TxReq: Send  { TgtID = tgtNodeID  |  SrcID   = nodeID    |   TxnID   = reqBufId  |                      }
 * { WriteData                      }   TxDat: Send  { TgtID = tgtNodeID  |  SrcID   = nodeID    |   TxnID   = DBID_g    |                      }
 * { CompDBIDResp                   }   RxRsp: M & G {                    |                      |   TxnID  == reqBufId  |  DBID_g = DBID       }
 *
 *
 * { SnoopResp                      }   TxRsp: Match { TgtID = SrcID_g    |  SrcID   = nodeID    |   TxnID   = TxnID_g   |                      }
 * { SnoopRespData                  }   TxDat: Send  { TgtID = SrcID_g    |  SrcID   = nodeID    |   TxnID   = TxnID_g   |                      }
 * { Snoop                          }   RxSnp: Store {                    |  SrcID_g = SrcID     |   TxnID_g = TxnID     |                      }
 *
 *
 * { SnpRespFwded                   }   TxRsp: Match { TgtID = SrcID_g    |  SrcID   = nodeID    |   TxnID   = TxnID_g   |                      }
 * { SnpRespDataFwded               }   TxDat: Match { TgtID = SrcID_g    |  SrcID   = nodeID    |   TxnID   = TxnID_g   |                      }
 * { CompData                       }   TxDat: Match { TgtID = FwdNId_g   |  SrcID   = nodeID    |   TxnID   = FwdTxnID  |  DBID = TxnID_g      |   HomeNID     = SrcID_g   }
 * { SnoopFwd                       }   RxSnp: Store {                    |  SrcID_g = SrcID     |   TxnID_g = TxnID     |                      |   FwdNId_g    = FwdNId    |   FwdTxnID_g  = FwdTxnID }
 *
 *
 *
 * *********************************************************** SNMASTRE *************************************************************************
 *
 * reqBufId     <-> ReqBuf
 *
 * { Read                           }   TxReq: Send  { TgtID = 0          |  SrcID   = 0         |   TxnID   = reqBufId  |                      }
 * { CompData                       }   RxDat: Match {                    |                      |   TxnID  == reqBufId  |                      }
 *
 * { Write                          }   TxReq: Send  { TgtID = 0          |  SrcID   = 0         |   TxnID   = reqBufId  |                      }
 * { WriteData                      }   TxDat: Send  { TgtID = 0          |  SrcID   = 0         |   TxnID   = DBID_g    |                      }
 * { CompDBIDResp                   }   RxRsp: M & G {                    |                      |   TxnID  == reqBufId  |  DBID_g = DBID       }
 *
 */


// ------------------------------------------ IO declaration ----------------------------------------------//
    val io = IO(new Bundle {
        val rnSlvChi            = Vec(nrRnSlv, CHIBundleUpstream(chiParams))
        val rnSlvChiLinkCtrl    = Vec(nrRnSlv, Flipped(new CHILinkCtrlIO()))
        val rnMasChi            = Vec(nrRnMas, CHIBundleDownstream(chiParams))
        val rnMasChiLinkCtrl    = Vec(nrRnMas, new CHILinkCtrlIO())
        val snMasChi            = Vec(djparam.nrBank, CHIBundleDownstream(chiParams))
        val snMasChiLinkCtrl    = Vec(djparam.nrBank, new CHILinkCtrlIO())
    })

    io <> DontCare

// ------------------------------------------ Modules declaration ----------------------------------------------//
    def createRnSlv(id: Int) = { val rnSlv = Module(new RnSlave(id)); rnSlv }
    def createRnMas(id: Int) = { val rnMas = Module(new RnMaster(id)); rnMas }
    val rnSlaves    = (0 until nrRnSlv).map(i => createRnSlv(i))
    val rnMasters   = (nrRnSlv until nrRnNode).map(i => createRnMas(i))
    val rnNodes     = rnSlaves ++ rnMasters

    rnNodes.foreach(_.io <> DontCare)


// ---------------------------------------------- Connection ---------------------------------------------------//
    /*
     * Connect IO CHI
     */
    rnSlaves.map(_.chiIO.chnls).zip(io.rnSlvChi).foreach { case(a, b) => a <> b}
    rnSlaves.map(_.chiIO.linkCtrl).zip(io.rnSlvChiLinkCtrl).foreach{ case(a, b) => a <> b}

    rnMasters.map(_.chiIO.chnls).zip(io.rnMasChi).foreach { case (a, b) => a <> b }
    rnMasters.map(_.chiIO.linkCtrl).zip(io.rnMasChiLinkCtrl).foreach { case (a, b) => a <> b }



}
