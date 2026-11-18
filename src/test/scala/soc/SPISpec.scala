package soc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable.ArrayBuffer

class SPISpec extends AnyFunSuite {
  private class Loopback(mode: Int) extends Module {
    val io = IO(new Bundle {
      val mem = Flipped(new MemoryPort)
      val sclk = Output(Bool())
      val mosi = Output(Bool())
      val cs = Output(Bool())
    })
    val spi = Module(new SPI(mode, 2))
    spi.io.mem <> io.mem
    spi.io.miso := spi.io.mosi
    io.sclk := spi.io.sclk
    io.mosi := spi.io.mosi
    io.cs := spi.io.cs
  }

  private class Driver(dut: Loopback, mode: Int = 0) {
    val port = dut.io.mem
    val samples = ArrayBuffer.empty[Int]
    var edges = 0
    private var lastClock = (mode & 2) != 0

    def step(cycles: Int = 1): Unit = {
      for (_ <- 0 until cycles) {
        dut.clock.step()
        val currentClock = dut.io.sclk.peek().litToBoolean
        if (currentClock != lastClock) {
          edges += 1
          val leading = currentClock != ((mode & 2) != 0)
          if (leading == ((mode & 1) == 0)) {
            samples += (if (dut.io.mosi.peek().litToBoolean) 1 else 0)
          }
        }
        lastClock = currentClock
      }
    }

    def initialize(): Unit = {
      port.req.valid.poke(false.B)
      port.req.bits.addr.poke(0.U)
      port.req.bits.write.poke(false.B)
      port.req.bits.wdata.poke(0.U)
      port.req.bits.wmask.poke(0.U)
      port.req.bits.ren.poke(false.B)
      port.resp.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(2)
      lastClock = (mode & 2) != 0
      edges = 0
      samples.clear()
      dut.io.sclk.expect(lastClock.B)
      dut.io.cs.expect(true.B)
      port.resp.valid.expect(false.B)
    }

    def present(addr: Int, write: Boolean = false, data: Int = 0, mask: Int = 15): Unit = {
      port.req.bits.addr.poke(addr.U)
      port.req.bits.write.poke(write.B)
      port.req.bits.wdata.poke(data.U)
      port.req.bits.wmask.poke(mask.U)
      port.req.bits.ren.poke((!write).B)
      port.req.valid.poke(true.B)
    }

    def request(addr: Int, write: Boolean = false, data: Int = 0, mask: Int = 15): Unit = {
      present(addr, write, data, mask)
      var waited = 0
      while (!port.req.ready.peek().litToBoolean && waited < 160) {
        step()
        waited += 1
      }
      port.req.ready.expect(true.B)
      step()
      port.req.valid.poke(false.B)
      port.resp.valid.expect(true.B)
      port.req.ready.expect(false.B)
    }

    def consume(expected: Int): Unit = {
      port.resp.valid.expect(true.B)
      port.resp.bits.expect(expected.U)
      port.resp.ready.poke(true.B)
      step()
      port.resp.ready.poke(false.B)
      port.resp.valid.expect(false.B)
    }

    def write(addr: Int, data: Int, mask: Int = 15): Unit = {
      request(addr, write = true, data = data, mask = mask)
      consume(0)
    }

    def read(addr: Int): Int = {
      request(addr)
      val result = port.resp.bits.peek().litValue.toInt
      consume(result)
      result
    }

    def awaitReady(): Int = {
      var status = read(8)
      var polls = 0
      while ((status & 3) != 3 && polls < 80) {
        status = read(8)
        polls += 1
      }
      assert((status & 3) == 3, "SPI não terminou a transferência e a recepção")
      status
    }
  }

  test("Os quatro modos serializam MSB primeiro, recebem em loopback e mantêm CS entre bytes") {
    for (mode <- 0 to 3) {
      simulate(new Loopback(mode)) { dut =>
        val driver = new Driver(dut, mode)
        driver.initialize()
        assert(driver.read(8) == 1)
        driver.write(12, 0)
        for (value <- Seq(0xa5, 0x3c)) {
          driver.write(0, value)
          assert(driver.awaitReady() == 3)
          dut.io.cs.expect(false.B)
          assert(driver.read(4) == value)
          assert(driver.read(4) == 0)
        }
        assert(driver.edges == 32)
        assert(driver.samples.toSeq == Seq(0xa5, 0x3c).flatMap(v => (7 to 0 by -1).map(i => (v >> i) & 1)))
        driver.write(12, 1)
        dut.io.cs.expect(true.B)
      }
    }
  }

  test("Resposta bloqueada e mudanças em req não repetem TX nem alteram a resposta capturada") {
    simulate(new Loopback(0)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      driver.request(0, write = true, data = 0xa5)
      driver.present(0, write = true, data = 0x3c)
      driver.step(100)
      driver.port.req.ready.expect(false.B)
      driver.port.resp.valid.expect(true.B)
      driver.port.resp.bits.expect(0.U)
      assert(driver.edges == 16)
      driver.port.req.valid.poke(false.B)
      driver.consume(0)
      assert(driver.read(4) == 0xa5)

      driver.write(0, 0x3c)
      driver.request(8)
      driver.port.resp.bits.expect(0.U)
      driver.step(100)
      driver.port.resp.bits.expect(0.U)
      driver.consume(0)
      assert(driver.read(8) == 3)
      assert(driver.read(4) == 0x3c)
    }
  }

  test("TX e CS aguardam o fim do byte; leitura pendente não perde a próxima recepção") {
    simulate(new Loopback(0)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      driver.write(12, 0)
      driver.write(0, 0x55)
      driver.present(0, write = true, data = 0xb6)
      driver.port.req.ready.expect(false.B)
      var waited = 0
      while (!driver.port.req.ready.peek().litToBoolean && waited < 100) {
        driver.step()
        waited += 1
      }
      assert(waited > 0 && waited < 100)
      driver.step()
      driver.port.req.valid.poke(false.B)
      driver.consume(0)
      driver.request(4)
      driver.port.resp.bits.expect(0x55.U)
      driver.step(100)
      driver.port.resp.bits.expect(0x55.U)
      driver.consume(0x55)
      assert(driver.read(8) == 3)
      assert(driver.read(4) == 0xb6)

      driver.write(0, 0x96)
      driver.present(12, write = true, data = 1)
      driver.port.req.ready.expect(false.B)
      waited = 0
      while (!driver.port.req.ready.peek().litToBoolean && waited < 100) {
        dut.io.cs.expect(false.B)
        driver.step()
        waited += 1
      }
      assert(waited > 0 && waited < 100)
      driver.step()
      driver.port.req.valid.poke(false.B)
      driver.consume(0)
      dut.io.cs.expect(true.B)
      assert(driver.edges == 48)
    }
  }

  test("Máscaras, permissões e endereço completo impedem escritas e transmissões indevidas") {
    simulate(new Loopback(0)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      for ((address, mask) <- Seq((0, 0), (0, 2), (4, 1), (0x100, 1), (0x104, 1), (1, 1))) {
        driver.write(address, 0xff, mask)
      }
      driver.write(12, 0, mask = 2)
      driver.write(0x10c, 0)
      assert(driver.read(12) == 1)
      assert(driver.read(0x10c) == 0)
      assert(driver.read(0) == 0)
      assert(driver.read(4) == 0)
      driver.step(100)
      assert(driver.edges == 0)
      assert(driver.read(8) == 1)
    }
  }

  test("Overflow preserva o byte não lido; STATUS limpa por máscara e reset cancela a operação") {
    simulate(new Loopback(0)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      driver.write(0, 0x3c)
      assert(driver.awaitReady() == 3)
      driver.write(0, 0xa7)
      assert(driver.awaitReady() == 7)
      assert(driver.read(4) == 0x3c)
      assert(driver.read(8) == 5)
      driver.write(8, 4, mask = 2)
      assert(driver.read(8) == 5)
      driver.write(8, 4, mask = 1)
      assert(driver.read(8) == 1)
      driver.write(12, 0)
      driver.request(0, write = true, data = 0)
      driver.step(4)
      driver.initialize()
      driver.step(100)
      assert(driver.edges == 0)
      assert(driver.read(8) == 1)
      assert(driver.read(4) == 0)
      driver.write(0, 0x5a)
      assert(driver.awaitReady() == 3)
      assert(driver.read(4) == 0x5a)
    }
  }
}
