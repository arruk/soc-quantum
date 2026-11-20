package spimemory

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import soc.{MemoryPort, SPI}

class SpiRomSpec extends AnyFunSuite with ChiselSim {
  // ChiselSim's memory randomization would overwrite the readmemh contents.
  private def simulationSettings[T <: Module] = chisel3.simulator.Settings.default[T].copy(
    randomization = chisel3.simulator.Randomization.uninitialized
  )
  override implicit def commonSettingsModifications: svsim.CommonSettingsModifications =
    (settings: svsim.CommonCompilationSettings) => settings.copy(
      verilogPreprocessorDefines = settings.verilogPreprocessorDefines :+
        svsim.CommonCompilationSettings.VerilogPreprocessorDefine("ENABLE_INITIAL_MEM_")
    )

  private val contents = Vector.tabulate(1024)(i => ((i * 73) ^ (i >> 3) ^ 0xa5) & 255)

  private def withImage(test: String => Unit): Unit = {
    val file = Files.createTempFile("spi-rom-", ".hex")
    Files.writeString(file, contents.map(b => f"$b%02x").mkString("\n") + "\n")
    try test(file.toString) finally Files.delete(file)
  }

  private class Driver(dut: SpiRom, halfPeriod: Int = 2) {
    def initialize(): Unit = {
      dut.io.cs.poke(true.B)
      dut.io.sclk.poke(false.B)
      dut.io.mosi.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(4)
      dut.reset.poke(false.B)
      dut.clock.step(4)
      dut.io.misoOe.expect(false.B)
    }

    def select(): Unit = {
      dut.io.cs.poke(false.B)
      dut.clock.step(halfPeriod)
    }

    def deselect(): Unit = {
      dut.io.cs.poke(true.B)
      dut.io.misoOe.expect(false.B)
      dut.io.miso.expect(false.B)
      dut.io.sclk.poke(false.B)
      dut.clock.step(halfPeriod)
    }

    def bit(value: Boolean): Int = {
      dut.io.mosi.poke(value.B)
      dut.clock.step(halfPeriod)
      dut.io.sclk.poke(true.B)
      val result = if (dut.io.miso.peek().litToBoolean) 1 else 0
      dut.clock.step(halfPeriod)
      dut.io.sclk.poke(false.B)
      dut.clock.step(halfPeriod)
      result
    }

    def transfer(value: Int, pause: Int = 0): Int = {
      val result = (7 to 0 by -1).foldLeft(0)((rx, i) => (rx << 1) | bit(((value >> i) & 1) != 0))
      dut.clock.step(pause)
      result
    }

    def header(address: Int): Unit = {
      select()
      transfer(3)
      for (shift <- Seq(16, 8, 0)) transfer((address >> shift) & 255)
    }

    def read(address: Int, length: Int, pause: Int = 0): Seq[Int] = {
      header(address)
      val result = (0 until length).map(_ => transfer(0, pause))
      deselect()
      result
    }
  }

  test("03 reads sequential bytes, unaligned addresses and byte/page boundaries") {
    withImage { path =>
      simulate(new SpiRom(path, contents.size), settings = simulationSettings) { dut =>
        val driver = new Driver(dut)
        driver.initialize()
        for ((address, size) <- Seq((0, 12), (1, 3), (250, 20), (513, 9))) {
          assert(driver.read(address, size) == contents.slice(address, address + size))
        }
        assert(driver.read(0, 3, pause = 17) == contents.take(3))
      }
    }
  }

  test("Out-of-range and full 24-bit addresses return FF instead of aliasing the ROM") {
    withImage { path =>
      simulate(new SpiRom(path, contents.size), settings = simulationSettings) { dut =>
        val driver = new Driver(dut, halfPeriod = 3)
        driver.initialize()
        assert(driver.read(1022, 5) == contents.takeRight(2) ++ Seq.fill(3)(255))
        for (address <- Seq(1024, 0x010001, 0xfffffe)) {
          assert(driver.read(address, 4) == Seq.fill(4)(255))
        }
      }
    }
  }

  test("Unsupported opcodes, partial commands and CS cancellation restart the parser") {
    withImage { path =>
      simulate(new SpiRom(path, contents.size), settings = simulationSettings) { dut =>
        val driver = new Driver(dut)
        driver.initialize()
        driver.select()
        driver.transfer(2)
        for (_ <- 0 until 8) {
          assert(driver.transfer(0) == 0)
          dut.io.misoOe.expect(false.B)
        }
        driver.deselect()
        for (partialBits <- Seq(3, 8 + 11, 32 + 5)) {
          driver.select()
          for (i <- 0 until partialBits) driver.bit(i < 8 && ((3 >> (7 - i)) & 1) != 0)
          driver.deselect()
          assert(driver.read(37, 2) == contents.slice(37, 39))
        }
        driver.header(17)
        driver.transfer(0)
        driver.initialize() // Reset while CS is active.
        assert(driver.read(3, 4) == contents.slice(3, 7))
      }
    }
  }

  private class MasterAndRom(path: String, divider: Int) extends Module {
    val io = IO(new Bundle { val mem = Flipped(new MemoryPort) })
    val spi = Module(new SPI(0, divider))
    val rom = Module(new SpiRom(path, contents.size))
    spi.io.mem <> io.mem
    rom.io.cs := spi.io.cs
    rom.io.sclk := spi.io.sclk
    rom.io.mosi := spi.io.mosi
    spi.io.miso := rom.io.miso
  }

  test("The SoC's Nandland controller reads the ROM through MemoryPort at dividers 2 and 4") {
    withImage { path =>
      for (divider <- Seq(2, 4)) {
        simulate(new MasterAndRom(path, divider), settings = simulationSettings) { dut =>
          val port = dut.io.mem
          port.req.valid.poke(false.B)
          port.req.bits.addr.poke(0.U)
          port.req.bits.write.poke(false.B)
          port.req.bits.wdata.poke(0.U)
          port.req.bits.wmask.poke(1.U)
          port.req.bits.ren.poke(false.B)
          port.resp.ready.poke(false.B)
          dut.reset.poke(true.B)
          dut.clock.step(4)
          dut.reset.poke(false.B)
          dut.clock.step(4)

          def access(address: Int, write: Boolean = false, value: Int = 0): Int = {
            port.req.bits.addr.poke(address.U)
            port.req.bits.write.poke(write.B)
            port.req.bits.ren.poke((!write).B)
            port.req.bits.wdata.poke(value.U)
            port.req.valid.poke(true.B)
            var cycles = 0
            while (!port.req.ready.peek().litToBoolean && cycles < 500) {
              dut.clock.step()
              cycles += 1
            }
            port.req.ready.expect(true.B)
            dut.clock.step()
            port.req.valid.poke(false.B)
            port.resp.valid.expect(true.B)
            val result = port.resp.bits.peek().litValue.toInt
            port.resp.ready.poke(true.B)
            dut.clock.step()
            port.resp.ready.poke(false.B)
            result
          }

          def transfer(value: Int): Int = {
            access(0, write = true, value)
            var status = access(8)
            var polls = 0
            while ((status & 3) != 3 && polls < 500) {
              status = access(8)
              polls += 1
            }
            assert(status == 3, s"Unexpected SPI status: $status")
            access(4)
          }

          for (address <- Seq(0, 253, 517, 1022, 0x010001)) {
            access(12, write = true, 0)
            transfer(3)
            for (shift <- Seq(16, 8, 0)) transfer((address >> shift) & 255)
            val received = (0 until 7).map(_ => transfer(0))
            val expected = (address until address + 7).map(i => if (i < contents.size) contents(i) else 255)
            assert(received == expected, s"Read from $address: $received != $expected")
            access(12, write = true, 1)
          }
        }
      }
    }
  }
}
