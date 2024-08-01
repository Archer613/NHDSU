package NHDSU

import NHDSU.CHI._
import NHDSU.CHI.CHIOp.REQ._
import NHDSU.CHI.ChiState._
import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._

/*
 * Decode Process Operation: Snoop, ReadDown, ReadDB, ReadDS, WriteDS, WSDir, WCDir, Atomic(deal in DS)
 * Auto Process Operation: SnpHlp, Replace, ReqRetry
 *
 * {Read / Dataless / Atomic / CMO} Request Processing Flow 0 (longest path):
 *                                                                  GetChiTxReq -> RecordInReqBuf -> IssueReq -> [Retry] -> [IssueReq] -> AllocMSHR -> ReadDir -> WaitDirResp -> Decode -> MpUpdateMSHR -> Process([Snoop] / [ReadDown] / [ReqRetry])
 *                                                                  |------------------------- CpuSlave -----------------------------|    |-- S0 --|  |-- S1 --|  |--- S2 ---|  |-------------------------------- S3 -------------------------------|
 *                                                                              -> [SnpCtlUpateMSHR] / [ReadCtlUpdateMSHR] -> [IssueResp] -> [Retry] -> [IssueResp] -> [ReadDir] -> [WaitDirResp] -> [Decode] -> Process([SnpHlp] / [Replace] / [ReqRetry])
 *                                                                                 |----------------------------------- MSHRCtl ----------------------------------|    |-- S1 --|   |---------- S2 ---------|   |-------------------- S3 ----------------|
 *                                                                              -> Commit([ReadDB] / [WSDir] / [WCDir] / [ReadDS] / [WriteDS] / [Atomic]) -> MpUpdateMSHR
 *                                                                                 |----------------------------------------- S4 ---------------------------------------|
 *                                                                              -> UpdateReqBuf -> SendChiRxRsp/Dat -> [GetAck] -> CpuUpdateMSHR -> MSHRRelease
 *                                                                                 |---------------------------- CpuSlave ---------------------|   |- MSHRCtl -|
 *
 *
 * {Read / Dataless / Atomic / CMO} Request Processing Flow 1 (shortest path):
 *                                                                  GetChiTxReq -> RecordInReqBuf -> IssueReq -> AllocMSHR -> ReadDir -> WaitDirResp -> Decode -> MpUpdateMSHR -> Commit([WSDir] / [WCDir] / [ReadDS] / [Atomic]) -> UpdateReqBuf -> SendChiRxRsp/Dat -> CpuUpdateMSHR -> MSHRRelease
 *                                                                  |------------- CpuSlave -----------------|   |-- S0 --|  |-- S1 --|  |--- S2 ---|   |------- S3 ---------|    |-------------------- S4 ---------------------|    |------------------------ CpuSlave --------------|   |- MSHRCtl -|
 *
 *
 * {Write} Request Processing Flow 0 (longest path):
 *                                                                  GetChiTxReq -> RecordInReqBuf -> GetDBID -> SendDBIDResp -> GetChiTxDat-> WBDataToDB-> IssueReq -> [Retry] -> [IssueReq] -> AllocMSHR -> ReadDir -> WaitDirResp -> Decode -> MpUpdateMSHR -> Process([Replace])
 *                                                                  |------------------------------------------------------------------ CpuSlave ------------------------------------------|    |-- S0 --|  |-- S1 --|  |--- S2 ---|  |------------------- S3 --------------------|
 *                                                                              -> Commit([WSDir] / [WCDir] / [ReadDS] / [WriteDS]) -> MpUpdateMSHR -> MSHRRelease
 *                                                                                 |------------------------------ S4 -----------------------------|  |- MSHRCtl -|
 *
 *
 * {SnpHlp} Request Processing Flow 0 (longest path):
 *                                                                  AllocMSHR -> [SnpCtlUpateMSHR] -> MpUpdateMSHR
 *                                                                  |-- S0 --|   |------------ MSHRCtl ----------|
 *
 * {Replace} Request Processing Flow 0 (longest path):
 *                                                                  AllocMSHR -> [ReadCtlUpdateMSHR] -> MpUpdateMSHR
 *                                                                  |-- S0 --|   |------------- MSHRCtl -----------|
 *
 *
 * decoder table: [opcode, srcState, srcHit, othState, othHit, hnState, hnHit] -> [Process Operations] + [srcNS, othNS, hnNS]
 *
 */

class OperationsBundle extends Bundle {
    val Snoop       = Bool()
    val ReadDown    = Bool()
    val ReadDB      = Bool()
    val ReadDS      = Bool()
    val WriteDS     = Bool()
    val WSDir       = Bool()
    val WCDir       = Bool()
    val Atomic      = Bool()
}

/*
 * When it need Snoop, it need to decode twice, and the result is based on the second decode
 */
object decode{
    def decode(opcode: UInt, srcState:UInt, othState:UInt, hnState:UInt, snpResp:UInt, isSnpResp:Bool): (Bundle, Bundle, Bundle, Bundle, Bool) = {
        val operations   = WireInit(0.U.asTypeOf(new OperationsBundle()))
        val srcNS        = WireInit(0.U.asTypeOf(new CHIStateBundle())) // source RN State
        val othNS        = WireInit(0.U.asTypeOf(new CHIStateBundle())) // other RN State
        val hnNS         = WireInit(0.U.asTypeOf(new CHIStateBundle()))
        val error        = WireInit(true.B)

        val mes = Cat(opcode, srcState, othState, hnState, snpResp, isSnpResp) // snpResp should be ChiResp.I when isSnpResp is false.B

        // State: I SC UC SD UD
        switch(mes) {
            //     [opcode]             [src state] [oth state] [hn state]  [snp resp]    [isSnpResp]                      Snoop | ReadDown | ReadDB | ReadDS | WriteDS | WSDir | WCDir | Atomic
            // directly commit
            is(Cat(ReadNotSharedDirty,  I,           I,          I,         ChiResp.I    , 0.U))        { operations := "b 0       1          1        0        0         0       1       0 ".U;   srcNS := UC;    othNS :=  I;    hnNS :=  I;     error := false.B }
            // need snoop
            is(Cat(ReadNotSharedDirty,  I,          SC,          I,         ChiResp.I    , 0.U))        { operations := "b 1       0          0        0        0         0       0       0 ".U;   srcNS :=  I;    othNS :=  I;    hnNS :=  I;     error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          SC,          I,         ChiResp.I    , 1.U))        { operations := "b 0       0          1        0        0         0       1       0 ".U;   srcNS := UC;    othNS :=  I;    hnNS :=  I;     error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          SC,          I,         ChiResp.SC   , 1.U))        { operations := "b 0       0          1        0        1         1       1       0 ".U;   srcNS := SC;    othNS := SC;    hnNS := SC;     error := false.B }
            // need snoop
            is(Cat(ReadNotSharedDirty,  I,          UC,          I,         ChiResp.I    , 0.U))        { operations := "b 1       0          0        0        0         0       0       0 ".U;   srcNS :=  I;    othNS :=  I;    hnNS :=  I;     error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UC,          I,         ChiResp.I    , 1.U))        { operations := "b 0       0          1        0        0         0       1       0 ".U;   srcNS := UC;    othNS :=  I;    hnNS :=  I;     error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UC,          I,         ChiResp.SC   , 1.U))        { operations := "b 0       0          1        0        1         1       1       0 ".U;   srcNS := SC;    othNS := SC;    hnNS := SC;     error := false.B }
            // need snoop
            is(Cat(ReadNotSharedDirty,  I,          UD,          I,         ChiResp.I    , 0.U))        { operations := "b 1       0          0        0        0         0       0       0 ".U;   srcNS :=  I;    othNS :=  I;    hnNS :=  I;     error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UD,          I,         ChiResp.I    , 1.U))        { operations := "b 0       0          1        0        0         0       1       0 ".U;   srcNS := UD;    othNS :=  I;    hnNS :=  I;     error := false.B }
            // directly commit
            is(Cat(ReadNotSharedDirty,  I,          SC,         SC,         ChiResp.I    , 0.U))        { operations := "b 0       0          0        1        0         0       1       0 ".U;   srcNS := SC;    othNS := SC;    hnNS := SC;     error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          SC,         SD,         ChiResp.I    , 0.U))        { operations := "b 0       0          0        1        0         0       1       0 ".U;   srcNS := SC;    othNS := SC;    hnNS := SD;     error := false.B }
            is(Cat(ReadNotSharedDirty,  I,           I,         UC,         ChiResp.I    , 0.U))        { operations := "b 0       0          0        1        0         1       1       0 ".U;   srcNS := UC;    othNS :=  I;    hnNS :=  I;     error := false.B }
            is(Cat(ReadNotSharedDirty,  I,           I,         UD,         ChiResp.I    , 0.U))        { operations := "b 0       0          0        1        0         1       1       0 ".U;   srcNS := UD;    othNS :=  I;    hnNS :=  I;     error := false.B }
        }


        (operations, srcNS, othNS, hnNS, error)
    }
}