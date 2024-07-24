package SubordinateNode

import chisel3._
import chisel3.util._
import NHDSU._
import NHDSU.CHI._
import org.chipsalliance.cde.config._
import NHSN._



class Interface[T <: Data](gen: T , aggregateIO: Bool = false.B) extends Module {

 // -------------------------- IO declaration -----------------------------//

val io = IO(new Bundle {

    val reset                    = Input(Bool())
    /* 
    RXREQ
     */
    val rxreq_ch_active_i        = Input(Bool())
    val rxreq_ch_flitv_i         = Input(Bool())
    val rxreq_ch_flit_i          = Input(UInt(ValueDefine.FLIT_REQ_WIDTH.W))
    val rxreq_ch_pop_o           = Output(Bool())
    val rxreq_ch_crd_rtn_o       = Output(Bool())

    /* 
    RXDAT
     */
    val rxdat_ch_active_i        = Input(Bool())
    val rxdat_ch_flitv_i         = Input(Bool())
    val rxdat_ch_flit_i          = Input(UInt(ValueDefine.FLIT_DAT_WIDTH.W))
    val rxdat_ch_pop_o           = Output(Bool())
    val rxdat_ch_crd_rtn_o       = Output(Bool())

    /* 
    RXRSP
     */
    val rxrsp_ch_active_i        = Input(Bool())
    val rxrsp_ch_flitv_i         = Input(Bool())
    val rxrsp_ch_flit_i          = Input(UInt(ValueDefine.FLIT_RSP_WIDTH.W))
    val rxrsp_ch_pop_o           = Output(Bool())
    val rxrsp_ch_crd_rtn_o       = Output(Bool())

    /* 
    TXRSP
     */
    val txrsp_ch_full_i          = Input(Bool())
    val txrsp_ch_push_o          = Output(Bool())
    val txrsp_ch_flit_o          = Output(UInt(ValueDefine.FLIT_RSP_WIDTH.W))

    /* 
    TXDAT
     */
    val txdat_ch_full_i          = Input(Bool())
    val txdat_ch_push_o          = Output(Bool())
    val txdat_ch_flit_o          = Output(UInt(ValueDefine.FLIT_DAT_WIDTH.W))

    /* 
    Protocol layer complete
     */
    val pcomplete_o              = Output(Bool())

    /* 
    Subsystem
     */
    val val_read_o               = Output(Bool())
    val val_rd_addr_o            = Output(UInt(ValueDefine.ADDRESS_WIDTH.W))
    val val_rd_data_i            = Input(UInt(ValueDefine.DATA_WIDTH.W))
    val val_write_o              = Output(Bool())
    val val_wr_addr_o            = Output(UInt(ValueDefine.ADDRESS_WIDTH.W))
    val val_wr_strb_o            = Output(UInt(ValueDefine.STRB_WIDTH.W))
    val val_wr_data_o            = Output(UInt(ValueDefine.DATA_WIDTH.W))
})

 // -------------------------- Define declaration -----------------------------//

  val CHI_RESPERR_OKAY           = "b00".U(2.W)
  val CHI_RESPERR_NONDATA_ERR    = "b11".U(2.W)
 
  val rxreq_pop                  = WireInit(false.B)
  val rxreq_tgtid                = RegInit(0.U(ValueDefine.TGTID_WIDTH.W))
  val rxreq_srcid                = RegInit(0.U(ValueDefine.SRCID_WIDTH.W))
  val rxreq_txnid                = RegInit(0.U(ValueDefine.TXNID_WIDTH.W))
  val rxreq_opcode               = RegInit(0.U(ValueDefine.REQ_OPCODE_WIDTH.W))
  val rxreq_size                 = RegInit(0.U(ValueDefine.SIZE_WIDTH.W))
  val rxreq_addr                 = RegInit(0.U(ValueDefine.ADDRESS_WIDTH.W))
  val rxreq_expcompack           = RegInit(false.B)
  val rxreq_valid                = RegInit(false.B)


  val rxdat_pop                  = WireInit(false.B)
  val rxdat_qos                  = RegInit(0.U(ValueDefine.QOS_WIDTH.W))
  val rxdat_tgtid                = RegInit(0.U(ValueDefine.TGTID_WIDTH.W))
  val rxdat_srcid                = RegInit(0.U(ValueDefine.SRCID_WIDTH.W))
  val rxdat_txnid                = RegInit(0.U(ValueDefine.TXNID_WIDTH.W))
  val rxdat_homenid              = RegInit(0.U(ValueDefine.HOMENID_WIDTH.W))
  val rxdat_opcode               = RegInit(0.U(ValueDefine.DATA_OPCODE_WIDTH.W))
  val rxdat_resperr              = RegInit(0.U(ValueDefine.RESPERR_WIDTH.W))
  val rxdat_resp                 = RegInit(0.U(ValueDefine.RESP_WIDTH.W))
  val rxdat_fwdstate             = RegInit(0.U(ValueDefine.FWDSTATE_WIDTH.W))
  val rxdat_dbid                 = RegInit(0.U(ValueDefine.DBID_WIDTH.W))
  val rxdat_ccid                 = RegInit(0.U(ValueDefine.CCID_WIDTH.W))
  val rxdat_dataid               = RegInit(0.U(ValueDefine.DATAID_WIDTH.W))
  val rxdat_tracetag             = RegInit(false.B)
  val rxdat_be                   = RegInit(0.U(ValueDefine.BE_WIDTH.W))
  val rxdat_data                 = RegInit(0.U(ValueDefine.DATA_WIDTH.W))
  val rxdat_valid                = RegInit(false.B)

  val read_valid                 = WireInit(false.B)
  val write_valid                = WireInit(false.B)
  val unsupported_req            = WireInit(false.B)
  val rxreq_beats                = RegInit(0.U(ValueDefine.BEATS_WIDTH.W))


  val rxrsp_pop                  = WireInit(false.B)
  val rxrsp_dbid                 = RegInit(0.U(ValueDefine.DBID_WIDTH.W))
  val rxrsp_resp                 = RegInit(0.U(ValueDefine.RESP_WIDTH.W))
  val rxrsp_resperr              = RegInit(0.U(ValueDefine.RESPERR_WIDTH.W))
  val rxrsp_opcode               = RegInit(0.U(ValueDefine.RSP_OPCODE_WIDTH.W))
  val rxrsp_txnid                = RegInit(0.U(ValueDefine.TXNID_WIDTH.W))
  val rxrsp_srcid                = RegInit(0.U(ValueDefine.SRCID_WIDTH.W))
  val rxrsp_tgtid                = RegInit(0.U(ValueDefine.TGTID_WIDTH.W))
  val rxrsp_valid                = RegInit(false.B)
  val wait_compack               = RegInit(false.B)
  val next_wait_compack          = WireInit(false.B)
  val use_exp_compack            = WireInit(false.B)


  val txrsp_push                 = WireInit(false.B)
  val txrsp_resperr              = WireInit(0.U(ValueDefine.RESPERR_WIDTH.W))
  val txrsp_opcode               = WireInit(0.U(ValueDefine.RSP_OPCODE_WIDTH.W))
  val txrsp_txnid                = WireInit(0.U(ValueDefine.TXNID_WIDTH.W))
  val txrsp_srcid                = WireInit(0.U(ValueDefine.SRCID_WIDTH.W))
  val txrsp_tgtid                = WireInit(0.U(ValueDefine.TGTID_WIDTH.W))

  val txrsp_flit                 = WireInit(0.U(ValueDefine.FLIT_RSP_WIDTH.W))

  val rxreq_crd_rtn              = WireInit(false.B)
  val rxdat_crd_rtn              = WireInit(false.B)
  val rxrsp_crd_rtn              = WireInit(false.B)

  val read_ongoing               = RegInit(false.B)
  val next_read_ongoing          = WireInit(false.B)
  val write_ongoing              = RegInit(false.B)
  val next_write_ongoing         = WireInit(false.B)

  rxreq_pop := io.rxreq_ch_active_i & io.rxreq_ch_flitv_i & !read_ongoing & !wait_compack  & !write_ongoing

  when(rxreq_pop & !aggregateIO){

  }


}