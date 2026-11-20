package soc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class DecoderSpec extends AnyFunSuite {
  private class Driver(clock: Clock, reset: Reset, cpu: MemoryPort, targets: Seq[MemoryPort]) {
    def initialize(): Unit = {
      cpu.req.valid.poke(false.B)
      cpu.req.bits.addr.poke(0.U)
      cpu.req.bits.write.poke(false.B)
      cpu.req.bits.wdata.poke(0.U)
      cpu.req.bits.wmask.poke(0.U)
      cpu.req.bits.ren.poke(false.B)
      cpu.resp.ready.poke(false.B)
      targets.foreach { target =>
        target.req.ready.poke(false.B)
        target.resp.valid.poke(false.B)
        target.resp.bits.poke(0.U)
      }
      reset.poke(true.B)
      clock.step(2)
      reset.poke(false.B)
      cpu.resp.valid.expect(false.B)
    }

    private def begin(address: BigInt, write: Boolean): Unit = {
      cpu.req.bits.addr.poke(address.U)
      cpu.req.bits.write.poke(write.B)
      cpu.req.bits.wdata.poke("h12345678".U)
      cpu.req.bits.wmask.poke(5.U)
      cpu.req.bits.ren.poke((!write).B)
      cpu.req.valid.poke(true.B)
    }

    def route(index: Int, address: BigInt, offset: BigInt, write: Boolean = false): Unit = {
      begin(address, write)
      targets.foreach(_.req.ready.poke(false.B))
      for ((target, i) <- targets.zipWithIndex) target.req.valid.expect((i == index).B)
      val chosen = targets(index)
      chosen.req.bits.addr.expect(offset.U)
      chosen.req.bits.write.expect(write.B)
      chosen.req.bits.wdata.expect("h12345678".U)
      chosen.req.bits.wmask.expect(5.U)
      chosen.req.bits.ren.expect((!write).B)
      cpu.req.ready.expect(false.B)
      clock.step(2) // O destino ainda não aceitou o pedido.
      chosen.req.valid.expect(true.B)
      chosen.req.ready.poke(true.B)
      cpu.req.ready.expect(true.B)
      clock.step()

      // Um próximo pedido não pode alterar o roteamento da resposta pendente.
      cpu.req.bits.addr.poke("hffffffff".U)
      cpu.req.bits.write.poke(false.B)
      targets.foreach { target =>
        target.req.ready.poke(true.B)
        target.req.valid.expect(false.B)
        target.resp.valid.poke(true.B)
        target.resp.bits.poke("hbad0bad0".U)
      }
      chosen.resp.valid.poke(false.B)
      cpu.req.ready.expect(false.B)
      cpu.resp.valid.expect(false.B)
      clock.step(2) // Respostas de outros destinos são ignoradas.
      chosen.resp.bits.poke("hcafebabe".U)
      chosen.resp.valid.poke(true.B)
      for (_ <- 0 until 3) {
        cpu.resp.valid.expect(true.B)
        cpu.resp.bits.expect("hcafebabe".U)
        cpu.req.ready.expect(false.B)
        targets.foreach(_.resp.ready.expect(false.B))
        clock.step()
      }

      cpu.req.valid.poke(false.B)
      cpu.resp.ready.poke(true.B)
      for ((target, i) <- targets.zipWithIndex) target.resp.ready.expect((i == index).B)
      clock.step()
      cpu.resp.ready.poke(false.B)
      targets.foreach(_.resp.valid.poke(false.B))
      cpu.resp.valid.expect(false.B)
      targets.foreach(_.req.valid.expect(false.B))
      clock.step()
    }

    def rejected(address: BigInt, write: Boolean = false): Unit = {
      begin(address, write)
      targets.foreach(_.req.valid.expect(false.B))
      cpu.req.ready.expect(true.B)
      clock.step()
      cpu.req.valid.poke(false.B)
      for (_ <- 0 until 3) {
        cpu.req.ready.expect(false.B)
        cpu.resp.valid.expect(true.B)
        cpu.resp.bits.expect(0.U)
        targets.foreach { target =>
          target.req.valid.expect(false.B)
          target.resp.ready.expect(false.B)
        }
        clock.step()
      }
      cpu.resp.ready.poke(true.B)
      clock.step()
      cpu.resp.ready.poke(false.B)
      cpu.resp.valid.expect(false.B)
    }

    def resetPending(index: Int, address: BigInt): Unit = {
      begin(address, write = false)
      targets(index).req.ready.poke(true.B)
      clock.step()
      cpu.req.valid.poke(false.B)
      cpu.req.ready.expect(false.B)
      reset.poke(true.B)
      clock.step()
      reset.poke(false.B)
      cpu.resp.valid.expect(false.B)
      targets.foreach(_.resp.ready.expect(false.B))
    }
  }

  test("Dados: todos os destinos, offsets locais, espera e retenção do destino") {
    val program = MemoryRegion(BigInt("00800000", 16), 4096)
    val qc = MemoryRegion(BigInt("20000000", 16), 4096)
    val spi = MemoryRegion(BigInt("20002000", 16), 4096)
    val gpio = MemoryRegion(BigInt("20003000", 16), 4096)
    val map = SocMemoryMap(program = program, qc = Some(qc), spi = spi, gpio = gpio)
    simulate(new DataDecoder(map)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu,
        Seq(dut.io.bootrom, dut.io.program, dut.io.data, dut.io.qc.get, dut.io.uart, dut.io.spi, dut.io.gpio))
      driver.initialize()
      val regions = Seq(map.bootrom, program, map.data, qc, map.uart, spi, gpio)
      for ((region, i) <- regions.zipWithIndex) {
        driver.route(i, region.base, 0)
        driver.route(i, region.end - 4, region.sizeBytes - 4, write = i != 0)
      }
      driver.rejected(map.bootrom.base, write = true)
      driver.rejected(0)
      driver.rejected(program.end)
      driver.rejected(map.data.base + 1)
      driver.resetPending(2, map.data.base)
      driver.route(3, qc.base, 0)
    }
  }

  test("Instruções: somente BOOTROM e programa, sempre leitura") {
    val program = MemoryRegion(BigInt("00800000", 16), 20)
    val map = SocMemoryMap(
      bootrom = MemoryRegion(BigInt("00400000", 16), 12),
      program = program
    )
    simulate(new InstructionDecoder(map)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu, Seq(dut.io.bootrom, dut.io.program))
      driver.initialize()
      driver.route(0, map.bootrom.base + 8, 8)
      driver.route(1, program.base + 16, 16)
      driver.rejected(program.base, write = true)
      driver.rejected(map.bootrom.base, write = true)
      driver.rejected(map.bootrom.end)
      driver.rejected(map.data.base)
      driver.rejected(program.base + 2)
    }
  }

  test("Dados: mapa padrao inclui programa, SPI e GPIO e omite somente QC") {
    val map = SocMemoryMap()
    simulate(new DataDecoder(map)) { dut =>
      assert(dut.io.qc.isEmpty)
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu,
        Seq(dut.io.bootrom, dut.io.program, dut.io.data, dut.io.uart, dut.io.spi, dut.io.gpio))
      driver.initialize()
      val regions = Seq(map.bootrom, map.program, map.data, map.uart, map.spi, map.gpio)
      for ((region, i) <- regions.zipWithIndex) {
        driver.route(i, region.base, 0, write = i != 0)
      }
      driver.rejected(BigInt("20000000", 16))
      driver.rejected(map.gpio.end)
      driver.resetPending(2, map.data.base)
      driver.route(3, map.uart.base + 4, 4)
    }
  }

  test("Dados: QC opcional preserva a associacao entre regioes e portas") {
    val qc = MemoryRegion(BigInt("20000000", 16), 4096)
    val map = SocMemoryMap(qc = Some(qc))
    simulate(new DataDecoder(map)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu,
        Seq(dut.io.bootrom, dut.io.program, dut.io.data, dut.io.qc.get, dut.io.uart, dut.io.spi, dut.io.gpio))
      driver.initialize()
      driver.route(0, map.bootrom.base, 0)
      driver.route(1, map.program.base + 4, 4, write = true)
      driver.route(2, map.data.base, 0)
      driver.route(3, qc.base + 4, 4, write = true)
      driver.route(4, map.uart.base + 8, 8, write = true)
      driver.route(5, map.spi.base + 12, 12, write = true)
      driver.route(6, map.gpio.end - 4, map.gpio.sizeBytes - 4, write = true)
      driver.rejected(qc.base + 2)
    }
  }

  test("Instrucoes: mapa padrao inclui programa e reset descarta resposta pendente") {
    val map = SocMemoryMap()
    simulate(new InstructionDecoder(map)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu, Seq(dut.io.bootrom, dut.io.program))
      driver.initialize()
      driver.route(0, map.bootrom.base, 0)
      driver.route(0, map.bootrom.end - 4, map.bootrom.sizeBytes - 4)
      driver.route(1, map.program.base, 0)
      driver.rejected(map.bootrom.base, write = true)
      driver.rejected(map.program.base, write = true)
      driver.rejected(map.data.base)
      driver.resetPending(1, map.program.base)
      driver.route(0, map.bootrom.base + 4, 4)
    }
  }
}
