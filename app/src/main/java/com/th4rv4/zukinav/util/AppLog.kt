package com.th4rv4.zukinav.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's log: shown in Troubleshooting, mirrored to logcat under
 * "ZukiNav", and kept in a file per day for the last [KEEP_DAYS] days, so it
 * survives the app being closed, the phone restarting or an update.
 */
object AppLog {

    /** Lines kept in memory for the log screen. */
    private const val MAX = 3000

    /** Days of log files kept on the phone. */
    const val KEEP_DAYS = 3

    /** A day's file stops growing past this; a ride's worth is far less. */
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Where the files go, once known; lines before that wait in [pending]. */
    @Volatile private var dir: File? = null
    private val pending = ArrayList<Pair<Long, String>>()
    private val writer: Handler by lazy { Handler(HandlerThread("ZukiNavLog").apply { start() }.looper) }
    private val toWrite = ArrayList<Pair<Long, String>>()
    private var flushQueued = false

    fun d(tag: String, msg: String) {
        Log.d("ZukiNav", "[$tag] $msg")
        val now = System.currentTimeMillis()
        val line = "${synchronized(fmt) { fmt.format(Date(now)) }} [$tag] $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            if (dir == null) pending += now to line else queue(now, line)
        }
        main.post { listeners.forEach { it() } }
    }

    /**
     * Starts writing to files. Safe to call from every entry point (the
     * service, the screens, the notification listener); only the first call
     * does anything.
     */
    fun attach(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.applicationContext.filesDir, "logs").apply { mkdirs() }
        synchronized(lines) {
            if (dir != null) return
            // What was logged before this run, so the log screen still shows it.
            val earlier = try {
                todayFile(d).takeIf { it.exists() }?.readLines()?.takeLast(MAX).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            val now = ArrayList(lines)
            lines.clear()
            earlier.forEach { lines.addLast(it) }
            now.forEach { lines.addLast(it) }
            while (lines.size > MAX) lines.removeFirst()
            dir = d
            pending.forEach { (at, l) -> queue(at, l) }
            pending.clear()
        }
        writer.post { prune(d) }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /**
     * The whole kept log, oldest first, a header before each day: for
     * sharing. Falls back to what is in memory.
     */
    fun full(): String {
        val d = dir ?: return snapshot().joinToString("\n")
        flushNow()
        val files = d.listFiles { f -> f.name.startsWith("log-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return snapshot().joinToString("\n")
        return files.joinToString("\n") { f ->
            "===== " + f.name.removePrefix("log-").removeSuffix(".txt") + " =====\n" + f.readText().trimEnd()
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    // ---- files ---------------------------------------------------------------

    private fun todayFile(d: File, at: Long = System.currentTimeMillis()) =
        File(d, "log-" + synchronized(dayFmt) { dayFmt.format(Date(at)) } + ".txt")

    /** Holds [lines] lock. Writes in batches, a second apart, off the main thread. */
    private fun queue(at: Long, line: String) {
        synchronized(toWrite) {
            toWrite += at to line
            if (flushQueued) return
            flushQueued = true
        }
        writer.postDelayed({ flush() }, 1_000L)
    }

    private fun flushNow() {
        val done = java.util.concurrent.CountDownLatch(1)
        writer.post { flush(); done.countDown() }
        try {
            done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun flush() {
        val d = dir ?: return
        val batch = synchronized(toWrite) {
            flushQueued = false
            ArrayList(toWrite).also { toWrite.clear() }
        }
        if (batch.isEmpty()) return
        try {
            // Lines go to the file of the day they were logged on.
            batch.groupBy { todayFile(d, it.first) }.forEach { (file, day) ->
                if (file.length() < MAX_FILE_BYTES) file.appendText(day.joinToString("") { it.second + "\n" })
            }
        } catch (e: Exception) {
            Log.d("ZukiNav", "Could not write the log: $e")
        }
    }

    /** Only the last [KEEP_DAYS] days stay. */
    private fun prune(d: File) {
        val files = d.listFiles { f -> f.name.startsWith("log-") && f.name.endsWith(".txt") }?.sortedBy { it.name } ?: return
        files.dropLast(KEEP_DAYS).forEach { it.delete() }
    }
}
