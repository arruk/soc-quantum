package spimemory

import chisel3._
import chisel3.util._
import soc.{MemoryMaps, SocMemoryMap, SystemOnChip}
import _root_.circt.stage.ChiselStage

/** Synthesizable test system. The SPI source is internal to the FPGA;
  * the application's PROGRAM/DATARAM contents must be loaded by firmware.
  */
class SystemWithSpiRom(
  imagePath: String,
  depthBytes: Int = 131072,
  map: SocMemoryMap = MemoryMaps.default
) extends Module {
  val io = IO(new Bundle {
    val rx = Input(Bool())
    val tx = Output(Bool())
    val pinsIn = Input(UInt(8.W))
    val pinsOut = Output(UInt(8.W))
    val pinsOe = Output(UInt(8.W))
  })

  val soc = Module(new SystemOnChip(map, initializeRamFromFile = false))
  val spiRom = Module(new SpiRom(imagePath, depthBytes))

  soc.io.rx := io.rx
  io.tx := soc.io.tx
  io.pinsOut := soc.io.pinsOut
  io.pinsOe := soc.io.pinsOe

  // Disconnected GPIO outputs must not accidentally select the SPI source.
  spiRom.io.cs := !soc.io.pinsOe(3) || soc.io.pinsOut(3)
  spiRom.io.sclk := soc.io.pinsOe(0) && soc.io.pinsOut(0)
  spiRom.io.mosi := soc.io.pinsOe(1) && soc.io.pinsOut(1)

  private val externalInputs = io.pinsIn & ~soc.io.pinsOe
  private val readback = soc.io.pinsOut & soc.io.pinsOe
  private val miso = Mux(spiRom.io.misoOe, spiRom.io.miso, io.pinsIn(2))
  soc.io.pinsIn := ((externalInputs | readback) & "hfb".U(8.W)) |
    (Mux(soc.io.pinsOe(2), soc.io.pinsOut(2), miso).asUInt << 2)
}

object SystemWithSpiRom extends App {
  ChiselStage.emitSystemVerilogFile(
    new SystemWithSpiRom(sys.env("CC_SPI_IMAGE"), sys.env.getOrElse("CC_SPI_BYTES", "131072").toInt),
    args = args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
      "-lowering-options=disallowLocalVariables")
  )
}
