package NHDSU.SLICE

import NHDSU._
import NHDSU.CHI._
import NHDSU.CHI.ChiState._
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
 * *** RN_0(I)  ---[ReadNotSharedDirty]--->   HN(UD -> I)  (RN_1-I)             ---[CompData(UD_PD)]--->  RN_0(UD)
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
 * *** RN_0(SC) ---[ReadUnique]---> HN(SD -> I) (RN_1-I)              ---[CompData(UD_PD)]---> RN_0(UD)
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
 * *** TODO: If snoop RN is I (HN receive Comp(I)), snoop transaction transfer to Read transaction(it will be happening in this coherence system because of Evict)
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
 * *** HN(I)        ---[SnpUnique(RetToSrc)]--->  RN_1(UC -> I) ---[SnpRespData(I)]--->    HN(I)       ---[CompData(UC)]--->            RN_0(UC)
 * *** HN(I)        ---[SnpUnique(RetToSrc)]--->  RN_1(UD -> I) ---[SnpRespData(I_PD)]---> HN(I)       ---[CompData(UD_PD)]--->         RN_0(UD)
 * *** HN(SC / SD)  ---[SnpUnique]--->            RN_1(SC -> I) ---[SnpResp(I)]--->        HN(I)       ---[CompData(UC / UD_PD)]--->    RN_0(UC / UD)
 *
 *
 *
 *
 * ---[SnpMakeInvalid]:
 * *** HN(I)       ---[SnpMakeInvalid]---> RN_1(UC -> I) ---[SnpResp(I)]---> HN(I) ---[Comp(UC)]---> RN_0(UD)
 * *** HN(I)       ---[SnpMakeInvalid]---> RN_1(UD -> I) ---[SnpResp(I)]---> HN(I) ---[Comp(UC)]---> RN_0(UD)
 * *** HN(SC / SD) ---[SnpMakeInvalid]---> RN_1(SC -> I) ---[SnpResp(I)]---> HN(I) ---[Comp(UC)]---> RN_0(UD)
 *
 *
 */


/*
 * ---[SnpUnique]: Use in snoop helper
 * *** HN(I)        ---[SnpUnique(RetToSrc)]--->  RN_1(UC -> I) ---[SnpRespData(I)]--->    HN(UC)
 * *** HN(I)        ---[SnpUnique(RetToSrc)]--->  RN_1(UD -> I) ---[SnpRespData(I_PD)]---> HN(UD)
 * *** HN(SC / SD)  ---[SnpUnique]--->            RN_1(SC -> I) ---[SnpResp(I)]--->        HN(UC / UD)
 *
 */


/*
 * ---[WriteNoSnpFull]: Use in replace
 * *** HN(UC -> I)
 * *** HN(SC -> I)
 * *** HN(UD -> I) ---[WriteNoSnpFull]---> SN  ---[DBIDResp]---> HN ---[WriteData]---> SN
 * *** HN(SD -> I) ---[WriteNoSnpFull]---> SN  ---[DBIDResp]---> HN ---[WriteData]---> SN // TODO: Coherence should support RN[SC]-HN[I]
 *
 */




/*
 * Coherence:
 *
 * Read Or Dataless Req: [ReadNotSharedDirty / ReadUnique / MakeUnique / Evict]
 * 0. genSnpReq: [SnpNotSharedDirty, SnpUnique, SnpMakeInvalid]
 * 1. genNewCohWithoutSnp
 * 2. genNewCohWithSnp
 * 3. genRnResp: [Comp, CompData]
 *
 * CopyBack Req: [WriteBackFull]
 * 0. genCopyBackNewCoh
 *
 * snoopHelper:
 * 0. genSnoopHelperReq: [SnpUnique, SnpMakeInvalid]
 *
 * Gen Replace:
 * 0. genReplace: [WriteNoSnpFull]
 *
 */
object Coherence {
  /*
   * req gen snoop
   */
  def genSnpReq(reqOp: UInt, self: UInt, other: UInt): (UInt, Bool, Bool, Bool, Bool) = {
    val snpOp       = WireInit(0x18.U(5.W)) // SNP.op.width = 5
    val doNotGoToSD = WireInit(true.B)
    val retToSrc    = WireInit(false.B)
    val needSnp     = WireInit(false.B)
    val error       = WireInit(true.B)
    switch(reqOp) {
      is(CHIOp.REQ.ReadNotSharedDirty) {
        snpOp     := CHIOp.SNP.SnpNotSharedDirty
        retToSrc  := true.B
        switch(other) {
          is(I)  { needSnp := false.B; error := false.B }
          is(UC) { needSnp := self === I; error := false.B }
          is(UD) { needSnp := self === I; error := false.B }
        }
      }
      is(CHIOp.REQ.ReadUnique) {
        snpOp := CHIOp.SNP.SnpUnique
        switch(other) {
          is(I)  { needSnp := false.B; error := false.B }
          is(UC) { needSnp := self === I; retToSrc := true.B; error := false.B }
          is(UD) { needSnp := self === I; retToSrc := true.B; error := false.B }
          is(SC) { needSnp := self === SC | self === SD; retToSrc := false.B; error := false.B }
        }
      }
      is(CHIOp.REQ.MakeUnique) {
        snpOp     := CHIOp.SNP.SnpMakeInvalid
        retToSrc  := false.B
        switch(other) {
          is(I)  { needSnp := false.B; error := false.B }
          is(UC) { needSnp := self === I; error := false.B }
          is(UD) { needSnp := self === I; error := false.B }
          is(SC) { needSnp := self === SC | self === SD; error := false.B }
        }
      }
      // TODO: Snoop Helper
    }
    (snpOp, doNotGoToSD, retToSrc, needSnp, error)
  }

  /*
   * gen new coherence when req need snoop
   */
  def genNewCohWithSnp(reqOp: UInt, snpResp: UInt): (UInt, UInt, UInt, Bool) = {
    val srcRnNS = WireInit(I)
    val othRnNS = WireInit(I)
    val hnNS    = WireInit(I)
    val error   = WireInit(true.B)
    switch(reqOp) {
      is(CHIOp.REQ.ReadNotSharedDirty) {
        switch(snpResp) {
          is(ChiResp.SC)    { othRnNS := SC; hnNS := SC; srcRnNS := SC; error := false.B }
          is(ChiResp.SC_PD) { othRnNS := SC; hnNS := SD; srcRnNS := SC; error := false.B }
          is(ChiResp.I)     { othRnNS := I; hnNS := I; srcRnNS := UC; error := false.B }
          is(ChiResp.I_PD)  { othRnNS := I; hnNS := I; srcRnNS := UD; error := false.B }
        }
      }
      is(CHIOp.REQ.ReadUnique) {
        switch(snpResp) {
          is(ChiResp.I)     { othRnNS := I; hnNS := I; srcRnNS := UC; error := false.B }
          is(ChiResp.I_PD)  { othRnNS := I; hnNS := I; srcRnNS := UD; error := false.B }
        }
      }
      is(CHIOp.REQ.MakeUnique) {
          othRnNS := I; hnNS := I; srcRnNS := UD; error := snpResp === ChiResp.I
      }
    }
    (srcRnNS, othRnNS, hnNS, error)
  }




  /*
   * gen new coherence when req dont need snoop
   */
  def genNewCohWithoutSnp(reqOp: UInt, self: UInt, otherCHit: Bool): (UInt, UInt, Bool, Bool) = {
    val rnNS      = WireInit(I)
    val hnNS      = WireInit(I)
    val needRDown = WireInit(false.B)
    val error     = WireInit(true.B)
    switch(reqOp) {
      is(CHIOp.REQ.ReadNotSharedDirty) {
        switch(self) {
          is(I)  { rnNS := UC; hnNS := I; needRDown := !otherCHit }
          is(SC) { rnNS := SC; hnNS := SC }
          is(SD) { rnNS := SC; hnNS := SD }
          is(UC) { rnNS := UC; hnNS := I }
          is(UD) { rnNS := UD; hnNS := I }
        }
        error := false.B
      }
      is(CHIOp.REQ.ReadUnique) {
        switch(self) {
          is(I)  { rnNS := UC; hnNS := I; needRDown := !otherCHit }
          is(SC) { rnNS := UC; hnNS := I }
          is(SD) { rnNS := UD; hnNS := I }
          is(UC) { rnNS := UC; hnNS := I }
          is(UD) { rnNS := UD; hnNS := I }
        }
        error := false.B
      }
      is(CHIOp.REQ.MakeUnique) {
        switch(self) {
          is(I)  { rnNS := UD; hnNS := I }
          is(SC) { rnNS := UD; hnNS := I }
          is(SD) { rnNS := UD; hnNS := I }
          is(UC) { rnNS := UD; hnNS := I }
          is(UD) { rnNS := UD; hnNS := I }
        }
        error := false.B
      }
      is(CHIOp.REQ.Evict) {
        switch(self) {
          is(I)  { rnNS := I; hnNS := I }
          is(SC) { rnNS := I; hnNS := Mux(otherCHit, SC, UC) }
          is(SD) { rnNS := I; hnNS := Mux(otherCHit, SD, UD) }
          is(UC) { rnNS := I; hnNS := UC }
          is(UD) { rnNS := I; hnNS := UD }
        }
        error := false.B
      }
    }
    (rnNS, hnNS, needRDown, error)
  }


  /*
   * return rn resp (channel, op, resp)
   */
  def genRnResp(reqOp: UInt, rnNS: UInt): (UInt, UInt, UInt, Bool) = {
    val channel = WireInit(0.U(CHIChannel.width.W))
    val op      = WireInit(0.U(4.W)) // DAT.op.width = 3; RSP.op.width = 4
    val resp    = WireInit(ChiResp.I) // resp.width = 3
    val error   = WireInit(true.B)
    switch(reqOp) {
      is(CHIOp.REQ.ReadNotSharedDirty) {
        switch(rnNS) {
          is(SC) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.SC; error := false.B }
          is(UC) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UC; error := false.B }
          is(UD) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UD_PD; error := false.B }
        }
      }
      is(CHIOp.REQ.ReadUnique) {
        switch(rnNS) {
          is(UC) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UC; error := false.B }
          is(UD) { channel := CHIChannel.RXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UD_PD; error := false.B }
        }
      }
      is(CHIOp.REQ.MakeUnique) {
        switch(rnNS) {
          is(UD) { channel := CHIChannel.RXRSP; op := CHIOp.RSP.Comp; resp := ChiResp.UC; error := false.B }
        }
      }
      is(CHIOp.REQ.Evict) {
        channel := CHIChannel.RXRSP; op := CHIOp.RSP.Comp; resp := ChiResp.I; error := false.B
      }
    }
    (channel, op, resp, error)
  }


  /*
   * gen new coherence when req dont need snoop
   */
  def genCopyBackNewCoh(reqOp: UInt, self: UInt, resp: UInt, otherCHit: Bool): (UInt, UInt, Bool) = {
    val rnNS      = WireInit(I)
    val hnNS      = WireInit(I)
    val error     = WireInit(true.B)
    switch(reqOp) {
      is(CHIOp.REQ.WriteBackFull) {
        switch(resp) {
          is(ChiResp.UD_PD) { rnNS := I; hnNS := UD }
          is(ChiResp.UC)    { rnNS := I; hnNS := UC }
          is(ChiResp.SC)    { rnNS := I; hnNS := Mux(otherCHit, self, Mux(self === SD, UD, UC)) }
          is(ChiResp.I)     { rnNS := I; hnNS := self }
        }
        error := false.B
      }
    }
    (rnNS, hnNS, error)
  }

  /*
   * gen SnoopHelper Req
   */
  def genSnpHelperReq(rnNS: UInt, clientHit: Bool, clientState: UInt): (UInt, Bool, Bool, Bool, Bool) = {
    val snpOp       = WireInit(CHIOp.SNP.SnpUnique) // SNP.op.width = 5
    val doNotGoToSD = WireInit(true.B)
    val retToSrc    = WireInit(false.B)
    val needSnpHlp  = WireInit(false.B)
    val error       = WireInit(true.B)
    when(!clientHit & rnNS =/= I) {
      switch(clientState) {
        is(I)  { needSnpHlp := false.B; error := false.B }
        is(UC) { needSnpHlp := true.B;  retToSrc := true.B;  error := false.B }
        is(UD) { needSnpHlp := false.B; retToSrc := true.B;  error := false.B }
        is(SC) { needSnpHlp := false.B; retToSrc := false.B; error := false.B }
      }
    }.otherwise {
      needSnpHlp := false.B
      error := false.B
    }

    (snpOp, doNotGoToSD, retToSrc, needSnpHlp, error)
  }


  /*
   * gen Replace Req
   */
  def genReplaceReq(hnNS: UInt, selfHit: Bool, selfState: UInt): (UInt, Bool, Bool, Bool) = {
    val needRepl = WireInit(false.B)
    val needWDS  = WireInit(false.B)
    val replOp   = WireInit(CHIOp.REQ.WriteNoSnpFull)
    val error    = WireInit(true.B)
    when(!selfHit & hnNS =/= I) {
      switch(selfState) {
        is(I)  { needRepl := false.B; error := false.B }
        is(UC) { needRepl := false.B; error := false.B }
        is(UD) { needRepl := true.B;  error := false.B }
        is(SC) { needRepl := false.B; error := false.B }
        is(SD) { needRepl := true.B;  error := false.B }
      }
      needWDS := true.B
    }.otherwise {
      needRepl := false.B
      error := false.B
    }

    (replOp, needRepl, needWDS, error)
  }

}