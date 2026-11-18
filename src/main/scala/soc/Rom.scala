package soc

import chisel3._
import chisel3.util._

class Rom(val contents: Seq[BigInt]) extends Module {
  require(contents.nonEmpty && BigInt(contents.size) * 4 <= (BigInt(1) << 32))
  require(contents.forall(word => word >= 0 && word < (BigInt(1) << 32)))

  val io = IO(Flipped(new MemoryPort))

  private val words = VecInit(contents.map(_.U(32.W)))
  private val index = if (contents.size == 1) 0.U else io.req.bits.addr(log2Ceil(contents.size) + 1, 2)
  private val response = Reg(UInt(32.W))
  private val pending = RegInit(false.B)

  io.req.ready := !pending && !reset.asBool
  io.resp.valid := pending && !reset.asBool
  io.resp.bits := response

  when(io.req.fire) {
    assert(io.req.bits.addr(1, 0) === 0.U, "ROM: unaligned address")
    assert(io.req.bits.addr < (BigInt(contents.size) * 4).U(33.W), "ROM: address out of range")
    response := Mux(io.req.bits.write, 0.U, words(index))
    pending := true.B
  }

  when(io.resp.fire) {
    pending := false.B
  }
}
