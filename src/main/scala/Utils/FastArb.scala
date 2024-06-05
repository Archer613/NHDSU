package Utils

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xs.utils.FastArbiter
import chi.CHIBundleParameters

object FastArb {
    def fastArbDec[T <: Bundle](in: Seq[DecoupledIO[T]], out: DecoupledIO[T], name: Option[String] = None): Unit = {
        val arb = Module(new FastArbiter[T](chiselTypeOf(out.bits), in.size))
        if (name.nonEmpty) {
            arb.suggestName(s"${name.get}_arb")
        }
        for ((a, req) <- arb.io.in.zip(in)) {
            a <> req
        }
        out <> arb.io.out
    }

    def fastArbVal[T <: Bundle](in: Seq[DecoupledIO[T]], out: ValidIO[T], name: Option[String] = None): Unit = {
        val arb = Module(new FastArbiter[T](chiselTypeOf(out.bits), in.size))
        if (name.nonEmpty) {
            arb.suggestName(s"${name.get}_arb")
        }
        for ((a, req) <- arb.io.in.zip(in)) {
            a <> req
        }
        arb.io.out.ready := true.B
        out.bits := arb.io.out.bits
        out.valid := arb.io.out.valid
    }
}
