package soc

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class UartSpec extends AnyFunSuite {
  private val cyclesPerBit = 16
  private val frameCycles = cyclesPerBit * 11
  private val maxTxCycles = frameCycles + 3 * cyclesPerBit

  private class Driver(dut: Uart) {
    val port = dut.io.mem

    def initialize(): Unit = {
      dut.io.rx.poke(true.B)
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
      port.resp.valid.expect(false.B)
      port.req.ready.expect(true.B)
      dut.io.tx.expect(true.B)
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
      port.req.ready.expect(true.B)
      dut.clock.step()
      port.req.valid.poke(false.B)
      port.resp.valid.expect(true.B)
      port.req.ready.expect(false.B)
    }

    def consume(expected: Int): Unit = {
      port.resp.bits.expect(expected.U)
      port.resp.ready.poke(true.B)
      dut.clock.step()
      port.resp.ready.poke(false.B)
      port.resp.valid.expect(false.B)
    }

    def read(addr: Int): BigInt = {
      request(addr)
      val value = port.resp.bits.peek().litValue
      consume(value.toInt)
      value
    }

    def awaitStart(): Unit = {
      var waited = 0
      while (dut.io.tx.peek().litToBoolean && waited < cyclesPerBit + 2) {
        dut.clock.step()
        waited += 1
      }
      dut.io.tx.expect(false.B)
    }

    def sendRx(value: Int): Unit = {
      val bits = Seq(0) ++ (0 until 8).map(i => (value >> i) & 1) ++ Seq(1, 1)
      bits.foreach { bit =>
        dut.io.rx.poke((bit != 0).B)
        dut.clock.step(cyclesPerBit)
      }
    }

    def expectFrame(value: Int): Unit = {
      awaitStart()
      val bits = Seq(0) ++ (0 until 8).map(i => (value >> i) & 1) ++ Seq(1, 1)
      bits.foreach { bit =>
        dut.io.tx.expect((bit != 0).B)
        dut.clock.step(cyclesPerBit)
      }
      dut.io.tx.expect(true.B)
    }
  }

  test("TX serializa 8N2 uma vez, mesmo com a resposta bloqueada e valid mantido") {
    simulate(new Uart(1600, 100)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      driver.request(Uart.TxData, write = true, data = 0xa5)
      driver.present(Uart.TxData, write = true, data = 0x3c)
      driver.expectFrame(0xa5)
      for (_ <- 0 until frameCycles) {
        dut.io.tx.expect(true.B)
        driver.port.req.ready.expect(false.B)
        driver.port.resp.valid.expect(true.B)
        driver.port.resp.bits.expect(0.U)
        dut.clock.step()
      }
      driver.port.req.valid.poke(false.B)
      driver.consume(0)
      driver.request(Uart.TxData, write = true, data = 0x3c)
      driver.expectFrame(0x3c)
      driver.consume(0)
    }
  }

  test("STATUS usa busy no bit 9 e retém o valor da leitura até consumir a resposta") {
    simulate(new Uart(1600, 100)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      assert(driver.read(Uart.Status) == 0)
      driver.request(Uart.TxData, write = true, data = 0x41)
      driver.consume(0)
      driver.request(Uart.Status)
      driver.port.resp.bits.expect((1 << Uart.BusyBit).U)
      dut.clock.step(maxTxCycles)
      driver.port.resp.bits.expect((1 << Uart.BusyBit).U)
      driver.consume(1 << Uart.BusyBit)
      assert(driver.read(Uart.Status) == 0)
    }
  }

  test("Uma segunda escrita espera o transmissor livre e preserva o próximo byte") {
    simulate(new Uart(1600, 100)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      driver.request(Uart.TxData, write = true, data = 0x55)
      driver.consume(0)
      driver.present(Uart.TxData, write = true, data = 0xb6)
      driver.port.req.ready.expect(false.B)
      var waited = 0
      while (!driver.port.req.ready.peek().litToBoolean && waited <= maxTxCycles) {
        driver.port.resp.valid.expect(false.B)
        dut.clock.step()
        waited += 1
      }
      assert(waited > 0 && waited <= maxTxCycles)
      dut.clock.step()
      driver.port.req.valid.poke(false.B)
      driver.port.resp.valid.expect(true.B)
      driver.expectFrame(0xb6)
      driver.consume(0)
    }
  }

  test("Somente a máscara do byte baixo em TX_DATA produz uma transmissão") {
    simulate(new Uart(1600, 100)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      for ((address, mask) <- Seq((Uart.TxData, 0), (Uart.TxData, 2), (0, 15), (Uart.Status, 15))) {
        driver.request(address, write = true, data = 0xff, mask = mask)
        driver.consume(0)
        for (_ <- 0 until frameCycles) {
          dut.io.tx.expect(true.B)
          dut.clock.step()
        }
        assert(driver.read(Uart.Status) == 0)
      }
    }
  }

  test("Reset cancela a transmissão e a resposta pendente") {
    simulate(new Uart(1600, 100)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      driver.request(Uart.TxData, write = true, data = 0)
      driver.awaitStart()
      driver.initialize()
      assert(driver.read(Uart.Status) == 0)
      driver.request(Uart.TxData, write = true, data = 0x5a)
      driver.expectFrame(0x5a)
      driver.consume(0)
    }
  }

  test("O fluxo legado store TX_DATA e polling STATUS termina para bytes consecutivos") {
    simulate(new Uart(1600, 100, printChars = true)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      for (character <- "OK\n") {
        driver.request(Uart.TxData, write = true, data = character.toInt)
        driver.consume(0)
        var busy = true
        var polls = 0
        while (busy && polls < frameCycles) {
          busy = (driver.read(Uart.Status) & (1 << Uart.BusyBit)) != 0
          polls += 1
        }
        assert(!busy, "putchar permaneceu bloqueado em STATUS")
      }
    }
  }

  test("RX_DATA retém o byte e uma resposta bloqueada não perde o próximo byte recebido") {
    simulate(new Uart(1600, 100)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      dut.clock.step(2 * cyclesPerBit)
      driver.sendRx(0xa5)
      assert(driver.read(Uart.Status) == (1 << Uart.RxReadyBit))
      driver.request(Uart.RxData)
      driver.port.resp.bits.expect(0xa5.U)
      driver.sendRx(0x5a)
      driver.port.resp.valid.expect(true.B)
      driver.port.resp.bits.expect(0xa5.U)
      driver.consume(0xa5)
      assert(driver.read(Uart.Status) == (1 << Uart.RxReadyBit))
      assert(driver.read(Uart.RxData) == 0x5a)
      assert(driver.read(Uart.Status) == 0)
      assert(driver.read(Uart.RxData) == 0)
    }
  }

  test("Overflow preserva o primeiro byte e só é limpo por escrita no byte correto de STATUS") {
    simulate(new Uart(1600, 100)) { dut =>
      val driver = new Driver(dut)
      driver.initialize()
      dut.clock.step(2 * cyclesPerBit)
      driver.sendRx(0x3c)
      driver.sendRx(0xa7)
      assert(driver.read(Uart.Status) == 3)
      assert(driver.read(Uart.RxData) == 0x3c)
      assert(driver.read(Uart.Status) == (1 << Uart.OverflowBit))
      driver.request(Uart.Status, write = true, data = 1 << Uart.OverflowBit, mask = 2)
      driver.consume(0)
      assert(driver.read(Uart.Status) == (1 << Uart.OverflowBit))
      driver.request(Uart.Status, write = true, data = 1 << Uart.OverflowBit, mask = 1)
      driver.consume(0)
      assert(driver.read(Uart.Status) == 0)
    }
  }
}
