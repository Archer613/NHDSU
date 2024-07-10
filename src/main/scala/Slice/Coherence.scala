package NHDSU.SLICE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

/*
 * Read transactions；
 *
 * --- [ReadNotSharedDirty]:
 * HN(I)
 * *** RN_0(I)  ---[ReadNotSharedDirty]--->   HN(I -> I)   (RN_1-I)  (ReadDown) ---[CompData(UC)]--->     RN_0(UC)
 * *** RN_0(I)  ---[ReadNotSharedDirty]--->   HN(I -> X)   (RN_1-UC) (Snoop)
 * *** RN_0(I)  ---[ReadNotSharedDirty]--->   HN(I -> X)   (RN_1-UD) (Snoop)
 * HN(SC)
 * *** RN_0(I)  ---[ReadNotSharedDirty]--->   HN(SC -> SC) (RN_1-I/SC)          ---[CompData(SC)]--->     RN_0(SC)
 * HN(SD)
 * *** RN_0(I)  ---[ReadNotSharedDirty]--->   HN(SD -> SD) (RN_1-I/SC)          ---[CompData(SC)]--->     RN_0(SC)
 * HN(UC)
 * *** RN_0(I)  ---[ReadNotSharedDirty]--->   HN(UC -> I)  (RN_1-I)             ---[CompData(UC)]--->     RN_0(UC)
 * HN(DD)
 * *** RN_0(I)  ---[ReadNotSharedDirty]--->   HN(UD -> SD) (RN_1-I)             ---[CompData(SC)]--->     RN_0(SC)
 *
 *
 * --- [ReadUnique]: We dont care others permitted state
 * HN(I)
 * *** RN_0(I)  ---[ReadUnique]---> HN(I -> I)  (RN_1-I)  (ReadDown)  ---[CompData(UC)]--->    RN_0(UC)
 * *** RN_0(I)  ---[ReadUnique]---> HN(I -> I)  (RN_1-UC) (Snoop)
 * *** RN_0(I)  ---[ReadUnique]---> HN(I -> I)  (RN_1-UD) (Snoop)
 * HN(SC)
 * *** RN_0(I)  ---[ReadUnique]---> HN(SC -> I) (RN_1-SC) (Snoop)
 * *** RN_0(SC) ---[ReadUnique]---> HN(SC -> I) (RN_1-I)              ---[CompData(UC)]--->    RN_0(UC)
 * *** RN_0(SC) ---[ReadUnique]---> HN(SC -> I) (RN_1-SC) (Snoop)
 * HN(SD)
 * *** RN_0(I)  ---[ReadUnique]---> HN(SD -> I) (RN_1-SC) (Snoop)
 * *** RN_0(SC) ---[ReadUnique]---> HN(SD -> I) (RN_1-I)              ---[CompData(UD_PD)]---> RN_0(UC)
 * *** RN_0(SC) ---[ReadUnique]---> HN(SD -> I) (RN_1-SC) (Snoop)
 * HN(UC)
 * *** RN_0(I)  ---[ReadUnique]---> HN(UC -> I) (RN_1-I)              ---[CompData(UC)]--->    RN_0(UC)
 * HN(UD)
 * *** RN_0(I)  ---[ReadUnique]---> HN(UD -> I) (RN_1-I)              ---[CompData(UD_PD)]---> RN_0(UD)
 *
 *
 */


/*
 * Dataless transactions:
 *
 * [MakeUnique]: We dont care others permitted state
 * HN(I)
 * *** RN_0(I)  ---[MakeUnique]---> HN(I -> I)  (RN_1-I)            ---[Comp(UC)]---> RN_0(UD)
 * *** RN_0(I)  ---[MakeUnique]---> HN(I -> I)  (RN_1-UC)   (Snoop)
 * *** RN_0(I)  ---[MakeUnique]---> HN(I -> I)  (RN_1-UD)   (Snoop)
 * HN(SC)
 * *** RN_0(I)  ---[MakeUnique]---> HN(SC -> I)  (RN_1-SC)  (Snoop)
 * *** RN_0(SC) ---[MakeUnique]---> HN(SC -> I)  (RN_1-I)           ---[Comp(UC)]---> RN_0(UD)
 * *** RN_0(SC) ---[MakeUnique]---> HN(SC -> I)  (RN_1-SC)  (Snoop)
 * HN(SD)
 * *** RN_0(I)  ---[MakeUnique]---> HN(SD -> I)  (RN_1-SC)  (Snoop)
 * *** RN_0(SC) ---[MakeUnique]---> HN(SD -> I)  (RN_1-I)           ---[Comp(UC)]---> RN_0(UD)
 * *** RN_0(SC) ---[MakeUnique]---> HN(SD -> I)  (RN_1-SC)  (Snoop)
 * HN(UC)
 * *** RN_0(I)  ---[MakeUnique]---> HN(UC -> I) (RN_1-I)            ---[Comp(UC)]---> RN_0(UD)
 * HN(UD)
 * *** RN_0(I)  ---[MakeUnique]---> HN(UD -> I) (RN_1-I)            ---[Comp(UC)]---> RN_0(UD)
 *
 *
 *
 * Before a CleanInvalid, MakeInvalid or Evict transaction it is permitted for the cache state to be UC, UCE or SC.
 * However, it is required that the cache state transitions to the I state before the transaction is issued. Therefore
 * Table4-13 shows I state as the only initial state.
 * [Evict]:
 * RN(I)
 * *** RN_0(I)  ---[Evict]---->  HN(DontCare this transcation)  ---[Comp(I)]---> RN_0(I)
 * RN(SC)
 * *** RN_0(SC) ---[Evict]---->  HN(SC -> UC) (RN_1-I)          ---[Comp(I)]---> RN_0(I)
 * *** RN_0(SC) ---[Evict]---->  HN(SC -> SC) (RN_1-SC)         ---[Comp(I)]---> RN_0(I)
 * *** RN_0(SC) ---[Evict]---->  HN(SD -> UD) (RN_1-I)          ---[Comp(I)]---> RN_0(I)
 * *** RN_0(SC) ---[Evict]---->  HN(SD -> SD) (RN_1-SC)         ---[Comp(I)]---> RN_0(I)
 * RN(UC)
 * *** RN_0(UC) ---[Evict]---->  HN(I -> I)   (RN_1-I)          ---[Comp(I)]---> RN_0(I)
 *
 */

/*
 * Write transactions:
 * *** DSU dont support write transactions
 *
 * ---[None]
 *
 */


/*
 * CopyBack transactions:
 * ***  A snoop might be received while a write is pending and result in a cache line state change before the WriteData response.
 * ***
 *
 * ---[WriteBackFull]:
 * HN(I)
 * *** RN_0 ---[WriteBackFull]--->  HN  ---[CompDBIDResp]---> RN_0(UD -> I) ---[CBWrData(UD_PD)]--->  HN(I -> UD)   (RN_1-I)
 * *** RN_0 ---[WriteBackFull]--->  HN  ---[CompDBIDResp]---> RN_0(UC -> I) ---[CBWrData(UC)]--->     HN(I -> UC)   (RN_1-I)    *** This shouldn't be happening
 * *** RN_0 ---[WriteBackFull]--->  HN  ---[CompDBIDResp]---> RN_0(I -> I)  ---[CBWrData(I)]--->      HN(I -> I)    (RN_1-UD)
 * HN(SC) *** This shouldn't be happening because data is dirty
 * HN(SD)
 * *** RN_0 ---[WriteBackFull]--->  HN  ---[CompDBIDResp]---> RN_0(SC -> I) ---[CBWrData(SC)]--->     HN(SD -> SD)   (RN_1-SC)
 * *** RN_0 ---[WriteBackFull]--->  HN  ---[CompDBIDResp]---> RN_0(SC -> I) ---[CBWrData(SC)]--->     HN(SD -> UD)   (RN_1-I)   *** This shouldn't be happening
 * *** RN_0 ---[WriteBackFull]--->  HN  ---[CompDBIDResp]---> RN_0(I -> I)  ---[CBWrData(I)]--->      HN(SD -> SD)   (RN_1-SC)
 * HN(UC)
 * *** RN_0 ---[WriteBackFull]--->  HN  ---[CompDBIDResp]---> RN_0(I -> I)  ---[CBWrData(SC)]--->     HN(UC -> UC)   (RN_1-I)   *** This shouldn't be happening
 * HN(UD)
 * *** RN_0 ---[WriteBackFull]--->  HN  ---[CompDBIDResp]---> RN_0(I -> I)  ---[CBWrData(SC)]--->     HN(UD -> UD)   (RN_1-I)
 *
 */


/*
 * Snoop transactions:
 * *** TODO: RN_1 return SnpResp when SnpNotSharedDirty set RetToSrc, so it require RN must return Data when RetToSrc has been set
 * *** TODO: If snoop RN is I (HN receive Comp(I)), snoop transaction transfer to Read transaction, it shouldn't be happening in this coherence system
 * *** All snoop in DSU set DoNotGoToSD, RN cant be SD in DSU coherence system
 *
 *
 *
 * ---[SnpNotSharedDirty]:
 * *** HN(I) ---[SnpNotSharedDirty(RetToSrc)]---> RN_1(UC -> SC / I) ---[SnpRespData(SC / I)]--->       HN(SC / I)   ---[CompData(SC / UC)]--->    RN_0(SC / UC)
 * *** HN(I) ---[SnpNotSharedDirty(RetToSrc)]---> RN_1(UD -> SC / I) ---[SnpRespData(SC_PD / I_PD)]---> HN(SD / I)  ---[CompData(SC / UD_PD)]---> RN_0(SC / UD)
 *
 *
 * ---[SnpUnique]:
 * *** HN(I)  ---[SnpUnique(RetToSrc)]--->  RN_1(UC -> I) ---[SnpRespData(I)]--->    HN(I)       ---[CompData(UC)]--->    RN_0(UC)
 * *** HN(I)  ---[SnpUnique(RetToSrc)]--->  RN_1(UD -> I) ---[SnpRespData(I_PD)]---> HN(I)       ---[CompData(UD_PD)]---> RN_0(UD)
 * *** HN(SC) ---[SnpUnique(RetToSrc)]--->  RN_1(SC -> I) ---[SnpResp(I)]--->        HN(SC -> I) ---[CompData(UC)]--->    RN_0(UC)
 *
 *
 * ---[SnpUnique]:Use in snoop helper
 * *** HN(I)        ---[SnpUnique(RetToSrc)]--->  RN_1(UC -> I) ---[SnpRespData(I)]--->    HN(UC)
 * *** HN(I)        ---[SnpUnique(RetToSrc)]--->  RN_1(UD -> I) ---[SnpRespData(I_PD)]---> HN(UD)
 * *** HN(SC / SD)  ---[SnpUnique]--->            RN_1(SC -> I) ---[SnpResp(I)]--->        HN(UC / UD)
 *
 *
 * ---[SnpMakeInvalid]:
 * *** HN(I)  ---[SnpMakeInvalid]---> RN_1(UC -> I) ---[SnpResp(I)]---> HN(I) ---[Comp(UC)]---> RN_0(UC)
 * *** HN(I)  ---[SnpMakeInvalid]---> RN_1(UD -> I) ---[SnpResp(I)]---> HN(I) ---[Comp(UC)]---> RN_0(UC)
 * *** HN(SC) ---[SnpMakeInvalid]---> RN_1(SC -> I) ---[SnpResp(I)]---> HN(I) ---[Comp(UC)]---> RN_0(UC)
 *
 *
 */


/*
 * Coherence:
 *
 * Read Or Dataless Req: [ReadNotSharedDirty / ReadUnique / MakeUnique]
 * 0. genSnpReq: [SnpNotSharedDirty, SnpUnique, SnpMakeInvalid]
 * 1. genNewCohWithoutSnp
 * 2. genNewCohWithSnp
 * 3. genRnResp: [Comp, CompData]
 *
 * CopyBack Req: [WriteBackFull, Evict]
 * 0. genCopyBackNewCoh
 * 1. genCopyBackRnResp
 */
object Coherence {
  /*
   * req not hit in client(dont need snoop)
   */
  def genNewCohWithoutSnp(reqOp: UInt, selfState: UInt): (UInt, UInt) = {
    val rnNS = WireInit(ChiState.ERROR)
    val hnNS = WireInit(ChiState.ERROR)
    switch(reqOp) {
      is(CHIOp.REQ.ReadNotSharedDirty) {
        switch(selfState) {
          is(ChiState.I)  { rnNS := ChiState.UC; hnNS := ChiState.I }
          is(ChiState.UC) { rnNS := ChiState.UC; hnNS := ChiState.I }
          is(ChiState.UD) { rnNS := ChiState.UD; hnNS := ChiState.I }
        }
      }
      is(CHIOp.REQ.ReadUnique) {
        switch(selfState) {
          is(ChiState.I)  { rnNS := ChiState.UC; hnNS := ChiState.I }
          is(ChiState.UC) { rnNS := ChiState.UC; hnNS := ChiState.I }
          is(ChiState.UD) { rnNS := ChiState.UD; hnNS := ChiState.I }
        }
      }
      is(CHIOp.REQ.MakeUnique) {
        switch(selfState) {
          is(ChiState.I)  { rnNS := ChiState.UC; hnNS := ChiState.I }
          is(ChiState.UC) { rnNS := ChiState.UC; hnNS := ChiState.I }
          is(ChiState.UD) { rnNS := ChiState.UD; hnNS := ChiState.I }
        }
      }
    }
    (rnNS, hnNS)
  }

  /*
   * return (channel, op, resp)
   */
  def genRnResp(reqOp: UInt, rnNS: UInt): (UInt, UInt, UInt) = {
    val channel = WireInit(0.U(CHIChannel.width.W))
    val op      = WireInit(0.U(4.W)) // DAT.op.width = 3; RSP.op.width = 4
    val resp    = WireInit(ChiResp.ERROR) // resp.width = 3
    switch(reqOp) {
      is(CHIOp.REQ.ReadNotSharedDirty) {
        switch(rnNS) {
          is(ChiState.SC) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.SC }
          is(ChiState.UC) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UC }
          is(ChiState.UD) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UD_PD }
        }
      }
      is(CHIOp.REQ.ReadUnique) {
        switch(rnNS) {
          is(ChiState.UC) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UC }
          is(ChiState.UD) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UD_PD }
        }
      }
      is(CHIOp.REQ.MakeUnique) {
        switch(rnNS) {
          is(ChiState.UC) { channel := CHIChannel.RXRSP; op := CHIOp.RSP.Comp; resp := ChiResp.UC }
        }
      }
    }
    (channel, op, resp)
  }


  /*
   *
   */

}