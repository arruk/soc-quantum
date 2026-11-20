package spimemory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

/** Read-only SPI source: mode 0, 03 + 24-bit byte address, MSB first.
  * SCLK/MOSI/CS must come from a master in this same system-clock domain,
  * with at least two system-clock cycles per SCLK half-period.
  */
class SpiRom(imagePath: String, val depthBytes: Int = 131072) extends Module {
  require(depthBytes >= 2 && isPow2(depthBytes) && depthBytes <= (1 << 24))
  private val path = java.nio.file.Paths.get(imagePath).toAbsolutePath.normalize
  require(java.nio.file.Files.isRegularFile(path), s"Missing SPI byte HEX: $path")

  val io = IO(new Bundle {
    val cs = Input(Bool()) // Active low.
    val sclk = Input(Bool())
    val mosi = Input(Bool())
    val miso = Output(Bool())
    val misoOe = Output(Bool())
  })

  private val memory = SyncReadMem(depthBytes, UInt(8.W))
  loadMemoryFromFileInline(memory, path.toString)

  private val command :: address :: reading :: ignored :: Nil = Enum(4)
  private val state = RegInit(command)
  private val previousClock = RegNext(io.sclk, false.B)
  private val rising = io.sclk && !previousClock
  private val falling = !io.sclk && previousClock
  private val selected = !io.cs && !reset.asBool
  private val count = RegInit(0.U(5.W))
  private val commandShift = RegInit(0.U(8.W))
  private val addressShift = RegInit(0.U(24.W))
  // An extra bit prevents reads beyond the 24-bit range from aliasing address 0.
  private val nextAddress = RegInit(0.U(25.W))
  private val txShift = RegInit(0.U(8.W))
  private val nextByte = RegInit(0.U(8.W))
  private val misoReg = RegInit(false.B)
  private val receivedCommand = Cat(commandShift(6,0), io.mosi)
  private val receivedAddress = Cat(addressShift(22,0), io.mosi)

  // Prefetch on the last address/data rising edge. The following falling edge
  // presents bit 7 of the next byte, without adding a dummy byte to command 03.
  private val fetch = selected && rising &&
    ((state === address && count === 23.U) || (state === reading && count === 7.U))
  private val fetchAddress = Mux(state === address, Cat(0.U(1.W), receivedAddress), nextAddress)
  private val inRange = fetchAddress < depthBytes.U
  private val readData = memory.read(fetchAddress(log2Ceil(depthBytes) - 1, 0), fetch && inRange)
  private val fetched = RegNext(fetch, false.B)
  private val fetchedInRange = RegNext(inRange, false.B)

  when(fetched && selected && state === reading) {
    nextByte := Mux(fetchedInRange, readData, 255.U)
  }

  when(io.cs) {
    state := command
    count := 0.U
    commandShift := 0.U
    addressShift := 0.U
    misoReg := false.B
  }.otherwise {
    when(rising) {
      switch(state) {
        is(command) {
          commandShift := receivedCommand
          count := count + 1.U
          when(count === 7.U) {
            state := Mux(receivedCommand === 3.U, address, ignored)
            count := 0.U
          }
        }
        is(address) {
          addressShift := receivedAddress
          count := count + 1.U
          when(count === 23.U) {
            state := reading
            count := 0.U
          }
        }
        is(reading) {
          count := Mux(count === 7.U, 0.U, count + 1.U)
        }
      }
    }

    when(fetch) {
      nextAddress := Mux(fetchAddress === (1 << 24).U, fetchAddress, fetchAddress + 1.U)
    }

    when(falling && state === reading) {
      when(count === 0.U) {
        misoReg := nextByte(7)
        txShift := nextByte
      }.otherwise {
        misoReg := txShift(6)
        txShift := Cat(txShift(6,0), 0.U(1.W))
      }
    }
  }

  io.misoOe := selected && state === reading
  io.miso := io.misoOe && misoReg
}

object SpiRom extends App {
  ChiselStage.emitSystemVerilogFile(
    new SpiRom(sys.env("CC_SPI_IMAGE"), sys.env.getOrElse("CC_SPI_BYTES", "131072").toInt),
    args = args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
      "-lowering-options=disallowLocalVariables")
  )
}
