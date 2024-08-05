package Utils

import coupledL2._
import DONGJIANG._
import DONGJIANG.CHI._
import chisel3._
import chisel3.util._
import coupledL2.tl2chi.PortIO
import org.chipsalliance.cde.config._


class ConnectChil2()(implicit p: Parameters) extends DJModule {
    val io = IO(new Bundle {
        val l2Chi           = Flipped(new PortIO())
        val djChiLinkCtrl  = new CHILinkCtrlIO()
        val djChi          = new CHIBundleDownstream(chiParams)
    })

    // linkCtrl
    io.djChiLinkCtrl.txsactive                 := io.l2Chi.txsactive
    io.l2Chi.rxsactive                         := io.djChiLinkCtrl.rxsactive
    // tx linkCtrl
    io.djChiLinkCtrl.txactivereq               := io.l2Chi.tx.linkactivereq
    io.l2Chi.tx.linkactiveack                  := io.djChiLinkCtrl.txactiveack
    // rx linkCtrl
    io.l2Chi.rx.linkactivereq                  := io.djChiLinkCtrl.rxactivereq
    io.djChiLinkCtrl.rxactiveack               := io.l2Chi.rx.linkactiveack

    // txreq ctrl
    io.djChi.txreq.flitpend                    := io.l2Chi.tx.req.flitpend
    io.djChi.txreq.flitv                       := io.l2Chi.tx.req.flitv
    io.l2Chi.tx.req.lcrdv                      := io.djChi.txreq.lcrdv
    // txreqflit
    val txreq                               = Wire(new CHIBundleREQ(chiParams))
    io.djChi.txreq.flit                    := txreq
    txreq.qos                               := io.l2Chi.tx.req.flit.qos
    txreq.tgtID                             := io.l2Chi.tx.req.flit.tgtID
    txreq.srcID                             := io.l2Chi.tx.req.flit.srcID
    txreq.txnID                             := io.l2Chi.tx.req.flit.txnID
    txreq.returnNID                         := io.l2Chi.tx.req.flit.returnNID
    txreq.returnTxnID                       := DontCare
    txreq.opcode                            := io.l2Chi.tx.req.flit.opcode
    txreq.size                              := io.l2Chi.tx.req.flit.size
    txreq.addr                              := io.l2Chi.tx.req.flit.addr
    txreq.ns                                := io.l2Chi.tx.req.flit.ns
    txreq.lpID                              := DontCare
    txreq.excl                              := DontCare
    txreq.likelyShared                      := io.l2Chi.tx.req.flit.likelyshared
    txreq.allowRetry                        := io.l2Chi.tx.req.flit.allowRetry
    txreq.order                             := io.l2Chi.tx.req.flit.order
    txreq.pCrdType                          := io.l2Chi.tx.req.flit.pCrdType
    txreq.memAttr                           := io.l2Chi.tx.req.flit.memAttr
    txreq.snpAttr                           := io.l2Chi.tx.req.flit.snpAttr.asUInt
    txreq.traceTag                          := DontCare
    txreq.rsvdc                             := DontCare
    // txreq.cah := io.l2Chi.tx.req.flit
    // txreq.excl         := io.l2Chi.tx.req.flit
    // txreq.snoopMe      := io.l2Chi.tx.req.flit
    txreq.expCompAck                        := io.l2Chi.tx.req.flit.expCompAck

    //txdat ctrl
    io.djChi.txdat.flitpend          := io.l2Chi.tx.dat.flitpend
    io.djChi.txdat.flitv             := io.l2Chi.tx.dat.flitv
    io.l2Chi.tx.dat.lcrdv             := io.djChi.txdat.lcrdv
    //txdatflit
    val txdat = Wire(new CHIBundleDAT(chiParams))
    io.djChi.txdat.flit           := txdat
    txdat.qos                      := io.l2Chi.tx.dat.flit.qos
    txdat.tgtID                    := io.l2Chi.tx.dat.flit.tgtID
    txdat.srcID                    := io.l2Chi.tx.dat.flit.srcID
    txdat.txnID                    := io.l2Chi.tx.dat.flit.txnID
    txdat.homeNID                  := io.l2Chi.tx.dat.flit.homeNID
    txdat.opcode                   := io.l2Chi.tx.dat.flit.opcode
    txdat.respErr                  := io.l2Chi.tx.dat.flit.respErr
    txdat.resp                     := io.l2Chi.tx.dat.flit.resp
    txdat.fwdState                 := io.l2Chi.tx.dat.flit.fwdState
    txdat.dbID                     := io.l2Chi.tx.dat.flit.dbID
    txdat.ccID                     := io.l2Chi.tx.dat.flit.ccID
    txdat.dataID                   := io.l2Chi.tx.dat.flit.dataID
    txdat.traceTag                 := io.l2Chi.tx.dat.flit.traceTag
    txdat.rsvdc                    := io.l2Chi.tx.dat.flit.rsvdc
    txdat.be                       := io.l2Chi.tx.dat.flit.be
    txdat.data                     := io.l2Chi.tx.dat.flit.data

    //txrsp ctrl
    io.djChi.txrsp.flitpend           := io.l2Chi.tx.rsp.flitpend
    io.djChi.txrsp.flitv              := io.l2Chi.tx.rsp.flitv
    io.l2Chi.tx.rsp.lcrdv             := io.djChi.txrsp.lcrdv
    //txrspflit
    val txrsp = Wire(new CHIBundleRSP(chiParams))
    io.djChi.txrsp.flit           := txrsp
    txrsp.qos                      := io.l2Chi.tx.rsp.flit.qos
    txrsp.tgtID                    := io.l2Chi.tx.rsp.flit.tgtID
    txrsp.srcID                    := io.l2Chi.tx.rsp.flit.srcID
    txrsp.txnID                    := io.l2Chi.tx.rsp.flit.txnID
    txrsp.opcode                   := io.l2Chi.tx.rsp.flit.opcode
    txrsp.respErr                  := io.l2Chi.tx.rsp.flit.respErr
    txrsp.resp                     := io.l2Chi.tx.rsp.flit.resp
    txrsp.fwdState                 := io.l2Chi.tx.rsp.flit.fwdState
    txrsp.dbID                     := io.l2Chi.tx.rsp.flit.dbID
    txrsp.pCrdType                 := io.l2Chi.tx.rsp.flit.pCrdType
    txrsp.traceTag                 := io.l2Chi.tx.rsp.flit.traceTag

    //rxrsp ctrl
    io.l2Chi.rx.rsp.flitpend          := io.djChi.rxrsp.flitpend
    io.l2Chi.rx.rsp.flitv             := io.djChi.rxrsp.flitv
    io.djChi.rxrsp.lcrdv              := io.l2Chi.rx.rsp.lcrdv
    //rxrspflit
    val rxrsp = Wire(new CHIBundleRSP(chiParams))
    rxrsp                             := io.djChi.rxrsp.flit
    io.l2Chi.rx.rsp.flit.qos          := rxrsp.qos
    io.l2Chi.rx.rsp.flit.tgtID        := rxrsp.tgtID
    io.l2Chi.rx.rsp.flit.srcID        := rxrsp.srcID
    io.l2Chi.rx.rsp.flit.txnID        := rxrsp.txnID
    io.l2Chi.rx.rsp.flit.opcode       := rxrsp.opcode
    io.l2Chi.rx.rsp.flit.respErr      := rxrsp.respErr
    io.l2Chi.rx.rsp.flit.resp         := rxrsp.resp
    io.l2Chi.rx.rsp.flit.fwdState     := rxrsp.fwdState
    io.l2Chi.rx.rsp.flit.dbID         := rxrsp.dbID
    io.l2Chi.rx.rsp.flit.pCrdType     := rxrsp.pCrdType
    io.l2Chi.rx.rsp.flit.traceTag     := rxrsp.traceTag

    //rxdat ctrl
    io.l2Chi.rx.dat.flitpend          := io.djChi.rxdat.flitpend
    io.l2Chi.rx.dat.flitv             := io.djChi.rxdat.flitv
    io.djChi.rxdat.lcrdv              := io.l2Chi.rx.dat.lcrdv
    //rxdatflit
    val rxdat = Wire(new CHIBundleDAT(chiParams))
    rxdat                             := io.djChi.rxdat.flit
    io.l2Chi.rx.dat.flit.qos          := rxdat.qos
    io.l2Chi.rx.dat.flit.tgtID        := rxdat.tgtID
    io.l2Chi.rx.dat.flit.srcID        := rxdat.srcID
    io.l2Chi.rx.dat.flit.txnID        := rxdat.txnID
    io.l2Chi.rx.dat.flit.homeNID      := rxdat.homeNID
    io.l2Chi.rx.dat.flit.opcode       := rxdat.opcode
    io.l2Chi.rx.dat.flit.respErr      := rxdat.respErr
    io.l2Chi.rx.dat.flit.resp         := rxdat.resp
    io.l2Chi.rx.dat.flit.fwdState     := rxdat.fwdState
    io.l2Chi.rx.dat.flit.dbID         := rxdat.dbID
    io.l2Chi.rx.dat.flit.ccID         := rxdat.ccID
    io.l2Chi.rx.dat.flit.dataID       := rxdat.dataID
    io.l2Chi.rx.dat.flit.traceTag     := rxdat.traceTag
    io.l2Chi.rx.dat.flit.rsvdc        := rxdat.rsvdc
    io.l2Chi.rx.dat.flit.be           := rxdat.be
    io.l2Chi.rx.dat.flit.data         := rxdat.data

    //rxsnp ctrl
    io.l2Chi.rx.snp.flitpend          := io.djChi.rxsnp.flitpend
    io.l2Chi.rx.snp.flitv             := io.djChi.rxsnp.flitv
    io.djChi.rxsnp.lcrdv              := io.l2Chi.rx.snp.lcrdv
    //rxsnpflit
    val rxsnp = Wire(new CHIBundleSNP(chiParams))
    rxsnp                             := io.djChi.rxsnp.flit
    io.l2Chi.rx.snp.flit.qos          := rxsnp.qos
    io.l2Chi.rx.snp.flit.srcID        := rxsnp.srcID
    io.l2Chi.rx.snp.flit.txnID        := rxsnp.txnID
    io.l2Chi.rx.snp.flit.fwdNID       := rxsnp.fwdNID
    io.l2Chi.rx.snp.flit.fwdTxnID     := rxsnp.fwdTxnID
    io.l2Chi.rx.snp.flit.opcode       := rxsnp.opcode
    io.l2Chi.rx.snp.flit.addr         := rxsnp.addr
    io.l2Chi.rx.snp.flit.ns           := rxsnp.ns
    io.l2Chi.rx.snp.flit.doNotGoToSD  := rxsnp.doNotGoToSD
    io.l2Chi.rx.snp.flit.retToSrc     := rxsnp.retToSrc
    io.l2Chi.rx.snp.flit.traceTag     := rxsnp.traceTag



}