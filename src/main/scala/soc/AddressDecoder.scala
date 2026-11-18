package soc

import chisel3._
import chisel3.util._

class AddressDecoder(val regions: Seq[MemoryRegion]) extends Module {
  require(regions.nonEmpty)
  for ((a, i) <- regions.zipWithIndex; b <- regions.drop(i + 1)) {
    require(a.end <= b.base || b.end <= a.base, "Memory regions overlap")
  }

  val io = IO(new Bundle {
    val cpu = Flipped(new MemoryPort)
    val targets = Vec(regions.size, new MemoryPort)
  })

  private val pending = RegInit(false.B)
  private val selected = Reg(UInt(math.max(1, log2Ceil(regions.size)).W))
  private val invalid = Reg(Bool())
  private val active = !reset.asBool
  private val request = io.cpu.req.bits
  private val hits = VecInit(regions.map { region =>
    request.addr >= region.base.U(32.W) &&
      request.addr < region.end.U(33.W) &&
      request.addr(1, 0) === 0.U &&
      !(request.write && region.readOnly.B)
  })
  private val mapped = hits.asUInt.orR

  for ((region, i) <- regions.zipWithIndex) {
    val target = io.targets(i)
    target.req.valid := active && !pending && io.cpu.req.valid && hits(i)
    target.req.bits := request
    target.req.bits.addr := request.addr - region.base.U(32.W)
    target.resp.ready := active && pending && !invalid && selected === i.U && io.cpu.resp.ready
  }

  io.cpu.req.ready := active && !pending &&
    Mux(mapped, Mux1H(hits, io.targets.map(_.req.ready)), true.B)

  private val responseValid = Mux1H(
    regions.indices.map(i => (selected === i.U) -> io.targets(i).resp.valid)
  )
  private val responseData = Mux1H(
    regions.indices.map(i => (selected === i.U) -> io.targets(i).resp.bits)
  )
  io.cpu.resp.valid := active && pending && Mux(invalid, true.B, responseValid)
  io.cpu.resp.bits := Mux(invalid, 0.U, responseData)

  when(io.cpu.req.fire) {
    pending := true.B
    selected := OHToUInt(hits.asUInt)
    invalid := !mapped
  }

  when(io.cpu.resp.fire) {
    pending := false.B
  }
}
