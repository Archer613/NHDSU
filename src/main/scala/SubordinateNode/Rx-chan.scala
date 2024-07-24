package SubordinateNode

import chisel3._
import chisel3.util._
import NHSN.ValueDefine
import NHSN._


class RxChan(val FLIT_WIDTH: Int = ValueDefine.FLIT_REQ_WIDTH,
             val FIFO_DEPTH: Int = ValueDefine.FIFO_DEPTH) extends Module {

  val CNT_WIDTH           = log2Ceil(FIFO_DEPTH + 1)

   // -------------------------- IO declaration -----------------------------//

  val io = IO(new Bundle {
    val reset             = Input(Bool())
    val rxflitv_i         = Input(Bool())
    val rxflit_i          = Input(UInt(FLIT_WIDTH.W))
    val rxlcrdv_o         = Output(Bool())
    val rxlinkactive_st_i = Input(UInt(2.W))

    /* 
    Internal channel interface
     */ 

    val ch_active_o       = Output(Bool())
    val ch_flitv_o        = Output(Bool())
    val ch_pop_i          = Input(Bool())
    val ch_flit_o         = Output(UInt(FLIT_WIDTH.W))
    val ch_crd_rtn_i      = Input(Bool())
  })

  // ----------------------- Reg/Wire declaration --------------------------//

  val flit_fifo           = Module(new Queue(UInt(FLIT_WIDTH.W), FIFO_DEPTH))
  val credit_cnt          = RegInit(0.U(CNT_WIDTH.W))
  val nxt_credit_cnt      = Wire(UInt(CNT_WIDTH.W))
  val credit_cnt_we       = Wire(Bool())
  val rxlcrdv             = RegInit(false.B)
  val nxt_rxlcrdv         = Wire(Bool())

  // ------------------------------ Logic ---------------------------------//

  flit_fifo.io.enq.valid := io.rxflitv_i
  flit_fifo.io.enq.bits  := io.rxflit_i
  flit_fifo.io.deq.ready := io.ch_pop_i

  /* 
  Credit management
  */

  when(io.reset) {
    credit_cnt           := 0.U
  } .elsewhen(credit_cnt_we) {
    credit_cnt           := nxt_credit_cnt
  }

  nxt_credit_cnt         := Mux(nxt_rxlcrdv, credit_cnt + 1.U, credit_cnt - 1.U)
  credit_cnt_we          := nxt_rxlcrdv ^ io.ch_pop_i

  when(io.reset) {
    rxlcrdv              := false.B
  } .otherwise {
    rxlcrdv              := nxt_rxlcrdv
  }

  nxt_rxlcrdv            := (io.rxlinkactive_st_i === LinkStates.RUN) && (credit_cnt < FIFO_DEPTH.U)

  /* 
  Output logic
   */

  io.rxlcrdv_o           := rxlcrdv
  io.ch_active_o         := (credit_cnt =/= 0.U)
  io.ch_flitv_o          := flit_fifo.io.deq.fire
  io.ch_flit_o           := flit_fifo.io.deq.bits
}

