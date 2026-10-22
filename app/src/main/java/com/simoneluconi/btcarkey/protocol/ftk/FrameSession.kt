package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.protocol.LinkClosedException
import com.simoneluconi.btcarkey.protocol.NoAnswerException
import com.simoneluconi.btcarkey.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Sends a request and suspends until its answer (or throws on timeout / disconnection). */
fun interface BoxChannel {
    suspend fun request(cmd: Int, key: Int, payload: ByteArray): Frame
}

/**
 * Request/answer matching over one connection: each request gets the next sequence id and waits
 * for the frame that echoes it. At most [MAX_IN_FLIGHT] requests wait at once, as in the official
 * app. Frames nobody waits for (INFO_SYNC / INFO_REPORT the box sends on its own, late answers)
 * go to [onUnsolicited].
 *
 * Pure Kotlin: [write] sends bytes (the BLE layer splits them to the MTU), [receive] takes
 * notifications in.
 */
class FrameSession(
    private val write: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
    private val onUnsolicited: (Frame) -> Unit = {},
) : BoxChannel {
    private val assembler = FrameAssembler { log("RX dropped ${it.size} stray bytes: ${it.toHex(" ")}") }
    private val pending = HashMap<Int, Pending>()
    private val inFlight = Semaphore(MAX_IN_FLIGHT)
    private var nextSeq = 0

    @Volatile
    private var closed = false

    private class Pending(val cmd: Int, val answer: CompletableDeferred<Frame>)

    override suspend fun request(cmd: Int, key: Int, payload: ByteArray): Frame =
        request(cmd, key, payload, DEFAULT_TIMEOUT_MS)

    suspend fun request(cmd: Int, key: Int, payload: ByteArray, timeoutMs: Long): Frame = inFlight.withPermit {
        if (closed) throw LinkClosedException()
        val answer = CompletableDeferred<Frame>()
        val seq: Int
        synchronized(pending) {
            seq = nextSeq
            nextSeq = (nextSeq + 1) and 0xFFFF
            pending[seq] = Pending(cmd, answer)
        }
        val bytes = Frame.encode(seq, cmd, key, payload)
        log("TX ${Cmd.name(cmd)}/$key seq=$seq ${describePayload(cmd, key, payload)}")
        try {
            write(bytes)
            withTimeout(timeoutMs) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            throw NoAnswerException("No answer to ${Cmd.name(cmd)}/$key within $timeoutMs ms")
        } finally {
            synchronized(pending) { pending.remove(seq) }
        }
    }

    /** One notification from the box. */
    fun receive(chunk: ByteArray) {
        for (frame in assembler.push(chunk)) {
            log("RX $frame ${describePayload(frame.cmd, frame.key, frame.payload)}")
            val waiting = synchronized(pending) {
                pending[frame.seq]?.takeIf { it.cmd == frame.cmd && frame.type != Frame.TYPE_REPORT }
                    ?.also { pending.remove(frame.seq) }
            }
            if (waiting != null) waiting.answer.complete(frame) else onUnsolicited(frame)
        }
    }

    /** Fails every waiting request: the connection is gone. */
    fun close() {
        closed = true
        val all = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        all.forEach { it.answer.completeExceptionally(LinkClosedException()) }
        assembler.reset()
    }

    companion object {
        const val MAX_IN_FLIGHT = 5
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Payload for the log; key material (SET/VERIFY) is never written out. */
        fun describePayload(cmd: Int, key: Int, payload: ByteArray): String = when {
            payload.isEmpty() -> ""
            cmd == Cmd.AUTH && (key == AuthKey.SET_DEVICE_KEY || key == AuthKey.VERIFY_DEVICE_KEY) && payload.size >= 16 ->
                "[${payload.size} bytes of key material]"
            payload.size > 64 -> payload.copyOfRange(0, 64).toHex(" ") + " …(${payload.size} bytes)"
            else -> payload.toHex(" ")
        }
    }
}
