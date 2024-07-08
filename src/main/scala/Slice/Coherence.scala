package NHDSU.SLICE

import NHDSU._
import NHDSU.CHI._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._

/*
  [ReadNotSharedDirty]
    AMBA-CHI-Issue-B:
      Read request to a Snoopable address region.
      • Data is included with the completion response.
      • Data size is a cache line length.
      • Requester will accept the data in any valid state except SD:
      — UC, UD, SC.
      • Can have exclusive attribute asserted. See  Chapter 6 Exclusive Accesses for details.
      — Data cannot be obtained directly from the Slave Node using DMT if the
      Exclusive bit is set.
      • Communicating node pairs:
      — RN-F to ICN(HN-F).
      • Request is included in this specification for use by caches that do not support the
      SharedDirty state.

    NHDSU Coh and Resp Logic:
      [ReadNotSharedDirty]: RN(I / UCE) ----[CompData(SC / UC / UD_PD)]----> RN(SC / UC / UD)
 */

/*
  [ReadUnique]
    AMBA-CHI-Issue-B:
        Read request to a Snoopable address region to carry out a store to the cache line.
        • All other cached copies must be invalidated.
        • Data is included with the completion response.
        • Data size is a cache line length.
        • Data must be provided to the Requester in unique state only:
        — UC, or UD.
        • Communicating node pairs:
        — RN-F to ICN(HN-F).

    NHDSU Coh and Resp Logic:
      We dont care others permitted state
      [ReadUnique]: RN(I / SC) ----[CompData(UC / UD_PD)]----> RN(UC / UD)
      [ReadUnique]: RN(SD)     ----[CompData(UD_PD)]     ----> RN(UC / UD)
 */

/*
  [MakeUnique]
    AMBA-CHI-Issue-B:
        Request to Snoopable address region to obtain ownership of the cache line without a data
        response. This request is used only when the Requester guarantees that it will carry out a
        store to all bytes of the cache line.
        • Data is not included with the completion response.
        • Any dirty copy of the cache line at a snooped cache must be invalidated without
        carrying out a data transfer.
        • Communicating node pairs:
        — RN-F to ICN(HN-F).

    NHDSU Coh and Resp Logic:
      We dont care others permitted state
      [ReadUnique]: RN(I / SC / SD) ----[Comp(UC)]----> RN(UC)
 */

// Coherence
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
  def genRnRespBundle(reqOp: UInt, rnNS: UInt): (UInt, UInt, UInt) = {
    val channel = WireInit(0.U(CHIChannel.width.W))
    val op      = WireInit(0.U(4.W)) // DAT.op.width = 3; RSP.op.width = 4
    val resp    = WireInit(ChiResp.ERROR) // resp.width = 3
    switch(reqOp) {
      is(CHIOp.REQ.ReadNotSharedDirty) {
        switch(rnNS) {
          is(ChiState.SC) { channel := CHIChannel.TXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.SC }
          is(ChiState.UC) { channel := CHIChannel.TXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UC }
          is(ChiState.UD) { channel := CHIChannel.TXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UD_PD }
        }
      }
      is(CHIOp.REQ.ReadUnique) {
        switch(rnNS) {
          is(ChiState.UC) { channel := CHIChannel.TXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UC }
          is(ChiState.UD) { channel := CHIChannel.TXDAT; op := CHIOp.DAT.CompData; resp := ChiResp.UD_PD }
        }
      }
      is(CHIOp.REQ.MakeUnique) {
        switch(rnNS) {
          is(ChiState.UC) { channel := CHIChannel.TXRSP; op := CHIOp.RSP.Comp; resp := ChiResp.UC }
        }
      }
    }
    (channel, op, resp)
  }
}