package NHSN

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import NHDSU._
import NHDSU.CHI._
import NHDSU.CHI.CHIOp._
import freechips.rocketchip.regmapper.RegFieldAddressBlock



class Interface(implicit p : Parameters) extends DSUModule {

 // ---------------------------- IO declaration -------------------------------//

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

 // ----------------------------- Define declaration -------------------------------//

  val CHI_RESPERR_OKAY           = "b00".U(2.W)
  val CHI_RESPERR_NONDATA_ERR    = "b11".U(2.W)

// ---------------------------- Reg/Wire declaration -------------------------------//
 
  val rxreq_pop                  = WireInit(false.B)
  val rxreqFlit                  = io.rxreq_ch_flit_i


  val rxdat_pop                  = WireInit(false.B)
  val rxdatFlit                  = io.rxdat_ch_flit_i

  

  val read_valid                 = WireInit(false.B)
  val read_addr                  = WireInit(0.U(addressBits.W))
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
  val txrspFlit                  = WireInit(0.U.asTypeOf(io.txrsp_ch_flit_o))

  val txdat_push                 = WireInit(false.B)
  val txdatFlit                  = WireInit(0.U.asTypeOf(io.txdat_ch_flit_o))

  val write_addr                 = WireInit(0.U(addressBits.W))
  val write_data                 = WireInit(0.U(dataBits.W))

  val complete                   = WireInit(false.B)
  val useReceipt                 = WireInit(false.B)
  val useCompDBID                = WireInit(false.B)


// ----------------------------------- Rxreq -----------------------------------//


  rxreq_pop                     := io.rxreq_ch_active_i  & !read_ongoing & !wait_compack  & !write_ongoing
  unsupported_req               := rxreq_pop & rxreqFlit.opcode =/= REQ.ReadNoSnp & rxreqFlit.opcode =/= REQ.WriteNoSnpFull
  
/* 
 * Read request
 */

  next_read_ongoing             := read_valid || (read_ongoing & !(io.read_ready_i & io.read_valid_o))
  read_ongoing                  := next_read_ongoing
  read_valid                    := rxreq_pop  & rxreqFlit.opcode === REQ.ReadNoSnp
  read_addr                     := rxreqFlit.addr
  useReceipt                    := read_valid & (rxreqFlit.order =/= "b01".U(2.W) || rxreqFlit.order =/= "b00".U(2.W))

/* 
 * Write request
 */

  next_write_ongoing            := rxreq_pop & rxreqFlit.opcode === REQ.WriteNoSnpFull || (write_ongoing & !(io.write_ready_i & write_valid))
  write_ongoing                 := next_write_ongoing
  

  write_addr                    := Mux(rxreq_pop, rxreqFlit.addr, 0.U(addressBits.W))
  write_data                    := Mux(rxdat_pop, rxdatFlit.data, 0.U(dataBits.W))
  write_valid                   := rxdat_pop & write_ongoing & rxdatFlit.opcode === DAT.NonCopyBackWrData

  useCompDBID                   := rxreq_pop & rxreqFlit.opcode === REQ.WriteNoSnpFull

  
  //------------------------------------- RxDat -------------------------------//
  rxdat_pop                     := io.rxdat_ch_active_i  & io.write_ready_i 
  next_rxdat_valid              := rxdat_pop || (rxdat_valid & write_ongoing & !io.write_ready_i) 
  rxdat_valid                   := next_rxdat_valid
  


  //------------------------------------ RxRsp --------------------------------//

  rxrsp_pop                     := io.rxrsp_ch_active_i 

  next_wait_compack             := (rxreq_pop & rxreqFlit.expCompAck || wait_compack) & !(rxrsp_pop & rxrspFlit.opcode === RSP.CompAck)
  wait_compack                  := next_wait_compack



  //--------------------------------- TxRsp -----------------------------------//

  txrsp_push                    := useCompDBID || useReceipt
  txrspFlit                     := 0.U
  txrspFlit.opcode              := Mux(useReceipt, RSP.ReadReceipt, Mux(useCompDBID, RSP.CompDBIDResp, 0.U))
  txrspFlit.respErr             := Mux(unsupported_req, "b11".U(2.W), "b00".U(2.W))
  txrspFlit.srcID               := rxreqFlit.tgtID
  txrspFlit.txnID               := rxreqFlit.txnID
  rxrspFlit.tgtID               := rxreqFlit.srcID

  //--------------------------------- TxDat -----------------------------------//

  txdat_push                    := RegNext(read_valid)
  txdatFlit                     := 0.U
  txdatFlit.tgtID               := Mux(rxreqFlit.returnNID   =/= 0.U, RegNext(rxreqFlit.returnNID), RegNext(rxreqFlit.srcID))
  txdatFlit.srcID               := RegNext(rxreqFlit.tgtID)
  txdatFlit.txnID               := Mux(rxreqFlit.returnTxnID === 0.U, RegNext(rxreqFlit.txnID), RegNext(rxreqFlit.returnTxnID))
  txdatFlit.opcode              := DAT.CompData
  txdatFlit.be                  := Fill(dataBits / 8, 1.U(1.W))
  txdatFlit.data                := io.read_data_i
  txdatFlit.dbID                := Mux(rxreqFlit.returnNID   =/= 0.U, RegNext(rxreqFlit.txnID), 0.U)
  

  //---------------------- Protocol layer complete ---------------------------//

  complete                      := rxrsp_pop   & rxrspFlit.opcode === RSP.CompAck & wait_compack |
                                   rxdat_valid & rxdatFlit.opcode === DAT.NonCopyBackWrData      |
                                   txrsp_push  & txrspFlit.opcode === RSP.ReadReceipt  


  //------------------------------ Return credit -----------------------------//

  rxreq_crd_rtn                 := rxreq_pop & rxreqFlit.opcode  === REQ.ReqLCrdReturn  & rxreqFlit.txnID === 0.U
  rxdat_crd_rtn                 := rxdat_pop & rxdatFlit.opcode  === DAT.DataLCrdReturn & rxdatFlit.txnID === 0.U
  rxrsp_crd_rtn                 := rxrsp_pop & rxrspFlit.opcode  === RSP.RespLCrdReturn & rxrspFlit.txnID === 0.U

  //------------------------------ Output logic ----------------------------//

  io.rxreq_ch_pop_o             := rxreq_pop
  io.rxreq_ch_crd_rtn_o         := rxreq_crd_rtn
  
  io.rxdat_ch_pop_o             := rxdat_pop
  io.rxdat_ch_crd_rtn_o         := rxdat_crd_rtn

  io.rxrsp_ch_pop_o             := rxrsp_pop
  io.rxrsp_ch_crd_rtn_o         := rxrsp_crd_rtn
  
  io.txrsp_ch_push_o            := txrsp_push
  io.txrsp_ch_flit_o            := txrspFlit

  io.txdat_ch_push_o            := txdat_push 
  io.txdat_ch_flit_o            := txdatFlit

  io.pcomplete_o                := complete

  io.read_valid_o               := read_valid
  io.read_addr_o                := read_addr

  io.write_valid_o              := write_valid
  io.write_data_o               := write_data
  io.write_addr_o               := write_addr
}