package NHDSU

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import chi._
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import Utils.GenerateVerilog

abstract class DSUModule(implicit val p: Parameters) extends Module with HasDSUParam
abstract class DSUBundle(implicit val p: Parameters) extends Bundle with HasDSUParam

class NHDSU()(implicit p: Parameters) extends DSUModule {
    //
    // IO declaration
    //
    val io_in = IO(new Bundle {
        val chi = CHIBundleUpstream(chiBundleParams)
        val chiLinkCtrl = Flipped(new CHILinkCtrlIO())
    })
    val io_out = IO(new Bundle {
        val chi = CHIBundleDownstream(chiBundleParams)
        val chiLinkCtrl = new CHILinkCtrlIO()
    })

    io_in.chi := DontCare
    io_in.chiLinkCtrl := DontCare
    io_out.chi := DontCare
    io_out.chiLinkCtrl := DontCare
    dontTouch(io_in)
    dontTouch(io_out)

    //
    // TODO: Other modules
    //
    
}


object DSU extends App {
    val config = new Config((_, _, _) => {
        case DSUParamKey     => DSUParam()
        case DebugOptionsKey => DebugOptions()
    })

    GenerateVerilog(args, () => new NHDSU()(config), name = "DSU", split = false)
}