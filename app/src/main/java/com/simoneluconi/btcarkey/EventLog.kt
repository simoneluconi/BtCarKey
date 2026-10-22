package com.simoneluconi.btcarkey

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Event log shown in the app and mirrored to logcat (tag "BtCarKey"). Also kept in a file in the
 * app's storage, so a session at the car can be read afterwards at the PC: logcat only holds a few
 * minutes. Pull it with `scripts\pull-log.cmd`.
 */
object EventLog {
    private const val TAG = "BtCarKey"
    private const val MAX_LINES = 400
    /** Each file holds 512 KB; the current one and two older ones are kept. */
    private const val MAX_FILE_BYTES = 512L * 1024
    private const val KEPT_FILES = 3
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    /** Where the time starts in a [dateTimeFormat] stamp: the app's log shows the time only. */
    private const val TIME_START = 11

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    /** The latest lines, oldest first. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "event-log").apply { isDaemon = true } }
    private val queued = AtomicInteger()

    // The open file, on the writer thread only: one open per file, not one per line
    private var out: Writer? = null
    private var outBytes = 0L

    /** Starts the file copy: files/log/events.txt. */
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "log").apply { mkdirs() }.resolve("events.txt")
    }

    /** The log files, oldest first, for sharing. */
    fun files(): List<File> {
        val dir = file?.parentFile ?: return emptyList()
        return ((KEPT_FILES - 1 downTo 1).map { dir.resolve("events.$it.txt") } + dir.resolve("events.txt")).filter { it.exists() }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val stamp = LocalDateTime.now().format(dateTimeFormat)
        val line = "${stamp.substring(TIME_START)} $message"
        synchronized(_lines) {
            val next = _lines.value + line
            _lines.value = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
        }
        file?.let { target ->
            queued.incrementAndGet()
            writer.execute { append(target, "$stamp $message\n") }
        }
    }

    fun clearScreen() {
        synchronized(_lines) { _lines.value = emptyList() }
    }

    private fun append(target: File, text: String) {
        try {
            if (outBytes > MAX_FILE_BYTES) {
                runCatching { out?.close() }
                out = null
                rotate(target)
            }
            val writer = out ?: FileOutputStream(target, true).bufferedWriter().also {
                out = it
                outBytes = target.length()
            }
            writer.write(text)
            outBytes += text.length
            // Flushed as soon as no other line waits: in the file within milliseconds, a burst costs one write
            if (queued.decrementAndGet() == 0) writer.flush()
        } catch (_: Exception) {
            queued.set(0)
            runCatching { out?.close() }
            out = null
        }
    }

    /** events.txt -> events.1.txt -> ... ; the oldest goes. */
    private fun rotate(target: File) {
        val dir = target.parentFile ?: return
        dir.resolve("events.${KEPT_FILES - 1}.txt").delete()
        for (i in KEPT_FILES - 2 downTo 1) dir.resolve("events.$i.txt").renameTo(dir.resolve("events.${i + 1}.txt"))
        target.renameTo(dir.resolve("events.1.txt"))
    }
}
