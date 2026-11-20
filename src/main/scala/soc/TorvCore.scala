package soc

import chisel3._
import chisel3.experimental.{ExtModule, IntParam}

class BBCore(resetPC: BigInt = MemoryMaps.default.bootrom.base)
    extends ExtModule(Map("RESET_PC" -> IntParam(resetPC))) {
  override def desiredName = "torv32"

  val clk    = IO(Input(Clock()))
  val resetn = IO(Input(Bool()))

  val imem_en   = IO(Output(Bool()))
  val imem_addr = IO(Output(UInt(32.W)))
  val imem_data = IO(Input(UInt(32.W)))
  val imem_req_valid = IO(Output(Bool()))
  val imem_req_ready = IO(Input(Bool()))
  val imem_resp_valid = IO(Input(Bool()))
  val imem_resp_ready = IO(Output(Bool()))

  val mem_data  = IO(Input(UInt(32.W)))
  val mem_wmask = IO(Output(UInt(4.W)))
  val mem_addr  = IO(Output(UInt(32.W)))
  val mem_wdata = IO(Output(UInt(32.W)))
  val mem_ren = IO(Output(Bool()))
  val mem_req_valid = IO(Output(Bool()))
  val mem_req_ready = IO(Input(Bool()))
  val mem_resp_valid = IO(Input(Bool()))
  val mem_resp_ready = IO(Output(Bool()))

  // soc-quantum/
  addPath("../../rtl/cores/cc/cctorv5.v")
}

class TorvCore(resetPC: BigInt = MemoryMaps.default.bootrom.base) extends Module {
  val io = IO(new Bundle {
    val imem = new MemoryPort
    val dmem = new MemoryPort
  })

  val torv = Module(new BBCore(resetPC))
  torv.clk    := clock
  torv.resetn := !reset.asBool

  io.imem.req.bits.addr  := torv.imem_addr
  io.imem.req.bits.write := false.B
  io.imem.req.bits.wdata := 0.U(32.W)
  io.imem.req.bits.wmask := 0.U(4.W)
  io.imem.req.bits.ren   := torv.imem_en
  io.imem.req.valid      := torv.imem_req_valid
  torv.imem_req_ready    := io.imem.req.ready

  torv.imem_data       := io.imem.resp.bits
  torv.imem_resp_valid := io.imem.resp.valid
  io.imem.resp.ready  := torv.imem_resp_ready

  io.dmem.req.bits.addr  := torv.mem_addr
  io.dmem.req.bits.write := torv.mem_wmask.orR
  io.dmem.req.bits.wdata := torv.mem_wdata
  io.dmem.req.bits.wmask := torv.mem_wmask
  io.dmem.req.bits.ren   := torv.mem_ren
  io.dmem.req.valid      := torv.mem_req_valid
  torv.mem_req_ready     := io.dmem.req.ready

  torv.mem_data       := io.dmem.resp.bits
  torv.mem_resp_valid := io.dmem.resp.valid
  io.dmem.resp.ready  := torv.mem_resp_ready
}

