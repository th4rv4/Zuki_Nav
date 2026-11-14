package com.th4rv4.zukinav.thermal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** The live [ThermalSession], kept in memory while the app runs, for the Thermals tab. */
object Thermals {
    val session = ThermalSession()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Adds a reading; returns the alerts it crossed. */
    @Synchronized
    fun onReading(tempC: Int, now: Long, warmUpC: Int, hotC: Int): Set<ThermalSession.Event> {
        val events = session.onReading(tempC, now, warmUpC, hotC)
        main.post { listeners.forEach { it() } }
        return events
    }
}
