package com.th4rv4.gixxernav.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory log shown on the main screen, mirrored to logcat under "GixxerNav". */
object AppLog {

    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) {
        Log.d("GixxerNav", "[$tag] $msg")
        val line = "${synchronized(fmt) { fmt.format(Date()) }} [$tag] $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        main.post { listeners.forEach { it() } }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
