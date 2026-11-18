package soc

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline

/** Byte-masked RAM. Disable HEX initialization for small standalone test memories. */
class Ram(val depthWords: Int, initializeFromFile: Boolean = true) extends Module {
  require(depthWords > 0 && BigInt(depthWords) * 4 <= (BigInt(1) << 32))

  val io = IO(Flipped(new MemoryPort))

  private val memory = SyncReadMem(depthWords, Vec(4, UInt(8.W)))
  private val hexPath = java.nio.file.Paths
    .get(sys.env.getOrElse("CC_DATARAM", "../../benchmarks/build/hex/dhrystone.DATARAM.hex"))
    .toAbsolutePath
    .toString

  if (initializeFromFile) {
    loadMemoryFromFileInline(memory, hexPath)
  }

  private val idle :: reading :: responding :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val response = Reg(UInt(32.W))
  private val request = io.req.bits
  private val index = if (depthWords == 1) 0.U else request.addr(log2Ceil(depthWords) + 1, 2)

  io.req.ready := state === idle && !reset.asBool
  io.resp.valid := state === responding && !reset.asBool
  io.resp.bits := response

  private val readData = memory.readWrite(
    index,
    request.wdata.asTypeOf(Vec(4, UInt(8.W))),
    request.wmask.asBools,
    io.req.fire,
    request.write
  )

  when(io.req.fire) {
    assert(request.addr(1, 0) === 0.U, "RAM: unaligned address")
    assert(request.addr < (BigInt(depthWords) * 4).U(33.W), "RAM: address out of range")
    response := 0.U
    state := Mux(request.write, responding, reading)
  }

  when(state === reading) {
    response := readData.asUInt
    state := responding
  }

  when(io.resp.fire) {
    state := idle
  }
}
