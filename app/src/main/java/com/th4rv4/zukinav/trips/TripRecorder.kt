package com.th4rv4.zukinav.trips

import com.th4rv4.zukinav.protocol.BikeData

/**
 * Builds trips from the dash's 5-second readings. A trip starts with the
 * first reading and ends once the dash has been silent for [GAP_MS] (the
 * ignition was turned off), so a quick stop with the engine off stays part
 * of the same ride.
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
    ) {
        fun toTrip(): Trip = Trip(
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
        )

        fun encode(): String = listOf(
            start, lastAt, startOdo, lastOdo, lastTripA, lastSpeed, distanceKm, movingMs, topSpeed,
            startLat ?: "", startLng ?: "", endLat ?: "", endLng ?: "",
        ).joinToString(";")

        companion object {
            fun decode(s: String?): Active? {
                val p = s?.split(';') ?: return null
                if (p.size != 13) return null
                return try {
                    Active(
                        p[0].toLong(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toDouble(),
                        p[5].toInt(), p[6].toDouble(), p[7].toLong(), p[8].toInt(),
                        p[9].toDoubleOrNull(), p[10].toDoubleOrNull(), p[11].toDoubleOrNull(), p[12].toDoubleOrNull(),
                    )
                } catch (_: NumberFormatException) {
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
     * Adds a reading. Returns the previous trip if this reading comes after
     * the ignition was off long enough to end it.
     */
    fun onReading(d: BikeData, now: Long): Trip? {
        val finished = finishIfIdle(now)
        val a = active
        justStarted = a == null
        if (a == null) {
            active = Active(now, now, d.odoKm, d.odoKm, d.tripAKm, d.speedKmh, 0.0, 0, d.speedKmh)
            return finished
        }
        val stepMs = now - a.lastAt
        val moving = d.speedKmh > 0 || a.lastSpeed > 0
        active = a.copy(
            lastAt = now,
            lastOdo = d.odoKm,
            lastTripA = d.tripAKm,
            lastSpeed = d.speedKmh,
            distanceKm = a.distanceKm + step(a, d),
            movingMs = a.movingMs + if (moving) stepMs.coerceIn(0, MAX_STEP_MS) else 0,
            topSpeed = maxOf(a.topSpeed, d.speedKmh),
        )
        return finished
    }

    /** Ends the trip if nothing has come from the dash for [GAP_MS]. */
    fun finishIfIdle(now: Long): Trip? {
        val a = active ?: return null
        return if (now - a.lastAt >= GAP_MS) finish() else null
    }

    /** Ends the trip now, e.g. when recording is turned off. */
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

    private fun step(a: Active, d: BikeData): Double {
        val byTripA = d.tripAKm - a.lastTripA
        if (byTripA in 0.0..MAX_STEP_KM) return byTripA
        // Trip A was reset, or the reading jumped: use the ODO for this stretch.
        val byOdo = (d.odoKm - a.lastOdo).toDouble()
        return if (byOdo in 0.0..MAX_STEP_KM) byOdo else 0.0
    }

    companion object {
        /** The dash silent this long means the ignition went off and the ride ended. */
        const val GAP_MS = 3 * 60 * 1000L

        /** Readings come every 5 s; a longer step is a dropout and does not count as moving. */
        private const val MAX_STEP_MS = 15_000L

        /** Farther than this between two readings is not real riding. */
        private const val MAX_STEP_KM = 5.0
    }
}
