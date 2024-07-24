// package SubordinateNode

// import chisel3._
// import chisel3.util._
// import NHDSU._

// class FIFOBuffer(val WIDTH: Int, val ENTRIES: Int) extends DSUParam {

//   // -------------------------- IO declaration -----------------------------//

//   val io = IO(new Bundle {
//     val pushd_i     = Input(UInt(WIDTH.W))
//     val push_i      = Input(Bool())
//     val popd_o      = Output(UInt(WIDTH.W))
//     val pop_i       = Input(Bool())
//     val empty_o     = Output(Bool())
//     val full_o      = Output(Bool())
//     val reset       = Output(Bool())
//   })

//   // ----------------------- Reg/Wire declaration --------------------------//

//   val PTR_WIDTH     = log2Ceil(ENTRIES)
//   val CNT_WIDTH     = log2Ceil(ENTRIES + 1)
//   val fifo          = RegInit(VecInit(Seq.fill(ENTRIES)(0.U(WIDTH.W))))
//   val rptr          = RegInit(0.U(PTR_WIDTH.W))
//   val wptr          = RegInit(0.U(PTR_WIDTH.W))
//   val count         = RegInit(0.U(CNT_WIDTH.W))
//   val fifo_we       = Wire(Vec(ENTRIES, Bool()))

//   //---------------------------- Logic -------------------------------------//

//   /* 
//   Write enable logic
//    */

//   for (i <- 0 until ENTRIES) {
//     fifo_we(i)     := (wptr === i.U) && io.push_i && !io.full_o
//   }

//   /* 
//   FIFO write logic
//    */

//   for (i <- 0 until ENTRIES) {
//     when(io.reset) {
//       fifo(i)      := 0.U
//     } .elsewhen(fifo_we(i)) {
//       fifo(i)      := io.pushd_i
//     }
//   }

//   /* 
//   Read pointer logic
//    */

//   when(io.reset) {
//     rptr           := 0.U
//   } .elsewhen(io.pop_i && !io.empty_o) {
//     rptr           := rptr + 1.U
//   }

//   /* 
//   Write pointer logic
//    */

//   when(io.reset) {
//     wptr           := 0.U
//   } .elsewhen(io.push_i && !io.full_o) {
//     wptr           := wptr + 1.U
//   }

//   /* 
//   FIFO count logic
//    */

//   when(io.reset) {
//     count          := 0.U
//   } .elsewhen(io.push_i ^ io.pop_i) {
//     when(io.push_i) {
//       count        := count + 1.U
//     } .otherwise {
//       count        := count - 1.U
//     }
//   }

//   /* 
//   Output Logic
//    */

//   io.popd_o        := fifo(rptr)
//   io.empty_o       := (count === 0.U)
//   io.full_o        := (count === ENTRIES.U)
// }

