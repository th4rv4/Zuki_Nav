package com.th4rv4.zukinav.thermal

/**
 * Engine temperature over the current ride: the last 30 minutes of
 * readings for the Temp Monitor, the ride's highest and average, time spent
 * at or above the heat warning, and when the engine warmed up. A gap of 3
 * minutes with no reading (ignition off) starts a new ride.
 *
 * [onReading] also says when to show the dash alerts; whether they are
 * switched on is up to the caller.
 */
class ThermalSession {

    enum class Event { WARMED_UP, OVERHEAT }

    data class Sample(val at: Long, val tempC: Int)

    private val window = ArrayDeque<Sample>()

    var startAt = 0L
        private set
    var lastAt = 0L
        private set
    var lastTempC: Int? = null
        private set
    var maxC: Int? = null
        private set
    private var minC = Int.MAX_VALUE
    private var sum = 0L
    private var count = 0
    var aboveHotMs = 0L
        private set
    var warmedUpAt: Long? = null
        private set
    private var lastOverheatAt: Long? = null

    val samples: List<Sample> get() = window.toList()
    val averageC: Int? get() = if (count > 0) Math.round(sum.toDouble() / count).toInt() else null

    /** Time from the start of the ride to warm-up, or null if it hasn't (or started warm). */
    val warmUpMs: Long? get() = warmedUpAt?.let { it - startAt }

    fun onReading(tempC: Int, now: Long, warmUpC: Int, hotC: Int): Set<Event> {
        if (lastAt == 0L || now - lastAt > GAP_MS) reset(now)
        val prev = lastTempC
        if (prev != null && hotC > 0 && prev >= hotC) aboveHotMs += (now - lastAt).coerceIn(0, MAX_STEP_MS)

        window.addLast(Sample(now, tempC))
        while (window.isNotEmpty() && now - window.first().at > WINDOW_MS) window.removeFirst()
        maxC = maxOf(maxC ?: tempC, tempC)
        minC = minOf(minC, tempC)
        sum += tempC
        count++
        lastAt = now
        lastTempC = tempC

        val events = HashSet<Event>()
        // Warmed up: once per ride, only if the ride started below the limit.
        if (warmUpC > 0 && warmedUpAt == null && minC < warmUpC && tempC >= warmUpC) {
            warmedUpAt = now
            events += Event.WARMED_UP
        }
        // Overheat: on reaching the limit, again every 5 minutes while it stays
        // there, and ready again once the engine has cooled a few degrees.
        if (hotC > 0) {
            if (tempC >= hotC) {
                val last = lastOverheatAt
                if (last == null || now - last >= OVERHEAT_REPEAT_MS) {
                    lastOverheatAt = now
                    events += Event.OVERHEAT
                }
            } else if (tempC <= hotC - COOL_MARGIN_C) {
                lastOverheatAt = null
            }
        }
        return events
    }

    private fun reset(now: Long) {
        window.clear()
        startAt = now
        maxC = null
        minC = Int.MAX_VALUE
        sum = 0
        count = 0
        aboveHotMs = 0
        warmedUpAt = null
        lastOverheatAt = null
        lastTempC = null
    }

    companion object {
        const val WINDOW_MS = 30 * 60_000L
        const val GAP_MS = 3 * 60_000L
        const val OVERHEAT_REPEAT_MS = 5 * 60_000L
        const val COOL_MARGIN_C = 3
        private const val MAX_STEP_MS = 10_000L
    }
}
