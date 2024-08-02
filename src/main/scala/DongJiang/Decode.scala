package DONGJIANG

import DONGJIANG.CHI.CHIStateBundle
import DONGJIANG.CHI.ChiResp
import DONGJIANG.TaskType._
import DONGJIANG.RespType._
import DONGJIANG.CHI.CHIOp.REQ._
import DONGJIANG.CHI.ChiState._
import chisel3._
import chisel3.util._

/*
 * Decode Process Operation: Snoop, ReadDown, ReadDB, ReadDS, WriteDS, WSDir, WCDir, Atomic(deal in DS), WriteBack
 * Auto Process Operation: SnpHlp, Replace, ReqRetry
 *
 * {Read / Dataless / Atomic / CMO} Request Processing Flow 0 (longest path):
 *                                                                  GetChiTxReq -> RecordInReqBuf -> IssueReq -> [Retry] -> [IssueReq] -> AllocMSHR -> ReadDir -> WaitDirResp -> Decode0 -> MpUpdateMSHR -> Process([Snoop] / [ReadDown] / [ReqRetry])
 *                                                                  |-------------------------- RnSlave -----------------------------|    |-- S0 --|  |-- S1 --|  |--- S2 ---|  |------------------------------- S3 ---------------------------------|
 *                                                                              -> [SnpCtlUpateMSHR] / [ReadCtlUpdateMSHR] -> [IssueResp] -> [Retry] -> [IssueResp] -> [ReadDir] -> [WaitDirResp] -> [Decode1] -> Process([SnpHlp] / [Replace] / [ReqRetry])
 *                                                                                 |----------------------------------- MSHRCtl ----------------------------------|    |-- S1 --|   |---------- S2 ----------|   |-------------------- S3 ----------------|
 *                                                                              -> Commit([ReadDB] / [WSDir] / [WCDir] / [ReadDS] / [WriteDS] / [Atomic]) -> MpUpdateMSHR
 *                                                                                 |----------------------------------------- S4 ---------------------------------------|
 *                                                                              -> UpdateReqBuf -> SendChiRxRsp/Dat -> [GetAck] -> RnUpdateMSHR -> MSHRRelease
 *                                                                                 |---------------------------- RnSlave ---------------------|   |- MSHRCtl -|
 *
 *
 * {Read / Dataless / Atomic / CMO} Request Processing Flow 1 (shortest path):
 *                                                                  GetChiTxReq -> RecordInReqBuf -> IssueReq -> AllocMSHR -> ReadDir -> WaitDirResp -> Decode0 -> MpUpdateMSHR -> Commit([WSDir] / [WCDir] / [ReadDS] / [Atomic]) -> UpdateReqBuf -> SendChiRxRsp/Dat -> RnUpdateMSHR -> MSHRRelease
 *                                                                  |-------------- RnSlave -----------------|   |-- S0 --|  |-- S1 --|  |--- S2 ---|   |-------- S3 ---------|    |-------------------- S4 ---------------------|    |------------------------- RnSlave -------------|   |- MSHRCtl -|
 *
 *
 * {Write} Request Processing Flow 0 (longest path):
 *                                                                  GetChiTxReq -> RecordInReqBuf -> GetDBID -> SendDBIDResp -> GetChiTxDat-> WBDataToDB-> IssueReq -> [Retry] -> [IssueReq] -> AllocMSHR -> ReadDir -> WaitDirResp -> Decode0 -> MpUpdateMSHR -> Process([Replace])
 *                                                                  |------------------------------------------------------------------- RnSlave ------------------------------------------|    |-- S0 --|  |-- S1 --|  |--- S2 ---|  |-------------------- S3 --------------------|
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



/*
 * When it need Snoop or ReadDown, it need to decode twice, and the result is based on the second decode(decode1)
 */
object decode {
    def decode0(opcode: UInt, srcState: UInt, othState: UInt, hnState: UInt): (Bundle, Bundle, Bundle, Bundle, Bool) = {
        val operations   = WireInit(0.U.asTypeOf(new OperationsBundle()))
        val srcNS        = WireInit(0.U.asTypeOf(new CHIStateBundle())) // source RN State
        val othNS        = WireInit(0.U.asTypeOf(new CHIStateBundle())) // other RN State
        val hnNS         = WireInit(0.U.asTypeOf(new CHIStateBundle()))
        val error        = WireInit(true.B)

        val mes          = Cat(opcode, srcState, othState, hnState)

        // State: I SC UC SD UD
        switch(mes) {
            // --------------------------------------------------------------------------------------------------------- ReadNotSharedDirty ------------------------------------------------------------------------------------------------------------------------ //
            //     [opcode]            [src state] [oth state] [hn state]               Commit  |   Snoop   |   ReadDown    |   ReadDB  |   ReadDS  |   WriteDS |   WSDir   |   WCDir   |   WriteBack
            // read down
            is(Cat(ReadNotSharedDirty,  I,           I,          I))    { operations :=                         ReadDown;                                                                                                                       error := false.B }
            // snoop
            is(Cat(ReadNotSharedDirty,  I,          SC,          I))    { operations :=             Snoop;                                                                                                                                      error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UC,          I))    { operations :=             Snoop;                                                                                                                                      error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UD,          I))    { operations :=             Snoop;                                                                                                                                      error := false.B }
            // directly commit
            is(Cat(ReadNotSharedDirty,  I,           I,         SC))    { operations := Commit  |                                           ReadDS  |                           WCDir;                  srcNS := UC; othNS := I;  hnNS := I;    error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          SC,         SC))    { operations := Commit  |                                           ReadDS  |                           WCDir;                  srcNS := SC; othNS := SC; hnNS := SC;   error := false.B }
            is(Cat(ReadNotSharedDirty,  I,           I,         SD))    { operations := Commit  |                                           ReadDS  |                           WCDir;                  srcNS := UD; othNS := I;  hnNS := I;    error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          SC,         SD))    { operations := Commit  |                                           ReadDS  |                           WCDir;                  srcNS := SC; othNS := SC; hnNS := SD;   error := false.B }
            is(Cat(ReadNotSharedDirty,  I,           I,         UC))    { operations := Commit  |                                           ReadDS  |               WSDir   |   WCDir;                  srcNS := UC; othNS := I;  hnNS := I;    error := false.B }
            is(Cat(ReadNotSharedDirty,  I,           I,         UD))    { operations := Commit  |                                           ReadDS  |               WSDir   |   WCDir;                  srcNS := UD; othNS := I;  hnNS := I;    error := false.B }
        }


        (operations, srcNS, othNS, hnNS, error)
    }

    def decode1(opcode: UInt, srcState: UInt, othState: UInt, hnState: UInt, respType: UInt, resp: UInt): (Bundle, Bundle, Bundle, Bundle, Bool) = {
        val operations   = WireInit(0.U.asTypeOf(new OperationsBundle()))
        val srcNS        = WireInit(0.U.asTypeOf(new CHIStateBundle())) // source RN State
        val othNS        = WireInit(0.U.asTypeOf(new CHIStateBundle())) // other RN State
        val hnNS         = WireInit(0.U.asTypeOf(new CHIStateBundle()))
        val error        = WireInit(true.B)

        val mes          = Cat(opcode, srcState, othState, hnState, respType, resp)

        switch(mes) {
            // --------------------------------------------------------------------------------------------------------- ReadNotSharedDirty ------------------------------------------------------------------------------------------------------------------------ //
            //     [opcode]            [src state] [oth state] [hn state] [type]        [resp]                          Commit  |   ReadDB  |   ReadDS  |   WriteDS |   WSDir   |   WCDir   |   WriteBack
            is(Cat(ReadNotSharedDirty,  I,           I,          I,       TpyeReadDown, ChiResp.UC   )) { operations := Commit  |   ReadDB  |                                       WCDir;                  srcNS := UC; othNS := I;  hnNS := I;    error := false.B }
            // snoop
            is(Cat(ReadNotSharedDirty,  I,          SC,          I,       TpyeSnoop,    ChiResp.I    )) { operations := Commit  |   ReadDB  |                                       WCDir;                  srcNS := UC; othNS := I;  hnNS := I;    error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          SC,          I,       TpyeSnoop,    ChiResp.SC   )) { operations := Commit  |   ReadDB  |               WriteDS |   WSDir   |   WCDir;                  srcNS := SC; othNS := SC; hnNS := SC;   error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UC,          I,       TpyeSnoop,    ChiResp.I    )) { operations := Commit  |   ReadDB  |                                       WCDir;                  srcNS := UC; othNS := I;  hnNS := I;    error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UC,          I,       TpyeSnoop,    ChiResp.SC   )) { operations := Commit  |   ReadDB  |               WriteDS |   WSDir   |   WCDir;                  srcNS := SC; othNS := SC; hnNS := SC;   error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UD,          I,       TpyeSnoop,    ChiResp.I_PD )) { operations := Commit  |   ReadDB  |                                       WCDir;                  srcNS := UD; othNS := I;  hnNS := I;    error := false.B }
            is(Cat(ReadNotSharedDirty,  I,          UD,          I,       TpyeSnoop,    ChiResp.SC_PD)) { operations := Commit  |   ReadDB  |               WriteDS |   WSDir   |   WCDir;                  srcNS := SC; othNS := SC; hnNS := SD;   error := false.B }
        }

        (operations, srcNS, othNS, hnNS, error)
    }
}