package soc

case class MemoryRegion(base: BigInt, sizeBytes: BigInt, readOnly: Boolean = false) {
  require(base >= 0 && base % 4 == 0, "Base must be word aligned")
  require(sizeBytes > 0 && sizeBytes % 4 == 0, "Size must contain complete words")
  require(base + sizeBytes <= (BigInt(1) << 32), "Region exceeds the address space")

  val end: BigInt = base + sizeBytes
}

/*
case class SocMemoryMap(
  bootrom: MemoryRegion = MemoryRegion(BigInt("00400000", 16), 4096, readOnly = true),
  program: MemoryRegion = MemoryRegion(BigInt("00800000", 16), 4096),
  data: MemoryRegion = MemoryRegion(BigInt("10010000", 16), 1024),
  qc: MemoryRegion = MemoryRegion(BigInt("20000000", 16), 4096),
  uart: MemoryRegion = MemoryRegion(BigInt("20001000", 16), 4096),
  spi: MemoryRegion = MemoryRegion(BigInt("20002000", 16), 4096),
  gpio: MemoryRegion = MemoryRegion(BigInt("20003000", 16), 4096)
)
*/

case class SocMemoryMap(
  bootrom: MemoryRegion = MemoryRegion(BigInt("00400000", 16), 4096, readOnly = true),
  program: Option[MemoryRegion] = None,
  data: MemoryRegion = MemoryRegion(BigInt("10010000", 16), 1024),
  uart: MemoryRegion = MemoryRegion(BigInt("20001000", 16), 4096),
  qc: Option[MemoryRegion] = None,
  spi: Option[MemoryRegion] = None,
  gpio: Option[MemoryRegion] = None
)

object MemoryMaps {
  val quantum = SocMemoryMap()

  val simpler = SocMemoryMap()

  // Memory layout used by benchmarks/common/pipeline.ld and putchar.S.
  val benchmark = SocMemoryMap(
    bootrom = MemoryRegion(BigInt("00000000", 16), 65536, readOnly = true),
    data = MemoryRegion(BigInt("00010000", 16), 65536),
    uart = MemoryRegion(BigInt("00400000", 16), 4096)
  )
}
