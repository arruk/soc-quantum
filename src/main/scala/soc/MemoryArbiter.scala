package soc

import chisel3._
import chisel3.util._

class MemoryArbiter extends Module {
  val io = IO(new Bundle {
    val inA = Flipped(new MemoryPort)
    val inB = Flipped(new MemoryPort)
    val out = new MemoryPort
  })

  val idle :: requisition :: waitResponse :: Nil = Enum(3)
  val state = RegInit(idle)
  val reqOwner = Reg(Bool())

  io.out.req.valid := false.B
  io.inA.req.ready := false.B
  io.inB.req.ready := false.B

  io.out.resp.ready := false.B
  io.inA.resp.valid := false.B
  io.inB.resp.valid := false.B

  io.inA.resp.bits := io.out.resp.bits
  io.inB.resp.bits := io.out.resp.bits

  when(state === idle) {
    when(io.inA.req.valid) {
      state := requisition
      reqOwner := true.B
    }.elsewhen(io.inB.req.valid) {
      state := requisition
      reqOwner := false.B
    }
  }

  when(state === requisition) {
    io.out.req.valid := Mux(reqOwner, io.inA.req.valid, io.inB.req.valid)
    when(reqOwner) {
      when(!io.inA.req.valid) {
        state := idle
      }
      io.inA.req.ready := io.out.req.ready
    }.otherwise {
      when(!io.inB.req.valid) {
        state := idle
      }
      io.inB.req.ready := io.out.req.ready
    }
  }

  when(io.out.req.fire) {
    state := waitResponse
  }

  when(state === waitResponse) {
    io.out.resp.ready := Mux(reqOwner, io.inA.resp.ready, io.inB.resp.ready)

    when(reqOwner) {
      io.inA.resp.valid := io.out.resp.valid
      io.inB.resp.valid := false.B
    }.otherwise {
      io.inA.resp.valid := false.B
      io.inB.resp.valid := io.out.resp.valid
    }
  }

  when(io.out.resp.fire) {
    state := idle
  }

  io.out.req.bits := Mux(reqOwner, io.inA.req.bits, io.inB.req.bits)

}


