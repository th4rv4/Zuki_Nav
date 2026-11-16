package com.th4rv4.zukinav.trips

/**
 * How the route of a ride is recorded, to spend as little battery as possible:
 *  - a Maps route open: share the fixes Maps already gets (no extra GPS);
 *    if none come for [SHARED_WAIT_MS] while moving, own GPS for [FALLBACK_MS];
 *  - otherwise own GPS, every 2 s while moving and every 15 s once the dash
 *    has said 0 km/h twice in a row, back to 2 s on the first sign of moving;
 *  - nothing with the key off.
 */
class GpsPlan {

    enum class Mode(val intervalMs: Long) {
        OFF(0),
        SHARED(0),
        FAST(2_000),
        SLOW(15_000),
    }

    private var zeros = 0
    private var moving = false
    private var lastFixAt = 0L
    private var sharedSince = 0L
    private var fallbackUntil = 0L

    var mode = Mode.OFF
        private set

    /** A dash reading. */
    fun onReading(speedKmh: Int) {
        if (speedKmh > 0) {
            zeros = 0
            moving = true
        } else {
            zeros++
            if (zeros >= 2) moving = false
        }
    }

    /** A location fix, with the speed GPS measured if it has one. */
    fun onFix(now: Long, gpsKmh: Float?) {
        lastFixAt = now
        if (gpsKmh != null && gpsKmh > MOVING_GPS_KMH) {
            zeros = 0
            moving = true
        }
    }

    /** Works out the mode for now; returns true if it changed. */
    fun update(now: Long, keyOn: Boolean, routeOpen: Boolean): Boolean {
        val next = when {
            !keyOn -> Mode.OFF
            routeOpen && now < fallbackUntil -> Mode.FAST
            routeOpen -> {
                val waitingSince = maxOf(sharedSince, lastFixAt)
                if (mode == Mode.SHARED && moving && now - waitingSince >= SHARED_WAIT_MS) {
                    fallbackUntil = now + FALLBACK_MS
                    Mode.FAST
                } else {
                    Mode.SHARED
                }
            }
            moving -> Mode.FAST
            else -> Mode.SLOW
        }
        if (next == mode) return false
        if (next == Mode.SHARED) sharedSince = now
        mode = next
        return true
    }

    companion object {
        /** No shared fix this long while moving: Maps isn't getting any, use own GPS. */
        const val SHARED_WAIT_MS = 6_000L

        /** How long own GPS stays on before trying Maps' fixes again. */
        const val FALLBACK_MS = 60_000L

        /** GPS speed above this counts as moving, before the dash says so. */
        const val MOVING_GPS_KMH = 8f
    }
}
