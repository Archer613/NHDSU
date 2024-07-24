package SubordinateNode

import chisel3._
import chisel3.util._
import NHSN.ValueDefine
import NHSN._

// Module definition with parameterized widths and depth
class TxChan(val FLIT_WIDTH: Int = ValueDefine.FLIT_REQ_WIDTH) extends Module {
              
  val cntWidth               = ValueDefine.FIFO_DEPTH

 // -------------------------- IO declaration -----------------------------//

  val io                     = IO(new Bundle {
    val reset                = Input(Bool())

    /* 
    CHI signals
     */ 

    val txflitpend_o         = Output(Bool())
    val txflitv_o            = Output(Bool())
    val txflit_o             = Output(UInt(FLIT_WIDTH.W))
    val txlcrdv_i            = Input(Bool())


    /* 
    Link active state
     */

    val txlinkactive_st_i    = Input(UInt(2.W))

    /* 
    Internal channel interface
     */

    val ch_full_o            = Output(Bool())              // FIFO is full
    val ch_empty_o           = Output(Bool())              // FIFO is empty
    val ch_push_i            = Input(Bool())               // Push a flit
    val ch_flit_i            = Input(UInt(FLIT_WIDTH.W))    // Pushed flit
  })

 // ----------------------- Reg/Wire declaration --------------------------//


  /* 
   Signal declarations
   */
  val flitFifoFull           = Wire(Bool())
  val flitFifoEmpty          = Wire(Bool())
  val pop                    = Wire(Bool())
  val txflit                 = Wire(UInt(FLIT_WIDTH.W))
  val txflitv                = Wire(Bool())
  val txflitpend             = Wire(Bool())

  /* 
   Credit management
   */
  val creditCnt              = RegInit(0.U(cntWidth.W))
  val nxtCreditCnt           = Wire(UInt(cntWidth.W))
  val creditCntWe            = Wire(Bool())
  val creditRtn              = Wire(Bool())
  val creditRtnReg           = RegInit(false.B)

  /* 
  Flit FIFO
   */
  val fifo                   = Module(new Queue(UInt(FLIT_WIDTH.W), ValueDefine.FIFO_DEPTH))
  fifo.io.enq.valid         := io.ch_push_i
  fifo.io.enq.bits          := io.ch_flit_i
  fifo.io.deq.ready         := pop

  flitFifoEmpty             := !fifo.io.deq.valid
  flitFifoFull              := !fifo.io.enq.ready

  // Flit popped when the FIFO isn't empty and the channel is in credit, provided the link is active.
  pop := (creditCnt =/= 0.U) && !flitFifoEmpty && (io.txlinkactive_st_i === LinkStates.RUN)

  // The output flit comes from the FIFO in the normal case but is zeroed out
  // when the link is not active. This creates the L-credit return flit.
  txflit := Mux(io.txlinkactive_st_i === LinkStates.RUN, fifo.io.deq.bits, 0.U)
  txflitv := pop || creditRtnReg
  txflitpend := io.ch_push_i || !flitFifoEmpty || (io.txlinkactive_st_i === LinkStates.DEACTIVATE)

  /* 
  Credit management
   */

  when(io.reset) {
    creditCnt := 0.U
  } .elsewhen(creditCntWe) {
    creditCnt := nxtCreditCnt
  }
  nxtCreditCnt := Mux(io.txlcrdv_i, creditCnt + 1.U, creditCnt - 1.U)
  creditCntWe := (io.txlcrdv_i ^ pop) || creditRtn
  creditRtn := (io.txlinkactive_st_i === LinkStates.DEACTIVATE) && (creditCnt =/= 0.U)

  // Clean registered credit return signal for factoring in FLITV. The FLITV term must be one cycle later than the FLITPEND term.
  when(io.reset) {
    creditRtnReg := false.B
  } .otherwise {
    creditRtnReg := creditRtn
  }

  // Output assignments
  io.ch_full_o := flitFifoFull
  io.ch_empty_o := flitFifoEmpty
  io.txflitpend_o := txflitpend
  io.txflitv_o := txflitv
  io.txflit_o := txflit
}

