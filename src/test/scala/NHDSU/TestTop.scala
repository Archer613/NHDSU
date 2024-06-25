package NHDSU

import NHDSU.CHI._
import chisel3._
import circt.stage.{ChiselStage, FirtoolOption}
import chisel3.util.{circt, _}
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile.MaxHartIdBits
import huancun.AliasField
import coupledL2.prefetch._
import coupledL2.tl2chi._
import utility.{ChiselDB, FileRegisters, TLLogger}
import coupledL2._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}

class TestTop_CHIL2(numCores: Int = 1, numULAgents: Int = 0, banks: Int = 1)(implicit p: Parameters) extends LazyModule
  with HasCHIMsgParameters {

  /*   L1D(L1I)* L1D(L1I)* ... L1D(L1I)*
   *       \         |          /
   *       L2        L2   ...  L2
   *         \       |        /
   *          \      |       /
   *                DSU
   */

  override lazy val desiredName: String = "TestTop"
  val delayFactor = 0.5
  val cacheParams = p(L2ParamKey)

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l1d_nodes = (0 until numCores).map(i => createClientNode(s"l1d$i", 32))
  val l1i_nodes = (0 until numCores).map {i =>
    (0 until numULAgents).map { j =>
      TLClientNode(Seq(
        TLMasterPortParameters.v1(
          clients = Seq(TLMasterParameters.v1(
            name = s"l1i${i}_${j}",
            sourceId = IdRange(0, 32)
          ))
        )
      ))
    }
  }

  // val l2 = LazyModule(new TL2CHICoupledL2())
  val l2_nodes = (0 until numCores).map(i => LazyModule(new TL2CHICoupledL2()(new Config((_, _, _) => {
    case L2ParamKey => cacheParams.copy(
      name                = s"L2_$i",
      hartId              = i,
    )
    case EnableCHI => true
    case BankBitsKey => log2Ceil(banks)
    case MaxHartIdBits => log2Up(numCores)
  }))))

  val bankBinders = (0 until numCores).map(_ => BankBinder(banks, 64))

  l1d_nodes.zip(l2_nodes).zipWithIndex.foreach { case ((l1d, l2), i) =>
    val l1xbar = TLXbar()
    l1xbar := 
      TLBuffer() :=
      TLLogger(s"L2_L1[${i}].C[0]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := 
      l1d

    l1i_nodes(i).zipWithIndex.foreach { case (l1i, j) =>
      l1xbar :=
        TLBuffer() :=
        TLLogger(s"L2_L1[${i}].UL[${j}]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) :=
        l1i
    }
    
    l2.managerNode :=
      TLXbar() :=*
      bankBinders(i) :*=
      l2.node :*=
      l1xbar
    /**
      * MMIO: make diplomacy happy
      */
    val mmioClientNode = TLClientNode(Seq(
      TLMasterPortParameters.v1(
        clients = Seq(TLMasterParameters.v1(
          "uncache"
        ))
      )
    ))
    l2.mmioBridge.mmioNode := mmioClientNode
  }

  lazy val module = new LazyModuleImp(this){
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    l1d_nodes.zipWithIndex.foreach{
      case (node, i) =>
        node.makeIOs()(ValName(s"master_port_$i"))
    }
    if (numULAgents != 0) {
      l1i_nodes.zipWithIndex.foreach { case (core, i) =>
        core.zipWithIndex.foreach { case (node, j) =>
          node.makeIOs()(ValName(s"master_ul_port_${i}_${j}"))
        }
      }
    }


    l2_nodes.zipWithIndex.foreach { case (l2, i) =>

      l2.module.io.hartId := i.U
      l2.module.io_nodeID := i.U(NODEID_WIDTH.W)
      l2.module.io.debugTopDown := DontCare
      l2.module.io.l2_tlb_req <> DontCare
    }

    val dsu = Module(new NHDSU())
    // TODO: Connect IO_SN <-> ARM_SN
//    val io = IO(new Bundle {
//      val snChi = Vec(dsu.dsuparam.nrBank, CHIBundleDownstream(dsu.chiBundleParams))
//      val snChiLinkCtrl = Vec(dsu.dsuparam.nrBank, new CHILinkCtrlIO())
//    })
//    dsu.io.snChi <> io.snChi
//    dsu.io.snChiLinkCtrl <> io.snChiLinkCtrl
      dsu.io.snChi <> DontCare
      dsu.io.snChiLinkCtrl <> DontCare

    dontTouch(dsu.io)
    dontTouch(l2_nodes(0).module.io_chi)

    // chil2 tansfer to nhdsu
    val l2Chi                               = l2_nodes(0).module.io_chi
    // linkCtrl
    dsu.io.rnChiLinkCtrl(0).txsactive       := l2Chi.txsactive
    l2Chi.rxsactive                         := dsu.io.rnChiLinkCtrl(0).rxsactive
    // tx linkCtrl
    dsu.io.rnChiLinkCtrl(0).txactivereq     := l2Chi.tx.linkactivereq
    l2Chi.tx.linkactiveack                  :=  dsu.io.rnChiLinkCtrl(0).txactiveack
    // rx linkCtrl
    l2Chi.rx.linkactivereq                  :=  dsu.io.rnChiLinkCtrl(0).rxactivereq
    dsu.io.rnChiLinkCtrl(0).rxactiveack     := l2Chi.rx.linkactiveack

        // txreq ctrl
    dsu.io.rnChi(0).txreq.flitpend          := l2Chi.tx.req.flitpend
    dsu.io.rnChi(0).txreq.flitv             := l2Chi.tx.req.flitv
    l2Chi.tx.req.lcrdv                      := dsu.io.rnChi(0).txreq.lcrdv
    // txreqflit
    val txreq                               = Wire(new CHIBundleREQ(dsu.chiBundleParams))
    dsu.io.rnChi(0).txreq.flit              := txreq
    txreq.qos                               := l2Chi.tx.req.flit.qos
    txreq.tgtID                             := l2Chi.tx.req.flit.tgtID
    txreq.srcID                             := l2Chi.tx.req.flit.srcID
    txreq.txnID                             := l2Chi.tx.req.flit.txnID
    txreq.returnNID                         := l2Chi.tx.req.flit.returnNID
    txreq.returnTxnID                       := DontCare
    txreq.opcode                            := l2Chi.tx.req.flit.opcode
    txreq.size                              := l2Chi.tx.req.flit.size
    txreq.addr                              := l2Chi.tx.req.flit.addr
    txreq.ns                                := l2Chi.tx.req.flit.ns
    txreq.lpID                              := DontCare
    txreq.excl                              := DontCare
    txreq.likelyShared                      := l2Chi.tx.req.flit.likelyshared
    txreq.allowRetry                        := l2Chi.tx.req.flit.allowRetry
    txreq.order                             := l2Chi.tx.req.flit.order
    txreq.pCrdType                          := l2Chi.tx.req.flit.pCrdType
    txreq.memAttr                           := l2Chi.tx.req.flit.memAttr.asUInt
    txreq.snpAttr                           := l2Chi.tx.req.flit.snpAttr.asUInt
    txreq.traceTag                          := DontCare
    txreq.rsvdc                             := DontCare
//    txreq.cah := l2Chi.tx.req.flit
    // txreq.excl         := l2Chi.tx.req.flit
    // txreq.snoopMe      := l2Chi.tx.req.flit
    txreq.expCompAck                        := l2Chi.tx.req.flit.expCompAck

    //txdat ctrl
    dsu.io.rnChi(0).txdat.flitpend := l2Chi.tx.dat.flitpend
    dsu.io.rnChi(0).txdat.flitv    := l2Chi.tx.dat.flitv
    l2Chi.tx.dat.lcrdv             := dsu.io.rnChi(0).txdat.lcrdv
    //txdatflit
    val txdat = Wire(new CHIBundleDAT(dsu.chiBundleParams))
    dsu.io.rnChi(0).txdat.flit     := txdat
    txdat.qos                      := l2Chi.tx.dat.flit.qos
    txdat.tgtID                    := l2Chi.tx.dat.flit.tgtID
    txdat.srcID                    := l2Chi.tx.dat.flit.srcID
    txdat.txnID                    := l2Chi.tx.dat.flit.txnID
    txdat.homeNID                  := l2Chi.tx.dat.flit.homeNID
    txdat.opcode                   := l2Chi.tx.dat.flit.opcode
    txdat.respErr                  := l2Chi.tx.dat.flit.respErr
    txdat.resp                     := l2Chi.tx.dat.flit.resp
    txdat.fwdState                 := l2Chi.tx.dat.flit.fwdState
    txdat.dbID                     := l2Chi.tx.dat.flit.dbID
    txdat.ccID                     := l2Chi.tx.dat.flit.ccID
    txdat.dataID                   := l2Chi.tx.dat.flit.dataID
    txdat.traceTag                 := l2Chi.tx.dat.flit.traceTag
    txdat.rsvdc                    := l2Chi.tx.dat.flit.rsvdc
    txdat.be                       := l2Chi.tx.dat.flit.be
    txdat.data                     := l2Chi.tx.dat.flit.data

    //txrsp ctrl
    dsu.io.rnChi(0).txrsp.flitpend := l2Chi.tx.rsp.flitpend
    dsu.io.rnChi(0).txrsp.flitv    := l2Chi.tx.rsp.flitv
    l2Chi.tx.rsp.lcrdv             := dsu.io.rnChi(0).txrsp.lcrdv
    //txrspflit
    val txrsp = Wire(new CHIBundleRSP(dsu.chiBundleParams))
    dsu.io.rnChi(0).txrsp.flit     := txrsp
    txrsp.qos                      := l2Chi.tx.rsp.flit.qos
    txrsp.tgtID                    := l2Chi.tx.rsp.flit.tgtID
    txrsp.srcID                    := l2Chi.tx.rsp.flit.srcID
    txrsp.txnID                    := l2Chi.tx.rsp.flit.txnID
    txrsp.opcode                   := l2Chi.tx.rsp.flit.opcode
    txrsp.respErr                  := l2Chi.tx.rsp.flit.respErr
    txrsp.resp                     := l2Chi.tx.rsp.flit.resp
    txrsp.fwdState                 := l2Chi.tx.rsp.flit.fwdState
    txrsp.dbID                     := l2Chi.tx.rsp.flit.dbID
    txrsp.pCrdType                 := l2Chi.tx.rsp.flit.pCrdType
    txrsp.traceTag                 := l2Chi.tx.rsp.flit.traceTag

    //rxrsp ctrl
    l2Chi.rx.rsp.flitpend          := dsu.io.rnChi(0).rxrsp.flitpend
    l2Chi.rx.rsp.flitv             := dsu.io.rnChi(0).rxrsp.flitv
    dsu.io.rnChi(0).rxrsp.lcrdv    := l2Chi.rx.rsp.lcrdv
    //rxrspflit
    val rxrsp = Wire(new CHIBundleRSP(dsu.chiBundleParams))
    rxrsp                          := dsu.io.rnChi(0).rxrsp.flit
    l2Chi.rx.rsp.flit.qos          := rxrsp.qos
    l2Chi.rx.rsp.flit.tgtID        := rxrsp.tgtID
    l2Chi.rx.rsp.flit.srcID        := rxrsp.srcID
    l2Chi.rx.rsp.flit.txnID        := rxrsp.txnID
    l2Chi.rx.rsp.flit.opcode       := rxrsp.opcode
    l2Chi.rx.rsp.flit.respErr      := rxrsp.respErr
    l2Chi.rx.rsp.flit.resp         := rxrsp.resp
    l2Chi.rx.rsp.flit.fwdState     := rxrsp.fwdState
    l2Chi.rx.rsp.flit.dbID         := rxrsp.dbID
    l2Chi.rx.rsp.flit.pCrdType     := rxrsp.pCrdType
    l2Chi.rx.rsp.flit.traceTag     := rxrsp.traceTag

    //rxdat ctrl
    l2Chi.rx.dat.flitpend          := dsu.io.rnChi(0).rxdat.flitpend
    l2Chi.rx.dat.flitv             := dsu.io.rnChi(0).rxdat.flitv
    dsu.io.rnChi(0).rxdat.lcrdv    := l2Chi.rx.dat.lcrdv
    //rxdatflit
    val rxdat = Wire(new CHIBundleDAT(dsu.chiBundleParams))
    rxdat                          := dsu.io.rnChi(0).rxdat.flit
    l2Chi.rx.dat.flit.qos          := rxdat.qos
    l2Chi.rx.dat.flit.tgtID        := rxdat.tgtID
    l2Chi.rx.dat.flit.srcID        := rxdat.srcID
    l2Chi.rx.dat.flit.txnID        := rxdat.txnID
    l2Chi.rx.dat.flit.homeNID      := rxdat.homeNID
    l2Chi.rx.dat.flit.opcode       := rxdat.opcode
    l2Chi.rx.dat.flit.respErr      := rxdat.respErr
    l2Chi.rx.dat.flit.resp         := rxdat.resp
    l2Chi.rx.dat.flit.fwdState     := rxdat.fwdState
    l2Chi.rx.dat.flit.dbID         := rxdat.dbID
    l2Chi.rx.dat.flit.ccID         := rxdat.ccID
    l2Chi.rx.dat.flit.dataID       := rxdat.dataID
    l2Chi.rx.dat.flit.traceTag     := rxdat.traceTag
    l2Chi.rx.dat.flit.rsvdc        := rxdat.rsvdc
    l2Chi.rx.dat.flit.be           := rxdat.be
    l2Chi.rx.dat.flit.data         := rxdat.data

    //rxsnp ctrl
    l2Chi.rx.snp.flitpend          := dsu.io.rnChi(0).rxsnp.flitpend
    l2Chi.rx.snp.flitv             := dsu.io.rnChi(0).rxsnp.flitv
    dsu.io.rnChi(0).rxsnp.lcrdv    := l2Chi.rx.snp.lcrdv
    //rxsnpflit
    val rxsnp = Wire(new CHIBundleSNP(dsu.chiBundleParams))
    rxsnp                          := dsu.io.rnChi(0).rxsnp.flit
    l2Chi.rx.snp.flit.qos          := rxsnp.qos
    l2Chi.rx.snp.flit.srcID        := rxsnp.srcID
    l2Chi.rx.snp.flit.txnID        := rxsnp.txnID
    l2Chi.rx.snp.flit.fwdNID       := rxsnp.fwdNID
    l2Chi.rx.snp.flit.fwdTxnID     := rxsnp.fwdTxnID
    l2Chi.rx.snp.flit.opcode       := rxsnp.opcode
    l2Chi.rx.snp.flit.addr         := rxsnp.addr
    l2Chi.rx.snp.flit.ns           := rxsnp.ns
    l2Chi.rx.snp.flit.doNotGoToSD  := rxsnp.doNotGoToSD
    l2Chi.rx.snp.flit.retToSrc     := rxsnp.retToSrc
    l2Chi.rx.snp.flit.traceTag     := rxsnp.traceTag
  }

}


object TestTopCHIHelper {
  def gen(fTop: Parameters => TestTop_CHIL2)(args: Array[String]) = {
    val FPGAPlatform    = false
    val enableChiselDB  = false
    
    val config = new Config((_, _, _) => {
      case L2ParamKey => L2Param(
        ways                = 4,
        sets                = 128,
        clientCaches        = Seq(L1Param(aliasBitsOpt = Some(2))),
        // echoField        = Seq(DirtyField),
        enablePerf          = false,
        enableRollingDB     = enableChiselDB && true,
        enableMonitor       = enableChiselDB && true,
        enableTLLog         = enableChiselDB && true,
        elaboratedTopDown   = false,
        FPGAPlatform        = FPGAPlatform,

        // SAM for tester ICN: Home Node ID = 33
        sam                 = Seq(AddressSet.everything -> 33)
      )
      case DebugOptionsKey => DebugOptions()
    })

    ChiselDB.init(enableChiselDB)

    val top = DisableMonitors(p => LazyModule(fTop(p)))(config)

    (new ChiselStage).execute(
      Array("--target", "verilog") ++ args,
      Seq(
        FirtoolOption("-O=release"),
        FirtoolOption("--disable-all-randomization"),
        FirtoolOption("--disable-annotation-unknown"),
        FirtoolOption("--strip-debug-info"),
        FirtoolOption("--lower-memories"),
        FirtoolOption(
          "--lowering-options=noAlwaysComb," +
            " disallowPortDeclSharing, disallowLocalVariables," +
            " emittedLineLength=120, explicitBitcast, locationInfoStyle=plain," +
            " disallowExpressionInliningInPorts, disallowMuxInlining"
        ),
        ChiselGeneratorAnnotation(() => top.module)
      )
    )

//    ChiselDB.addToFileRegisters
//    FileRegisters.write("./build")
  }
}

object TestTop_CHI_OneCore_1UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 1,
    numULAgents = 1,
    banks = 1)(p)
  )(args)
}

object TestTop_CHI_DualCore_0UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 2,
    numULAgents = 0,
    banks = 1)(p)
  )(args)
}

object TestTop_CHI_DualCore_2UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 2,
    numULAgents = 0,
    banks = 1)(p)
  )(args)
}



object TestTop_CHI_QuadCore_0UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 4,
    numULAgents = 0,
    banks = 1)(p)
  )(args)
}

object TestTop_CHI_QuadCore_2UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 4,
    numULAgents = 2,
    banks = 1)(p)
  )(args)
}


object TestTop_CHI_OctaCore_0UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 8,
    numULAgents = 0,
    banks = 1)(p)
  )(args)
}

object TestTop_CHI_OctaCore_2UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 8,
    numULAgents = 2,
    banks = 1)(p)
  )(args)
}


object TestTop_CHI_HexaCore_0UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 16,
    numULAgents = 0,
    banks = 1)(p)
  )(args)
}

object TestTop_CHI_HexaCore_2UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 16,
    numULAgents = 2,
    banks = 1)(p)
  )(args)
}
