package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.protocol.putU16
import com.simoneluconi.btcarkey.protocol.sum16
import com.simoneluconi.btcarkey.protocol.u16
import com.simoneluconi.btcarkey.protocol.u32
import com.simoneluconi.btcarkey.protocol.u8

/**
 * One protocol frame: a 16-byte little-endian header followed by the payload.
 *
 * | off | field                                      |
 * |-----|--------------------------------------------|
 * | 0   | magic: 55 AA app->device, AA 55 device->app |
 * | 2   | version (0)                                |
 * | 3   | type (low nibble) / channel (high nibble)  |
 * | 4   | sequence id, echoed by the answer          |
 * | 6   | cmd                                        |
 * | 8   | key (sub-command)                          |
 * | 10  | payload length                             |
 * | 12  | payload checksum (byte sum, 16 bits)       |
 * | 14  | header checksum (sum of bytes 0..13)       |
 */
class Frame(
    val type: Int,
    val channel: Int,
    val seq: Int,
    val cmd: Int,
    val key: Int,
    val payload: ByteArray,
    /** False when a checksum didn't match: kept and logged, the official app doesn't check them either. */
    val checksumsOk: Boolean = true,
) {
    /** First 4 payload bytes as the uint32 errorCode most answers carry, -1 when too short. */
    val errorCode: Long get() = if (payload.size >= 4) payload.u32(0) else -1L

    override fun toString(): String =
        "${Cmd.name(cmd)}/$key seq=$seq type=$type len=${payload.size}" + if (checksumsOk) "" else " (bad checksum)"

    companion object {
        const val HEADER_SIZE = 16
        const val TYPE_REQUEST = 0
        const val TYPE_RESPONSE = 1
        const val TYPE_REQUEST_NO_ANSWER = 2
        const val TYPE_REPORT = 3

        /** Builds an app->device request (type 0, channel 0). */
        fun encode(seq: Int, cmd: Int, key: Int, payload: ByteArray = ByteArray(0)): ByteArray {
            val out = ByteArray(HEADER_SIZE + payload.size)
            out[0] = 0x55
            out[1] = 0xAA.toByte()
            out[2] = 0 // version
            out[3] = TYPE_REQUEST.toByte()
            out.putU16(4, seq and 0xFFFF)
            out.putU16(6, cmd)
            out.putU16(8, key)
            out.putU16(10, payload.size)
            out.putU16(12, payload.sum16())
            out.putU16(14, out.sum16(0, 14))
            payload.copyInto(out, HEADER_SIZE)
            return out
        }

        /** Reads a complete frame (header + exactly its payload). */
        fun decode(bytes: ByteArray): Frame {
            require(bytes.size >= HEADER_SIZE) { "Frame shorter than its header" }
            val length = bytes.u16(10)
            require(bytes.size >= HEADER_SIZE + length) { "Frame shorter than its payload" }
            val payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
            val ok = bytes.u16(14) == bytes.sum16(0, 14) && bytes.u16(12) == payload.sum16()
            return Frame(
                type = bytes.u8(3) and 0x0F,
                channel = bytes.u8(3) ushr 4,
                seq = bytes.u16(4),
                cmd = bytes.u16(6),
                key = bytes.u16(8),
                payload = payload,
                checksumsOk = ok,
            )
        }
    }
}

/**
 * Rebuilds frames from notifications: a frame longer than the MTU arrives in pieces, and the
 * frame is complete once the buffer holds 16 + length bytes. Bytes that don't start with a
 * magic are dropped up to the next one, so one garbled notification can't wedge the stream.
 */
class FrameAssembler(private val onDiscard: (ByteArray) -> Unit = {}) {
    private var buffer = ByteArray(0)

    /** Adds a notification; returns the frames it completed (often one, sometimes none). */
    fun push(chunk: ByteArray): List<Frame> {
        buffer += chunk
        val frames = mutableListOf<Frame>()
        while (true) {
            val start = findMagic()
            if (start < 0) {
                // Keep a trailing 0xAA: it may be the first half of the next magic
                val keep = if (buffer.isNotEmpty() && buffer.last() == 0xAA.toByte()) 1 else 0
                if (buffer.size > keep) onDiscard(buffer.copyOfRange(0, buffer.size - keep))
                buffer = buffer.copyOfRange(buffer.size - keep, buffer.size)
                return frames
            }
            if (start > 0) {
                onDiscard(buffer.copyOfRange(0, start))
                buffer = buffer.copyOfRange(start, buffer.size)
            }
            if (buffer.size < Frame.HEADER_SIZE) return frames
            val total = Frame.HEADER_SIZE + buffer.u16(10)
            if (buffer.size < total) return frames
            frames += Frame.decode(buffer.copyOfRange(0, total))
            buffer = buffer.copyOfRange(total, buffer.size)
        }
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    private fun findMagic(): Int {
        for (i in 0 until buffer.size - 1) {
            val a = buffer[i]
            val b = buffer[i + 1]
            // Device->app is AA 55; app->device 55 AA is accepted too (some firmwares echo it)
            if ((a == 0xAA.toByte() && b == 0x55.toByte()) || (a == 0x55.toByte() && b == 0xAA.toByte())) return i
        }
        return -1
    }
}
