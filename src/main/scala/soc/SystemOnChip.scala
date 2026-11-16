package soc

import math._
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class SystemOnChip extends Module {

}

object SystemOnChip extends App {
  ChiselStage.emitSystemVerilogFile(
    new SystemOnChip,
    args = args,
    firtoolOpts = Array(
      "-disable-all-randomization", 
      "-strip-debug-info", 
      "-default-layer-specialization=enable"
    )
  )
}
