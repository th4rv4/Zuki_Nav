package com.th4rv4.zukinav.trips

import com.th4rv4.zukinav.protocol.BikeData

/**
 * Builds trips from the dash's 5-second readings.
 *
 * The ride goes on while readings keep coming, whatever the speed. Only the
 * dash going quiet (the key turned off) can end it, once it has stayed quiet
 * for [OFF_LIMIT_MS], or [ROUTE_OFF_LIMIT_MS] while a Maps route is open.
 *
 * Inside a ride, a halt becomes a listed stop when it lasts:
 *  - [HALT_STOP_MS] or more at 0 km/h with the key on (a long jam, a wait),
 *  - [KEY_OFF_STOP_MS] or more with the key off (fuel, tea, photos).
 * Shorter halts (signals, traffic) only count as time not moving. A stop
 * runs from when the bike came to rest until it moves again, so switching
 * off, on and idling is one stop.
 *
 * Nothing before the bike first moves is a stop. If the engine warmed up in
 * that time (at least [WARM_UP_MIN_MS] and [WARM_UP_RISE_C] hotter), it is
 * shown as Engine Warm Up; otherwise the ride starts with the first move.
 *
 * Distance comes from Trip A (0.1 km steps). If Trip A is reset during the
 * ride, the ODO covers that stretch instead.
 *
 * Not thread safe: call it from one thread.
 */
class TripRecorder {

    /** The ride in progress. */
    data class Active(
        val start: Long,
        val lastAt: Long,
        val startOdo: Int,
        val lastOdo: Int,
        val lastTripA: Double,
        val lastSpeed: Int,
        val distanceKm: Double,
        val movingMs: Long,
        val topSpeed: Int,
        val startLat: Double? = null,
        val startLng: Double? = null,
        val endLat: Double? = null,
        val endLng: Double? = null,
        /** Engine temperature: highest, and the sum and count for the average. */
        val engineMax: Int? = null,
        val engineSum: Long = 0,
        val engineCount: Int = 0,
        /** When the bike came to rest (first 0 km/h reading), while it still is. */
        val restSince: Long? = null,
        val restLat: Double? = null,
        val restLng: Double? = null,
        /** The riding part since the last stop. */
        val legStart: Long = start,
        val legStartKm: Double = 0.0,
        val legStartMovingMs: Long = 0,
        val legTop: Int = 0,
        /** Finished parts and the stops between them. */
        val legs: List<Leg> = emptyList(),
        val stops: List<Stop> = emptyList(),
        /** When the bike first moved, and the engine temperature at switch-on and then. */
        val movedAt: Long? = null,
        val tempStart: Int? = null,
        val tempAtMove: Int? = null,
        /** The key went off during the current rest: it becomes a stop once the bike moves. */
        val keyOffStop: Boolean = false,
        /** Switched back on during that rest: when, and the engine temperature then and since, all at rest. */
        val keyOnAt: Long? = null,
        val keyOnTemp: Int? = null,
        val restTemp: Int? = null,
    ) {
        /** Warmed up before moving off: long enough, and the engine got hotter. */
        val warmedUp: Boolean
            get() = movedAt != null && movedAt - start >= WARM_UP_MIN_MS &&
                tempStart != null && tempAtMove != null && tempAtMove - tempStart >= WARM_UP_RISE_C

        fun toTrip(): Trip {
            val moved = movedAt
            val allLegs = (if (stops.isEmpty()) emptyList() else legs + currentLeg(lastAt)).mapIndexed { i, l ->
                // The first part starts when the bike moved, not at switch-on.
                if (i == 0 && moved != null) l.copy(start = maxOf(l.start, moved)) else l
            }
            val warm = warmedUp
            return Trip(
                start = start,
                end = lastAt,
                distanceKm = Math.round(distanceKm * 10) / 10.0,
                movingSeconds = movingMs / 1000,
                topSpeedKmh = topSpeed,
                startOdo = startOdo,
                endOdo = lastOdo,
                startLat = startLat,
                startLng = startLng,
                endLat = endLat,
                endLng = endLng,
                maxEngineC = engineMax,
                avgEngineC = if (engineCount > 0) Math.round(engineSum.toDouble() / engineCount).toInt() else null,
                stops = stops.size,
                stopSeconds = stops.sumOf { it.seconds },
                legs = allLegs,
                stopList = stops,
                movedAt = moved,
                warmUp = warm,
                warmFromC = if (warm) tempStart else null,
                warmToC = if (warm) tempAtMove else null,
            )
        }

        fun currentLeg(end: Long) = Leg(
            start = legStart,
            end = end,
            distanceKm = Math.round((distanceKm - legStartKm) * 10) / 10.0,
            movingSeconds = (movingMs - legStartMovingMs) / 1000,
            topSpeedKmh = legTop,
        )

        fun encode(): String = listOf(
            start, lastAt, startOdo, lastOdo, lastTripA, lastSpeed, distanceKm, movingMs, topSpeed,
            startLat ?: "", startLng ?: "", endLat ?: "", endLng ?: "",
            engineMax ?: "", engineSum, engineCount,
            restSince ?: "", restLat ?: "", restLng ?: "",
            legStart, legStartKm, legStartMovingMs, legTop,
            legs.joinToString("|") { "${it.start},${it.end},${it.distanceKm},${it.movingSeconds},${it.topSpeedKmh}" },
            stops.joinToString("|") { "${it.arrive},${it.depart},${it.lat ?: ""},${it.lng ?: ""},${it.warmUpSeconds}" },
            movedAt ?: "", tempStart ?: "", tempAtMove ?: "", keyOffStop,
            keyOnAt ?: "", keyOnTemp ?: "", restTemp ?: "",
        ).joinToString(";")

        companion object {
            fun decode(s: String?): Active? {
                val p = s?.split(';') ?: return null
                // 13 fields at first, 16 with engine temperature, 25 with stops, 32 with warm-ups.
                if (p.size != 13 && p.size != 16 && p.size != 25 && p.size != 32) return null
                return try {
                    val start = p[0].toLong()
                    Active(
                        start, p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toDouble(),
                        p[5].toInt(), p[6].toDouble(), p[7].toLong(), p[8].toInt(),
                        p[9].toDoubleOrNull(), p[10].toDoubleOrNull(), p[11].toDoubleOrNull(), p[12].toDoubleOrNull(),
                        engineMax = p.getOrNull(13)?.toIntOrNull(),
                        engineSum = p.getOrNull(14)?.toLongOrNull() ?: 0,
                        engineCount = p.getOrNull(15)?.toIntOrNull() ?: 0,
                        restSince = p.getOrNull(16)?.toLongOrNull(),
                        restLat = p.getOrNull(17)?.toDoubleOrNull(),
                        restLng = p.getOrNull(18)?.toDoubleOrNull(),
                        legStart = p.getOrNull(19)?.toLongOrNull() ?: start,
                        legStartKm = p.getOrNull(20)?.toDoubleOrNull() ?: 0.0,
                        legStartMovingMs = p.getOrNull(21)?.toLongOrNull() ?: 0,
                        legTop = p.getOrNull(22)?.toIntOrNull() ?: p[8].toInt(),
                        legs = p.getOrNull(23).orEmpty().split('|').filter { it.isNotBlank() }.map { l ->
                            val f = l.split(',')
                            Leg(f[0].toLong(), f[1].toLong(), f[2].toDouble(), f[3].toLong(), f[4].toInt())
                        },
                        stops = p.getOrNull(24).orEmpty().split('|').filter { it.isNotBlank() }.map { l ->
                            val f = l.split(',')
                            Stop(
                                f[0].toLong(), f[1].toLong(), f.getOrNull(2)?.toDoubleOrNull(), f.getOrNull(3)?.toDoubleOrNull(),
                                warmUpSeconds = f.getOrNull(4)?.toLongOrNull() ?: 0,
                            )
                        },
                        // Rides in progress from before: treated as already moving.
                        movedAt = if (p.size >= 32) p[25].toLongOrNull() else start,
                        tempStart = p.getOrNull(26)?.toIntOrNull(),
                        tempAtMove = p.getOrNull(27)?.toIntOrNull(),
                        keyOffStop = p.getOrNull(28) == "true",
                        keyOnAt = p.getOrNull(29)?.toLongOrNull(),
                        keyOnTemp = p.getOrNull(30)?.toIntOrNull(),
                        restTemp = p.getOrNull(31)?.toIntOrNull(),
                    )
                } catch (_: RuntimeException) {
                    null
                }
            }
        }
    }

    var active: Active? = null

    /** True if the last [onReading] started a new trip. */
    var justStarted = false
        private set

    /**
     * Adds a reading. [routeOpen] says whether a Maps route is open now, and
     * [here] is where the bike is, if known (for naming stops). Returns the
     * previous trip if this reading comes after the key was off long enough
     * to end it.
     */
    fun onReading(
        d: BikeData,
        now: Long,
        routeOpen: Boolean = false,
        here: Pair<Double, Double>? = null,
        /** The dash was lost while riding since the last reading: the gap was riding, not a stop. */
        gapWasRiding: Boolean = false,
        /** Longest step between readings that still counts as riding (longer in Zuki Sim at fast-forward). */
        maxStepMs: Long = MAX_STEP_MS,
    ): Trip? {
        val finished = finishIfIdle(now, routeOpen)
        var a = active
        justStarted = a == null
        if (a == null) {
            val e = d.engineTempC
            active = Active(
                now, now, d.odoKm, d.odoKm, d.tripAKm, d.speedKmh, 0.0, 0, d.speedKmh,
                engineMax = e, engineSum = e?.toLong() ?: 0, engineCount = if (e != null) 1 else 0,
                legTop = d.speedKmh,
                restSince = if (d.speedKmh == 0) now else null,
                restLat = here?.first, restLng = here?.second,
                movedAt = if (d.speedKmh > 0) now else null,
                tempStart = e,
                tempAtMove = if (d.speedKmh > 0) e else null,
            )
            return finished
        }

        val stepMs = now - a.lastAt
        if (a.movedAt == null) {
            // Not moved yet: nothing here is a stop.
            if (d.speedKmh > 0) a = a.copy(movedAt = now, tempAtMove = d.engineTempC ?: a.tempAtMove)
            else if (d.engineTempC != null) a = a.copy(tempAtMove = d.engineTempC, tempStart = a.tempStart ?: d.engineTempC)
        } else if (gapWasRiding) {
            // Not a stop: the dash dropped out while riding.
        } else {
            if (stepMs >= KEY_OFF_STOP_MS) {
                // The key was off a while: a stop from when the bike came to
                // rest, until it moves again.
                a = a.copy(
                    restSince = a.restSince ?: a.lastAt,
                    restLat = a.restLat ?: a.endLat ?: here?.first,
                    restLng = a.restLng ?: a.endLng ?: here?.second,
                    keyOffStop = true,
                    // Back on: a warm-up, if any, is measured from here, only while at rest.
                    keyOnAt = if (d.speedKmh == 0) now else null,
                    keyOnTemp = if (d.speedKmh == 0) d.engineTempC else null,
                    restTemp = if (d.speedKmh == 0) d.engineTempC else null,
                )
            } else if (d.speedKmh == 0 && a.keyOnAt != null && d.engineTempC != null) {
                a = a.copy(keyOnTemp = a.keyOnTemp ?: d.engineTempC, restTemp = d.engineTempC)
            }
            val rest = a.restSince
            if (d.speedKmh > 0 && rest != null && (a.keyOffStop || now - rest >= HALT_STOP_MS)) {
                // Warmed up again after the key was off: at rest the whole time
                // (any movement would have ended the stop), long enough, hotter.
                val on = a.keyOnAt
                val from = a.keyOnTemp
                val to = a.restTemp
                val warm = if (on != null && from != null && to != null && now - on >= WARM_UP_MIN_MS && to - from >= WARM_UP_RISE_C) {
                    (now - on) / 1000
                } else {
                    0L
                }
                a = addStop(a, rest, now, a.restLat ?: here?.first, a.restLng ?: here?.second, warm)
            }
        }

        val moving = gapWasRiding || ((d.speedKmh > 0 || a.lastSpeed > 0) && stepMs <= maxStepMs)
        val atRest = d.speedKmh == 0
        active = a.copy(
            lastAt = now,
            lastOdo = d.odoKm,
            lastTripA = d.tripAKm,
            lastSpeed = d.speedKmh,
            distanceKm = a.distanceKm + step(a, d, if (gapWasRiding) maxOf(MAX_STEP_KM, stepMs * MAX_KMH / 3_600_000.0) else MAX_STEP_KM),
            movingMs = a.movingMs + if (moving) stepMs.coerceAtLeast(0) else 0,
            topSpeed = maxOf(a.topSpeed, d.speedKmh),
            legTop = maxOf(a.legTop, d.speedKmh),
            engineMax = d.engineTempC?.let { maxOf(it, a.engineMax ?: it) } ?: a.engineMax,
            engineSum = a.engineSum + (d.engineTempC ?: 0),
            engineCount = a.engineCount + if (d.engineTempC != null) 1 else 0,
            restSince = if (atRest) a.restSince ?: now else null,
            restLat = if (atRest) (if (a.restSince != null) a.restLat else here?.first) else null,
            restLng = if (atRest) (if (a.restSince != null) a.restLng else here?.second) else null,
        )
        return finished
    }

    /** The key off for longer than the limit ([routeOpen] lengthens it): the trip ends. */
    fun finishIfIdle(now: Long, routeOpen: Boolean = false): Trip? {
        val a = active ?: return null
        return if (now - a.lastAt >= offLimit(routeOpen)) finish() else null
    }

    /** Ends the trip now: End Ride, or recording turned off. */
    fun finish(): Trip? {
        val a = active ?: return null
        active = null
        return a.toTrip()
    }

    fun setStartPlace(lat: Double, lng: Double) {
        active = active?.copy(startLat = lat, startLng = lng)
    }

    fun setEndPlace(lat: Double, lng: Double) {
        active = active?.copy(endLat = lat, endLng = lng)
    }

    private fun addStop(a: Active, arrive: Long, depart: Long, lat: Double?, lng: Double?, warmUpSeconds: Long = 0): Active {
        val legEnd = arrive.coerceIn(a.legStart, a.lastAt)
        return a.copy(
            legs = a.legs + a.currentLeg(legEnd),
            stops = a.stops + Stop(arrive = arrive, depart = depart, lat = lat, lng = lng, warmUpSeconds = warmUpSeconds),
            legStart = depart,
            legStartKm = a.distanceKm,
            legStartMovingMs = a.movingMs,
            legTop = 0,
            restSince = null,
            restLat = null,
            restLng = null,
            keyOffStop = false,
            keyOnAt = null,
            keyOnTemp = null,
            restTemp = null,
        )
    }

    private fun step(a: Active, d: BikeData, maxKm: Double): Double {
        val byTripA = d.tripAKm - a.lastTripA
        if (byTripA in 0.0..maxKm) return byTripA
        // Trip A was reset, or the reading jumped: use the ODO for this stretch.
        val byOdo = (d.odoKm - a.lastOdo).toDouble()
        return if (byOdo in 0.0..maxKm) byOdo else 0.0
    }

    companion object {
        /** The key off this long ends the ride. */
        const val OFF_LIMIT_MS = 10 * 60_000L

        /** ...or this long while a Maps route is open (a long break on a long ride). */
        const val ROUTE_OFF_LIMIT_MS = 2 * 60 * 60_000L

        /** The key off at least this long is listed as a stop. */
        const val KEY_OFF_STOP_MS = 2 * 60_000L

        /** At 0 km/h with the key on at least this long is listed as a stop. */
        const val HALT_STOP_MS = 5 * 60_000L

        /**
         * Engine Warm Up: idling at least this long before moving off, and
         * the engine this much hotter. A running engine gains about 20 °C a
         * minute from cold; a dash that is only switched on stays flat.
         */
        const val WARM_UP_MIN_MS = 60_000L
        const val WARM_UP_RISE_C = 10

        fun offLimit(routeOpen: Boolean) = if (routeOpen) ROUTE_OFF_LIMIT_MS else OFF_LIMIT_MS

        /** Readings come every 5 s; a longer step is a dropout and does not count as moving. */
        const val MAX_STEP_MS = 15_000L

        /** Farther than this between two readings is not real riding. */
        private const val MAX_STEP_KM = 5.0

        /** Across a dash dropout while riding, no faster than this on average. */
        private const val MAX_KMH = 150.0
    }
}
