package soc

import chisel3._

class InstructionDecoder(val map: SocMemoryMap = SocMemoryMap()) extends Module {
  val io = IO(new Bundle {
    val cpu = Flipped(new MemoryPort)
    val bootrom = new MemoryPort
    val program = new MemoryPort
  })

  private val connections = Seq(
    Some(map.bootrom.copy(readOnly = true) -> io.bootrom),
    Some(map.program.copy(readOnly = true) -> io.program),
  ).flatten

  private val decoder =
    Module(new AddressDecoder(connections.map(_._1)))

  decoder.io.cpu <> io.cpu

  connections.zip(decoder.io.targets).foreach {
    case ((_, port), target) => port <> target
  }
}
