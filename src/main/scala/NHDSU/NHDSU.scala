package NHDSU

import NHDSU.CHI._
import NHDSU.CPUSALVE._
import NHDSU.SLICE._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog

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
//    ------------        -----------------------------------------------------------
//    | CPUSLAVE | <--->  |      |   Dir   |      |  SnpCtl  |                      |
//    ------------        |      -----------      ------------      ----------      |        ----------
//                        | ---> | Arbiter | ---> | MainPipe | ---> | TxReqQ | ---> |  <---> | Master |
//    ------------        |      -----------      ------------      ----------      |        ----------
//    | CPUSLAVE | <--->  |      |   DB    | <--> |    DS    |                      |
//    ------------        -----------------------------------------------------------
//                                                   Slice


    // ------------------------------------------ Modules declaration And Connection ----------------------------------------------//
    if(dsuparam.nrBank == 1){
        // Modules declaration
        val cpuSalves = Seq.fill(dsuparam.nrCore) { Module(new CpuSlave()) }
        val slice = Module(new Slice())


        // IO Connection
        cpuSalves.zipWithIndex.foreach {
            case(cpu, i) =>
                // chi
                cpu.io.chi <> io.rnChi(i)
                cpu.io.chiLinkCtrl <> io.rnChiLinkCtrl(i)
                // dataBuffer <-> cpuslave
                cpu.io.dbCtrl(0) <> slice.io.dbCrtl(i)
                // mainpipe <-> cpuslave
                cpu.io.mpTask(0) <> slice.io.cpuTask(i)
                cpu.io.mpResp(0) <> slice.io.cpuResp(i)
                // snpCtrl <-> cpuslave
                cpu.io.snpTask(0) <> slice.io.snpTask(i)
                cpu.io.snpResp(0) <> slice.io.snpResp(i)
        }

        slice.io.dbCrtl(dsuparam.nrCore) <> DontCare
        slice.io.msTask <> DontCare
        slice.io.msResp <> DontCare



    } else {
        //
        // TODO: multi-bank
        //
        assert(false.B, "Now dont support multi-bank")
    }
    
}


object DSU extends App {
    val config = new Config((_, _, _) => {
        case DSUParamKey     => DSUParam()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new NHDSU()(config), name = "DSU", split = false)
}