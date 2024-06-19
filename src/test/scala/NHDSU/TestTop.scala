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

    val io = IO(Vec(numCores, new Bundle() {
      val chi = new PortIO
    }))

    l2_nodes.zipWithIndex.foreach { case (l2, i) =>
      l2.module.io_chi <> io(i).chi
      dontTouch(l2.module.io)

      l2.module.io.hartId := i.U
      l2.module.io_nodeID := i.U(NODEID_WIDTH.W)
      l2.module.io.debugTopDown := DontCare
      l2.module.io.l2_tlb_req <> DontCare
    }

    val dsu = Module(new NHDSU())
    val io_dsu = IO(new Bundle {
      val rnChi = Vec(dsu.dsuparam.nrCore, CHIBundleUpstream(dsu.chiBundleParams))
      val rnChiLinkCtrl = Vec(dsu.dsuparam.nrCore, Flipped(new CHILinkCtrlIO()))
      val snChi = Vec(dsu.dsuparam.nrBank, CHIBundleDownstream(dsu.chiBundleParams))
      val snChiLinkCtrl = Vec(dsu.dsuparam.nrBank, new CHILinkCtrlIO())
    })

    dsu.io.rnChi <> io_dsu.rnChi
    dsu.io.rnChiLinkCtrl <> io_dsu.rnChiLinkCtrl
    dsu.io.snChi <> io_dsu.snChi
    dsu.io.snChiLinkCtrl <> io_dsu.snChiLinkCtrl
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

object TestTop_CHI_OneCore_0UL extends App {

  TestTopCHIHelper.gen(p => new TestTop_CHIL2(
    numCores = 1,
    numULAgents = 0,
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
