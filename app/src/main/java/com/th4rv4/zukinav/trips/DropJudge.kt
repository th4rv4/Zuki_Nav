package com.th4rv4.zukinav.trips

/**
 * The dash went quiet: was the bike switched off, or did the dash drop out
 * while riding? The dash says the same in both cases, so this goes by what
 * happens around it:
 *  - the dash's last speed, if it was still moving: nobody switches off at speed;
 *  - the phone's GPS in the next minute: riding speed on two fixes in a row,
 *    or [AWAY_M] from where the dash dropped, is still riding;
 *  - otherwise, still or walking for [DECIDE_MS]: the bike is off.
 *
 * Not thread safe: call it from one thread.
 */
class DropJudge(
    private val droppedAt: Long,
    /** The dash's last speed, if a reading came in the last [FRESH_READING_MS]. */
    lastDashKmh: Int?,
) {

    enum class Verdict { WAITING, BIKE_OFF, DASH_LOST }

    var verdict: Verdict = if (lastDashKmh != null && lastDashKmh >= MOVING_DASH_KMH) Verdict.DASH_LOST else Verdict.WAITING
        private set

    /** Why it decided, for the log. */
    var reason: String = if (verdict == Verdict.DASH_LOST) "dash said $lastDashKmh km/h" else ""
        private set

    /** Where the dash dropped, if known. */
    var dropLat: Double? = null
        private set
    var dropLng: Double? = null
        private set

    private var fastFixes = 0

    /** Where the bike was when the dash dropped, e.g. the ride's last route point. */
    fun setDropPoint(lat: Double, lng: Double) {
        if (dropLat != null) return
        dropLat = lat
        dropLng = lng
    }

    /** A GPS fix, with the speed GPS measured if it has one. */
    fun onFix(now: Long, lat: Double, lng: Double, gpsKmh: Float?): Verdict {
        if (verdict != Verdict.WAITING) return verdict
        val fromLat = dropLat
        val fromLng = dropLng
        if (fromLat == null || fromLng == null) {
            setDropPoint(lat, lng)
        } else {
            val away = Route.distanceM(fromLat, fromLng, lat, lng)
            if (away >= AWAY_M) return lost("${away.toInt()} m from where the dash dropped")
        }
        if (gpsKmh != null && gpsKmh >= RIDING_GPS_KMH) {
            fastFixes++
            if (fastFixes >= 2) return lost("GPS ${gpsKmh.toInt()} km/h")
        } else {
            fastFixes = 0
        }
        return onTime(now)
    }

    /** Called as time passes; settles on Bike Off once [DECIDE_MS] go by without signs of riding. */
    fun onTime(now: Long, hasGps: Boolean = true): Verdict {
        if (verdict != Verdict.WAITING) return verdict
        val wait = if (hasGps) DECIDE_MS else NO_GPS_DECIDE_MS
        if (now - droppedAt >= wait) {
            verdict = Verdict.BIKE_OFF
            reason = if (hasGps) "still for ${wait / 1000} s" else "no location, ${wait / 1000} s quiet"
        }
        return verdict
    }

    private fun lost(why: String): Verdict {
        verdict = Verdict.DASH_LOST
        reason = why
        return verdict
    }

    companion object {
        /** No sign of riding this long after the drop: the bike is off. */
        const val DECIDE_MS = 60_000L

        /** Without location the dash's word is all there is, as before. */
        const val NO_GPS_DECIDE_MS = 30_000L

        /** A dash reading this recent says how fast the bike was going. */
        const val FRESH_READING_MS = 15_000L

        /** The dash's last speed at or above this: still riding. */
        const val MOVING_DASH_KMH = 10

        /** GPS speed at or above this on two fixes in a row: riding, not walking. */
        const val RIDING_GPS_KMH = 15f

        /** This far from where the dash dropped: riding (on foot it would take minutes). */
        const val AWAY_M = 150.0

        /** A dash lost while riding that doesn't come back in this long: treat it as the bike off where it dropped. */
        const val LOST_LIMIT_MS = 5 * 60_000L
    }
}
