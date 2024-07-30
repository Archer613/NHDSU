package NHDSU

import NHDSU.CHI._
import Utils._
import chisel3._
import circt.stage.{ChiselStage, FirtoolOption}
import chisel3.util.{circt, _}
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tile.MaxHartIdBits
import NHL2._
import SimpleL2.Configs.L2ParamKey
import SimpleL2.SimpleL2Cache
import SimpleL2.Configs.L2Param
import xs.utils.perf.{DebugOptions, DebugOptionsKey}

class TestTop_NHL2(numCores: Int = 1, numULAgents: Int = 0, banks: Int = 1)(implicit p: Parameters) extends LazyModule {
  /*   L1D(L1I)* L1D(L1I)* ... L1D(L1I)*
   *       \         |          /
   *       L2        L2   ...  L2
   *         \       |        /
   *          \      |       /
   *                DSU
   */

  override lazy val desiredName: String = "TestTop"
  val l2NodeID = 0
  val delayFactor = 0.5
  val cacheParams = p(L2ParamKey)

  def createDCacheNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(
      Seq(
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
          requestFields = Nil,
          responseKeys = Nil
        )
      )
    )
    masterNode
  }

  def createICacheNode(name: String, source: Int) = {
    val masterNode = TLClientNode(
      Seq(
        TLMasterPortParameters.v1(
          clients = Seq(
            TLMasterParameters.v1(
              name = name,
              sourceId = IdRange(0, source)
            )
          )
        )
      )
    )
    masterNode
  }

  val BlockSize = 64 // in byte

  val l1d_nodes = (0 until numCores).map(i => createDCacheNode(s"l1d$i", 32))
  val l1i_nodes = (0 until numCores).map {i =>
    (0 until numULAgents).map { j => createICacheNode(s"l1I_${i}_${j}", 32) }
  }

  // val l2 = LazyModule(new TL2CHICoupledL2())
  val l2_nodes = (0 until numCores).map(i => LazyModule(new SimpleL2Cache()(new Config((_, _, _) => {
    case L2ParamKey =>
      cacheParams.copy(
        name = s"l2_$i",
        useDiplomacy = true,
        nrSlice = banks,
        blockBytes = BlockSize
      )
  }))))
  val bankBinders = (0 until numCores).map(_ => BankBinder(banks, 64))



  l1d_nodes.zip(l2_nodes).zipWithIndex.foreach { case ((l1d, l2), i) =>
    val l1xbar = TLXbar()
    l1xbar := 
      TLBuffer() :=
      l1d

    l1i_nodes(i).zipWithIndex.foreach { case (l1i, j) =>
      l1xbar :=
        TLBuffer() :=
        l1i
    }

    l2.sinkNodes.foreach { node =>
      node := bankBinders(i)
    }
    bankBinders(i) :*= l1xbar
  }

  lazy val module = new LazyModuleImp(this){

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


    l2_nodes.foreach(_.module.io.nodeID := l2NodeID.U)

// ----------------------------- Connect IO_SN <-> ARM_SN -------------------------- //
    val dsu = Module(new NHDSU())
    val io = IO(new Bundle {
      val snChi = Vec(dsu.dsuparam.nrBank, CHIBundleDownstream(dsu.chiBundleParams))
      val snChiLinkCtrl = Vec(dsu.dsuparam.nrBank, new CHILinkCtrlIO())
    })

    dsu.io.snChi <> io.snChi
    dsu.io.snChiLinkCtrl <> io.snChiLinkCtrl

    dsu.io.rnChi.zipWithIndex.foreach { case(chi, i) => chi <> l2_nodes(i).module.io.chi }
    dsu.io.rnChiLinkCtrl.zipWithIndex.foreach { case(ctrl, i) => ctrl <> l2_nodes(i).module.io.chiLinkCtrl }
  }

}


object TestTopNHHelper {
  def gen(nrCore: Int = 1, fTop: Parameters => TestTop_NHL2)(args: Array[String]) = {
    val FPGAPlatform    = false
    val enableChiselDB  = false
    
    val config = new Config((_, _, _) => {
      case L2ParamKey => L2Param(
        ways = 4,
        sets = 128
      )
      case DebugOptionsKey => DebugOptions()
      case DSUParamKey => DSUParam(
        nrCore = nrCore
      )
    })

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
  }
}

object TestTop_NHL2_OneCore_1UL extends App {

  TestTopNHHelper.gen(nrCore = 1, p => new TestTop_NHL2(
    numCores = 1,
    numULAgents = 1,
    banks = 1)(p)
  )(args)
}


object TestTop_NHL2_DualCore_1UL extends App {

  TestTopNHHelper.gen(nrCore = 2, p => new TestTop_NHL2(
    numCores = 2,
    numULAgents = 1,
    banks = 1)(p)
  )(args)
}
