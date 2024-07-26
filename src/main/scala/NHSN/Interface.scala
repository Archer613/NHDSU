package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU._
import NHDSU.CHI._
import NHDSU.CHI.CHIOp._



class Interface(implicit p : Parameters) extends DSUModule {

 // -------------------------- IO declaration -----------------------------//

val io = IO(new Bundle {

    /* 
    RXREQ
     */
    val rxreq_ch_active_i        = Input(Bool())
    val rxreq_ch_flitv_i         = Input(Bool())
    val rxreq_ch_flit_i          = Input(new CHIBundleREQ(chiBundleParams))
    val rxreq_ch_pop_o           = Output(Bool())
    val rxreq_ch_crd_rtn_o       = Output(Bool())

    /* 
    RXDAT
     */
    val rxdat_ch_active_i        = Input(Bool())
    val rxdat_ch_flitv_i         = Input(Bool())
    val rxdat_ch_flit_i          = Input(new CHIBundleDAT(chiBundleParams))
    val rxdat_ch_pop_o           = Output(Bool())
    val rxdat_ch_crd_rtn_o       = Output(Bool())

    /* 
    RXRSP
     */
    val rxrsp_ch_active_i        = Input(Bool())
    val rxrsp_ch_flitv_i         = Input(Bool())
    val rxrsp_ch_flit_i          = Input(new CHIBundleRSP(chiBundleParams))
    val rxrsp_ch_pop_o           = Output(Bool())
    val rxrsp_ch_crd_rtn_o       = Output(Bool())

    /* 
    TXRSP
     */
    val txrsp_ch_full_i          = Input(Bool())
    val txrsp_ch_push_o          = Output(Bool())
    val txrsp_ch_flit_o          = Output(new CHIBundleRSP(chiBundleParams))

    /* 
    TXDAT
     */
    val txdat_ch_full_i          = Input(Bool())
    val txdat_ch_push_o          = Output(Bool())
    val txdat_ch_flit_o          = Output(new CHIBundleDAT(chiBundleParams))

    /* 
    Protocol layer complete
     */
    val pcomplete_o              = Output(Bool())

    /* 
    Subsystem
     */
    val read_valid_o             = Output(Bool())
    val read_addr_o              = Output(UInt(addressBits.W))
    val read_data_i              = Input(UInt(dataBits.W))
    val write_valid_o            = Output(Bool())
    val write_addr_o             = Output(UInt(addressBits.W))
    val write_data_o             = Output(UInt(addressBits.W))
    val read_ready_i             = Input(Bool())
    val write_ready_i            = Input(Bool())
})

 // -------------------------- Define declaration -----------------------------//

  val CHI_RESPERR_OKAY           = "b00".U(2.W)
  val CHI_RESPERR_NONDATA_ERR    = "b11".U(2.W)

// -------------------------- Reg/Wire declaration -----------------------------//
 
  val rxreq_pop                  = WireInit(false.B)
  val rxreqFlit                  = io.rxreq_ch_flit_i


  val rxdat_pop                  = WireInit(false.B)
  val rxdatFlit                  = io.rxdat_ch_flit_i

  

  val read_valid                 = WireInit(false.B)
  val write_valid                = WireInit(false.B)
  val unsupported_req            = WireInit(false.B)
  val rxreq_beats                = RegInit(0.U(ValueDefine.BEATS_WIDTH.W))


  val rxrsp_pop                  = WireInit(false.B)
  val rxrspFlit                  = io.rxrsp_ch_flit_i

  val wait_compack               = RegInit(false.B)
  val next_wait_compack          = WireInit(false.B)
  val use_exp_compack            = WireInit(false.B)

  val rxreq_crd_rtn              = WireInit(false.B)
  val rxdat_crd_rtn              = WireInit(false.B)
  val rxrsp_crd_rtn              = WireInit(false.B)

  val read_ongoing               = RegInit(false.B)
  val next_read_ongoing          = WireInit(false.B)
  val write_ongoing              = RegInit(false.B)
  val next_write_ongoing         = WireInit(false.B)
  
  val rxdat_valid                = RegInit(false.B)
  val next_rxdat_valid           = WireInit(false.B)

  val txrsp_push                 = WireInit(false.B)
  val txrspFlit                  = io.txrsp_ch_flit_o

  val txdat_push                 = WireInit(false.B)



  rxreq_pop                     := io.rxreq_ch_active_i  & !read_ongoing & !wait_compack  & !write_ongoing

  unsupported_req               := rxreq_pop & rxreqFlit.opcode =/= REQ.ReadNoSnp & rxreqFlit.opcode =/= REQ.WriteNoSnpFull
  
  
  next_read_ongoing             := rxreq_pop & rxreqFlit.opcode === REQ.ReadNoSnp || (read_ongoing & !(io.read_ready_i & io.read_valid_o))
  read_ongoing                  := next_read_ongoing
  read_valid                    := rxreq_pop & rxreqFlit.opcode === REQ.ReadNoSnp

  next_write_ongoing            := rxreq_pop & rxreqFlit.opcode === REQ.WriteNoSnpFull || (write_ongoing & !(io.write_ready_i & io.write_valid_o))
  write_ongoing                 := next_write_ongoing
  write_valid                   := rxreq_pop & rxreqFlit.opcode === REQ.WriteNoSnpFull

  next_wait_compack             := (rxreq_pop & rxreqFlit.expCompAck || wait_compack) & !(rxrsp_pop & rxrspFlit.opcode === RSP.CompAck)
  wait_compack                  := next_wait_compack

  
  
  rxdat_pop                     := io.rxdat_ch_active_i  & io.write_ready_i 
  next_rxdat_valid              := rxdat_pop || (rxdat_valid & write_ongoing & !io.write_ready_i) 
  rxdat_valid                   := next_rxdat_valid


  //------------------------------- RxRsp ---------------------------------//

  rxrsp_pop                     := io.rxrsp_ch_active_i 



  //------------------------------- TxRsp ---------------------------------//

  txrsp_push                    := write_valid
  txrspFlit                     := 0.U
  txrspFlit.opcode              := RSP.CompDBIDResp
  txrspFlit.respErr             := Mux(unsupported_req, CHI_RESPERR_NONDATA_ERR, CHI_RESPERR_OKAY)
  txrspFlit.srcID               := rxreqFlit.tgtID
  txrspFlit.txnID               := rxreqFlit.txnID

  //------------------------------- TxDat ---------------------------------//

  txdat_push                    := read_valid
  
  
  

  
}