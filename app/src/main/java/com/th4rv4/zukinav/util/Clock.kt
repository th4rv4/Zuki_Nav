package com.th4rv4.zukinav.util

import android.os.SystemClock

/**
 * The time rides, stops and alerts are judged by. On the real app this is
 * always the phone's clock. The Zuki Sim test build lets a simulated dash
 * run it faster, so a long ride with breaks can be tested in minutes.
 */
object Clock {

    @Volatile private var anchorReal = 0L
    @Volatile private var anchorVirtual = 0L
    @Volatile private var factor = 1.0
    @Volatile private var virtual = false

    /** Now, in ms since 1970. */
    fun now(): Long {
        if (!virtual) return System.currentTimeMillis()
        return anchorVirtual + ((SystemClock.elapsedRealtime() - anchorReal) * factor).toLong()
    }

    /** How long to wait in real time for [ms] of ride time to pass. */
    fun scaled(ms: Long): Long = if (!virtual || factor <= 1.0) ms else (ms / factor).toLong().coerceAtLeast(50L)

    /** Test builds only: ride time is [virtualNow] and runs [speed] times faster from here. */
    fun sync(virtualNow: Long, speed: Double) {
        anchorReal = SystemClock.elapsedRealtime()
        anchorVirtual = virtualNow
        factor = speed.coerceIn(1.0, 120.0)
        virtual = true
    }

    val speed: Double get() = if (virtual) factor else 1.0
}
