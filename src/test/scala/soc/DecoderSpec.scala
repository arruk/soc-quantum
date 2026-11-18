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
    val map = SocMemoryMap(program = Some(program), qc = Some(qc), spi = Some(spi), gpio = Some(gpio))
    simulate(new DataDecoder(map)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu,
        Seq(dut.io.bootrom, dut.io.program.get, dut.io.data, dut.io.qc.get, dut.io.uart, dut.io.spi.get, dut.io.gpio.get))
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
      program = Some(program)
    )
    simulate(new InstructionDecoder(map)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu, Seq(dut.io.bootrom, dut.io.program.get))
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

  test("Dados: mapa simples omite portas opcionais e mantém dados e UART nos destinos corretos") {
    val map = SocMemoryMap()
    simulate(new DataDecoder(map)) { dut =>
      assert(Seq(dut.io.program, dut.io.qc, dut.io.spi, dut.io.gpio).forall(_.isEmpty))
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu,
        Seq(dut.io.bootrom, dut.io.data, dut.io.uart))
      driver.initialize()
      driver.route(0, map.bootrom.base, 0)
      driver.route(1, map.data.end - 4, map.data.sizeBytes - 4, write = true)
      driver.route(2, map.uart.base, 0, write = true)
      Seq("00800000", "20000000", "20002000", "20003000").foreach { address =>
        driver.rejected(BigInt(address, 16))
      }
      driver.resetPending(1, map.data.base)
      driver.route(2, map.uart.base + 4, 4)
    }
  }

  test("Dados: opcionais intercalados preservam a associação entre região e porta") {
    val qc = MemoryRegion(BigInt("20000000", 16), 4096)
    val gpio = MemoryRegion(BigInt("20003000", 16), 4096)
    val map = SocMemoryMap(qc = Some(qc), gpio = Some(gpio))
    simulate(new DataDecoder(map)) { dut =>
      assert(dut.io.program.isEmpty && dut.io.spi.isEmpty)
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu,
        Seq(dut.io.bootrom, dut.io.data, dut.io.qc.get, dut.io.uart, dut.io.gpio.get))
      driver.initialize()
      driver.route(0, map.bootrom.base, 0)
      driver.route(1, map.data.base, 0)
      driver.route(2, qc.base + 4, 4, write = true)
      driver.route(3, map.uart.base + 8, 8, write = true)
      driver.route(4, gpio.end - 4, gpio.sizeBytes - 4, write = true)
      driver.rejected(BigInt("00800000", 16))
      driver.rejected(BigInt("20002000", 16))
    }
  }

  test("Instruções: sem RAM de programa, somente BOOTROM está disponível") {
    val map = SocMemoryMap()
    simulate(new InstructionDecoder(map)) { dut =>
      assert(dut.io.program.isEmpty)
      val driver = new Driver(dut.clock, dut.reset, dut.io.cpu, Seq(dut.io.bootrom))
      driver.initialize()
      driver.route(0, map.bootrom.base, 0)
      driver.route(0, map.bootrom.end - 4, map.bootrom.sizeBytes - 4)
      driver.rejected(map.bootrom.base, write = true)
      driver.rejected(map.data.base)
      driver.rejected(BigInt("00800000", 16))
      driver.resetPending(0, map.bootrom.base)
      driver.route(0, map.bootrom.base + 4, 4)
    }
  }
}
