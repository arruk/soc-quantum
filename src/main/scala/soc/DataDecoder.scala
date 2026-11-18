package soc

import chisel3._

class DataDecoder(val map: SocMemoryMap = SocMemoryMap()) extends Module {
  val io = IO(new Bundle {
    val cpu = Flipped(new MemoryPort)
    val bootrom = new MemoryPort
    val data = new MemoryPort
    val uart = new MemoryPort
    val gpio = new MemoryPort
    val spi = new MemoryPort

    val program = map.program.map(_ => new MemoryPort)
    val qc      = map.qc.map(_ => new MemoryPort)
      
  })

  private val connections = Seq(
    Some(map.bootrom.copy(readOnly = true) -> io.bootrom),
    map.program.zip(io.program),
    Some(map.data -> io.data),
    map.qc.zip(io.qc),
    Some(map.uart -> io.uart),
    Some(map.spi -> io.spi),
    Some(map.gpio -> io.gpio)
  ).flatten

  private val decoder =
    Module(new AddressDecoder(connections.map(_._1)))

  decoder.io.cpu <> io.cpu

  connections.zip(decoder.io.targets).foreach {
    case ((_, port), target) => port <> target
  }

}
