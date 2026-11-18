package soc

import chisel3._
import chisel3.util._

class GPIO extends Module {
  val io = IO(new Bundle {
    val mem = Flipped(new MemoryPort)
    val pinsIn = Input(UInt(8.W))
    val pinsOut = Output(UInt(8.W))
    val pinsOe = Output(UInt(8.W))
    val funcSel = Output(UInt(4.W))
  })

  val inputMeta = RegNext(io.pinsIn, 0.U(8.W))
  val inputSync = RegNext(inputMeta, 0.U(8.W))

  val outReg = RegInit(0.U(8.W))
  val outEnReg = RegInit(0.U(8.W))
  val funcSelReg = RegInit(0.U(4.W))

  val inAddr      = 0x00.U
  val outAddr     = 0x04.U
  val outEnAddr   = 0x08.U
  val funcSelAddr = 0x0C.U

  val idle :: responding :: Nil = Enum(2)
  val state = RegInit(idle)
  val response = Reg(UInt(8.W))
  val index = io.mem.req.bits.addr

  val responseData = MuxLookup(index, 0.U(8.W))(Seq(
    inAddr -> inputSync,
    outAddr -> outReg,
    outEnAddr -> outEnReg,
    funcSelAddr -> funcSelReg
    ))

  io.mem.req.ready := state === idle && !reset.asBool
  io.mem.resp.valid := state === responding && !reset.asBool
  io.mem.resp.bits := response

  when(io.mem.req.fire) {

    state := responding
    response := Mux(io.mem.req.bits.write, 0.U, responseData)

    when(io.mem.req.bits.write && io.mem.req.bits.wmask(0)) {
      switch(index) {
        is(outAddr)     { outReg := io.mem.req.bits.wdata(7,0) }
        is(outEnAddr)   { outEnReg := io.mem.req.bits.wdata(7,0) }
        is(funcSelAddr) { funcSelReg := io.mem.req.bits.wdata(3,0) }
      }
    }
  }
 
  when(io.mem.resp.fire) {
    state := idle
  }
 
  io.pinsOut := outReg
  io.pinsOe := outEnReg
  io.funcSel := funcSelReg

}

