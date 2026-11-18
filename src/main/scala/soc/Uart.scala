package soc

import chisel3._
import chisel3.util._
import chisel.lib.uart.{Rx, Tx}

object Uart {
  val RxData = 0x00
  val TxData = 0x08
  val Status = 0x10
  val RxReadyBit = 0
  val OverflowBit = 1
  val BusyBit = 9
}

/** Fixed-baud UART adapter. Addresses are local offsets from DataDecoder.
  * STATUS: bit 0 = RX ready, bit 1 = RX overflow, bit 9 = TX busy.
  * Reading RX_DATA consumes the stored byte. Writing STATUS bit 1 clears overflow.
  */
class Uart(frequencyHz: Int, baudRate: Int, printChars: Boolean = false) extends Module {
  require(frequencyHz > 0 && baudRate > 0 && frequencyHz.toLong >= 2L * baudRate)

  val io = IO(new Bundle {
    val mem = Flipped(new MemoryPort)
    val tx = Output(Bool())
    val rx = Input(Bool())
  })

  private val transmitter = Module(new Tx(frequencyHz, baudRate))
  private val receiver = Module(new Rx(frequencyHz, baudRate))
  private val active = !reset.asBool

  private val txData = Reg(UInt(8.W))
  private val txPending = RegInit(false.B)
  private val txBusy = RegInit(false.B)
  transmitter.io.channel.bits := txData
  transmitter.io.channel.valid := active && txPending
  io.tx := transmitter.io.txd.asBool

  when(transmitter.io.channel.fire) {
    txPending := false.B
  }
  when(txBusy && !txPending && transmitter.io.channel.ready) {
    txBusy := false.B
  }

  private val rxData = Reg(UInt(8.W))
  private val rxReady = RegInit(false.B)
  private val rxOverflow = RegInit(false.B)
  receiver.io.rxd := io.rx.asUInt
  receiver.io.channel.ready := active

  private val pending = RegInit(false.B)
  private val response = Reg(UInt(32.W))
  private val request = io.mem.req.bits
  private val writeTx = request.write && request.addr === Uart.TxData.U && request.wmask(0)
  private val readRx = !request.write && request.addr === Uart.RxData.U
  private val readRxFire = io.mem.req.fire && readRx
  private val status = (txBusy.asUInt << Uart.BusyBit) |
    (rxOverflow.asUInt << Uart.OverflowBit) | (rxReady.asUInt << Uart.RxReadyBit)

  io.mem.req.ready := active && !pending && (!writeTx || !txBusy)
  io.mem.resp.valid := active && pending
  io.mem.resp.bits := response

  when(io.mem.req.fire) {
    pending := true.B
    response := 0.U
    when(!request.write && request.addr === Uart.Status.U) {
      response := status
    }
    when(readRx) {
      response := Mux(rxReady, rxData, 0.U)
      rxReady := false.B
    }
    when(writeTx) {
      txData := request.wdata(7, 0)
      txPending := true.B
      txBusy := true.B
    }
    when(request.write && request.addr === Uart.Status.U &&
      request.wmask(0) && request.wdata(Uart.OverflowBit)) {
      rxOverflow := false.B
    }
  }

  when(receiver.io.channel.fire) {
    when(rxReady && !readRxFire) {
      rxOverflow := true.B
    }.otherwise {
      rxData := receiver.io.channel.bits
      rxReady := true.B
    }
  }

  when(io.mem.resp.fire) {
    pending := false.B
  }

  if (printChars) {
    when(transmitter.io.channel.fire) {
      printf("%c", transmitter.io.channel.bits)
    }
  }
}
