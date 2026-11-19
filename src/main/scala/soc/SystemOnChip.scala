package soc

import math._
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class SystemOnChip(val map: SocMemoryMap = MemoryMaps.quantum) extends Module {

  val io = IO(new Bundle {
    val tx = Output(Bool())
    val rx = Input(Bool())
    val pinsIn = Input(UInt(8.W))
    val pinsOut = Output(UInt(8.W))
    val pinsOe = Output(UInt(8.W))
  })
  
  val progWords = HexWords.load(
    sys.env.getOrElse("CC_PROGROM", "../../benchmarks/build/hex/dhrystone.PROGROM.hex"),
    16384
  )

  val gpio = Module(new GPIO)

  val spi = Module(new SPI)

  val dataRam = Module(new Ram(16384))
  
  val progRam = Module(new Ram(16384))

  val bootRom = Module(new Rom(progWords))

  val uart = Module(new Uart(
    sys.env.getOrElse("CC_CLOCK_HZ", "50000000").toInt,
    sys.env.getOrElse("CC_BAUD", "115200").toInt
  ))
  io.tx := uart.io.tx
  uart.io.rx := io.rx

  val dataDec = Module(new DataDecoder(map))
  dataDec.io.data <> dataRam.io
  dataDec.io.uart <> uart.io.mem
  dataDec.io.gpio <> gpio.io.mem
  dataDec.io.spi <> spi.io.mem

  val instDec = Module(new InstructionDecoder(map))

  val bootromArb = Module(new MemoryArbiter)
  bootromArb.io.inA <> dataDec.io.bootrom
  bootromArb.io.inB <> instDec.io.bootrom
  bootRom.io <> bootromArb.io.out

  val programArb = Module(new MemoryArbiter)
  programArb.io.inA <> dataDec.io.program
  programArb.io.inB <> instDec.io.program
  progRam.io <> programArb.io.out

  val cpu = Module(new TorvCore)
  instDec.io.cpu <> cpu.io.imem
  dataDec.io.cpu <> cpu.io.dmem

  val spiSelected = gpio.io.funcSel(0)

  val spiOut = Cat(
    spi.io.cs, 0.U(1.W), spi.io.mosi, spi.io.sclk
  )

  val outSelected = Mux(
    spiSelected, spiOut, gpio.io.pinsOut(3,0)
  )

  val oeSelected = Mux(
    spiSelected, "b1011".U(4.W), gpio.io.pinsOe(3,0)
  )

  io.pinsOut := Cat(gpio.io.pinsOut(7,4), outSelected)
  io.pinsOe  := Cat(gpio.io.pinsOe(7,4), oeSelected)

  gpio.io.pinsIn := io.pinsIn
  spi.io.miso := io.pinsIn(2)

}

object SystemOnChip extends App {
  ChiselStage.emitSystemVerilogFile(
    new SystemOnChip(
      if (sys.env.get("CC_SIM_BENCHMARK").contains("1")) MemoryMaps.benchmark
      else MemoryMaps.simpler
    ),
    args = args,
    firtoolOpts = Array(
      "-disable-all-randomization", 
      "-strip-debug-info", 
      "-default-layer-specialization=enable"
    )
  )
}
