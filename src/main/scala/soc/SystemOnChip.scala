package soc

import math._
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class SystemOnChip(val map: SocMemoryMap = MemoryMaps.quantum) extends Module {

  val io = IO(new Bundle {
    val tx = Output(Bool())
    val rx = Input(Bool())
    val pins_io = Output(UInt(8.W))
  })
  
  val progWords = HexWords.load(
    sys.env.getOrElse("CC_PROGROM", "../../benchmarks/build/hex/dhrystone.PROGROM.hex"),
    16384
  )

  val dataRam = Module(new Ram(16384))
  val bootRom = Module(new Rom(progWords))

  val uart = Module(new Uart(
    sys.env.getOrElse("CC_CLOCK_HZ", "50000000").toInt,
    sys.env.getOrElse("CC_BAUD", "115200").toInt
  ))
  io.tx := uart.io.tx
  uart.io.rx := io.rx

  val dataDec = Module(new DataDecoder(map))
  dataDec.io.bootrom <> bootRom.io
  dataDec.io.data <> dataRam.io
  dataDec.io.uart <> uart.io.mem

  val instDec = Module(new InstructionDecoder(map))
  instDec.io.bootrom <> bootRom.io

  val cpu = Module(new TorvCore)
  instDec.io.cpu <> cpu.io.imem
  dataDec.io.cpu <> cpu.io.dmem

  io.pins_io := 0.U(8.W)

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
