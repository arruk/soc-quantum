package soc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class MemorySpec extends AnyFunSuite {
  private class Driver(clock: Clock, reset: Reset, port: MemoryPort) {
    def initialize(): Unit = {
      port.req.valid.poke(false.B)
      port.req.bits.addr.poke(0.U)
      port.req.bits.write.poke(false.B)
      port.req.bits.wdata.poke(0.U)
      port.req.bits.wmask.poke(0.U)
      port.req.bits.ren.poke(false.B)
      port.resp.ready.poke(false.B)
      reset.poke(true.B)
      clock.step(2)
      reset.poke(false.B)
      port.resp.valid.expect(false.B)
    }

    def request(addr: Int, write: Boolean = false, data: BigInt = 0, mask: Int = 15): Unit = {
      port.req.ready.expect(true.B)
      port.resp.valid.expect(false.B)
      port.req.bits.addr.poke(addr.U)
      port.req.bits.write.poke(write.B)
      port.req.bits.wdata.poke(data.U)
      port.req.bits.wmask.poke(mask.U)
      port.req.bits.ren.poke((!write).B)
      port.req.valid.poke(true.B)
      clock.step()
      port.req.valid.poke(false.B)
      // A resposta deve depender do pedido aceito, não do endereço atual.
      port.req.bits.addr.poke(0.U)
      port.req.bits.write.poke(false.B)
      port.req.bits.wdata.poke(0.U)
      port.req.bits.wmask.poke(0.U)
      port.req.bits.ren.poke(false.B)
      var cycles = 0
      while (!port.resp.valid.peek().litToBoolean && cycles < 4) {
        port.req.ready.expect(false.B)
        clock.step()
        cycles += 1
      }
      port.resp.valid.expect(true.B)
    }

    def response(expected: BigInt, stall: Int = 0): Unit = {
      for (_ <- 0 until stall) {
        port.req.ready.expect(false.B)
        port.resp.valid.expect(true.B)
        port.resp.bits.expect(expected.U)
        clock.step()
      }
      port.resp.bits.expect(expected.U)
      port.resp.ready.poke(true.B)
      clock.step()
      port.resp.ready.poke(false.B)
      port.resp.valid.expect(false.B)
      port.req.ready.expect(true.B)
      clock.step(2)
      port.resp.valid.expect(false.B)
    }

    def write(addr: Int, data: BigInt, mask: Int = 15): Unit = {
      request(addr, write = true, data = data, mask = mask)
      response(0, stall = 2)
    }

    def read(addr: Int, expected: BigInt): Unit = {
      request(addr)
      response(expected, stall = 3)
    }
  }

  test("RAM: máscaras por byte, endereços independentes, respostas retidas e reset sem apagar dados") {
    simulate(new Ram(4, initializeFromFile = false)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io)
      driver.initialize()
      val initial = BigInt("11223344", 16)
      val replacement = BigInt("aabbccdd", 16)
      driver.write(12, BigInt("deadbeef", 16))
      for (mask <- 0 until 16) {
        driver.write(0, initial)
        driver.write(0, replacement, mask)
        val byteMask = (0 until 4).filter(i => (mask & (1 << i)) != 0)
          .foldLeft(BigInt(0))((bits, i) => bits | (BigInt(255) << (8 * i)))
        driver.read(0, (initial & ~byteMask) | (replacement & byteMask))
      }
      driver.read(12, BigInt("deadbeef", 16))
      driver.request(12)
      driver.initialize() // Descarta uma resposta pendente.
      driver.read(12, BigInt("deadbeef", 16))
    }
  }

  test("ROM: conteúdo constante, escritas ignoradas e respostas retidas") {
    val words = Seq(BigInt("00000013", 16), BigInt("80000000", 16), BigInt("ffffffff", 16))
    simulate(new Rom(words)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io)
      driver.initialize()
      for ((word, index) <- words.zipWithIndex) {
        driver.read(4 * index, word)
        driver.write(4 * index, 0)
        driver.read(4 * index, word)
      }
      driver.request(4)
      driver.initialize()
      driver.read(4, words(1))
    }
  }

  test("RAM: profundidade de uma palavra") {
    simulate(new Ram(1, initializeFromFile = false)) { dut =>
      val driver = new Driver(dut.clock, dut.reset, dut.io)
      driver.initialize()
      driver.write(0, BigInt("12345678", 16))
      driver.read(0, BigInt("12345678", 16))
    }
  }
}
