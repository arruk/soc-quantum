package soc

import chisel3._
import chisel3.util._
import chisel3.experimental.{ExtModule, IntParam}

class BBSpi(spiMode: Int = 0, clksPerHalfBit: Int = 2) extends ExtModule(Map(
  "SPI_MODE" -> IntParam(spiMode),
  "CLKS_PER_HALF_BIT" -> IntParam(clksPerHalfBit)
)) {
  override def desiredName = "SPI_Master"

  val i_Clk = IO(Input(Clock()))
  val i_Rst_L = IO(Input(Bool()))

  val i_TX_Byte = IO(Input(UInt(8.W)))
  val i_TX_DV = IO(Input(Bool()))
  val o_TX_Ready = IO(Output(Bool()))
  val o_RX_Byte = IO(Output(UInt(8.W)))
  val o_RX_DV = IO(Output(Bool()))

  val o_SPI_Clk = IO(Output(Bool()))
  val o_SPI_MOSI = IO(Output(Bool()))
  val i_SPI_MISO = IO(Input(Bool()))

  addPath("src/main/resources/vsrc/spi/SPI_Master.v")
}

// Local offsets: TX_DATA 0x00 (W), RX_DATA 0x04 (R), STATUS 0x08 (R/W), CS 0x0C (R/W).
// STATUS: bit 0 TX_READY, bit 1 RX_READY, bit 2 RX_OVERFLOW (write 1 to clear).
// RX_DATA consumes the buffered byte; overflow preserves an unread byte.
// CS bit 0 is the physical, active-low level: reset = 1 (inactive).
class SPI(spiMode: Int = 0, clksPerHalfBit: Int = 2) extends Module {
  require(spiMode >= 0 && spiMode <= 3)
  require(clksPerHalfBit >= 2)

  val io = IO(new Bundle {
    val mem = Flipped(new MemoryPort)
    val sclk = Output(Bool())
    val mosi = Output(Bool())
    val miso = Input(Bool())
    val cs = Output(Bool())
  })

  val spi = Module(new BBSpi(spiMode, clksPerHalfBit))
  spi.i_Clk := clock
  spi.i_Rst_L := !reset.asBool
  spi.i_SPI_MISO := io.miso
  spi.i_TX_Byte := io.mem.req.bits.wdata(7,0)

  val rxReg = RegInit(0.U(8.W))
  val rxReadyReg = RegInit(false.B)
  val overflowReg = RegInit(false.B)
  val csReg = RegInit(true.B)

  val txAddr     = 0x00.U
  val rxAddr     = 0x04.U
  val statusAddr = 0x08.U
  val csAddr     = 0x0C.U

  val idle :: responding :: Nil = Enum(2)
  val state = RegInit(idle)
  val response = Reg(UInt(8.W))
  val index = io.mem.req.bits.addr

  val txWrite = io.mem.req.bits.write && io.mem.req.bits.wmask(0) && index === txAddr
  val csWrite = io.mem.req.bits.write && io.mem.req.bits.wmask(0) && index === csAddr
  val rxRead = io.mem.req.fire && !io.mem.req.bits.write && index === rxAddr
  val status = Cat(overflowReg, rxReadyReg, spi.o_TX_Ready)

  val responseData = MuxLookup(index, 0.U(8.W))(Seq(
    rxAddr -> Mux(rxReadyReg, rxReg, 0.U),
    statusAddr -> status,
    csAddr -> csReg.asUInt
  ))

  // TX and CS writes wait for the last serial clock edge to finish.
  io.mem.req.ready := state === idle && !reset.asBool &&
    (!(txWrite || csWrite) || spi.o_TX_Ready)
  io.mem.resp.valid := state === responding && !reset.asBool
  io.mem.resp.bits := response
  spi.i_TX_DV := io.mem.req.fire && txWrite

  when(io.mem.req.fire) {
    state := responding
    response := Mux(io.mem.req.bits.write, 0.U, responseData)

    when(io.mem.req.bits.write && io.mem.req.bits.wmask(0)) {
      switch(index) {
        is(statusAddr) { when(io.mem.req.bits.wdata(2)) { overflowReg := false.B } }
        is(csAddr)     { csReg := io.mem.req.bits.wdata(0) }
      }
    }

    when(rxRead) {
      rxReadyReg := false.B
    }
  }

  // A simultaneous read consumes the old byte and leaves room for the new one.
  when(spi.o_RX_DV && !reset.asBool) {
    when(rxReadyReg && !rxRead) {
      overflowReg := true.B
    }.otherwise {
      rxReg := spi.o_RX_Byte
      rxReadyReg := true.B
    }
  }

  when(io.mem.resp.fire) {
    state := idle
  }

  io.sclk := spi.o_SPI_Clk
  io.mosi := spi.o_SPI_MOSI
  io.cs := csReg
}
