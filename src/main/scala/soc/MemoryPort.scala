package soc

import chisel3._
import chisel3.util.Decoupled

class MemoryRequest extends Bundle {
  val addr  = UInt(32.W)
  val write = Bool()
  val wdata = UInt(32.W)
  val wmask = UInt(4.W)
  val ren = Bool()
}

class MemoryPort extends Bundle {
  val req  = Decoupled(new MemoryRequest)
  val resp = Flipped(Decoupled(UInt(32.W)))
}
