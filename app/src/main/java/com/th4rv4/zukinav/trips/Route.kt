package com.th4rv4.zukinav.trips

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS point of a ride: where, when, and the dash's speed and engine temperature then. */
data class RoutePoint(
    val lat: Double,
    val lng: Double,
    /** ms since the epoch. */
    val at: Long,
    val speedKmh: Int,
    val engineC: Int? = null,
)

/** Plain-text storage and the maths for drawing a route. Pure Kotlin, no Android. */
object Route {

    /** One point per line: lat;lng;at;speed;engine. */
    fun encode(p: RoutePoint): String = String.format(
        Locale.US, "%.6f;%.6f;%d;%d;%s", p.lat, p.lng, p.at, p.speedKmh, p.engineC?.toString() ?: "",
    )

    fun decode(line: String): RoutePoint? {
        val f = line.trim().split(';')
        if (f.size < 4) return null
        return RoutePoint(
            lat = f[0].toDoubleOrNull() ?: return null,
            lng = f[1].toDoubleOrNull() ?: return null,
            at = f[2].toLongOrNull() ?: return null,
            speedKmh = f[3].toIntOrNull() ?: 0,
            engineC = f.getOrNull(4)?.toIntOrNull(),
        )
    }

    /** Points between the trip's start and end; GPS keeps going for a while after the ignition goes off. */
    fun trim(points: List<RoutePoint>, start: Long, end: Long): List<RoutePoint> =
        points.filter { it.at >= start - 5_000 && it.at <= end + 10_000 }

    /** Distance in metres. */
    fun metres(a: RoutePoint, b: RoutePoint): Double = distanceM(a.lat, a.lng, b.lat, b.lng)

    /** Distance in metres between two places. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** At most [max] points, keeping the first and last, for thumbnails and links. */
    fun thin(points: List<RoutePoint>, max: Int): List<RoutePoint> {
        if (points.size <= max || max < 2) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return (0 until max).map { points[Math.round(it * step).toInt().coerceAtMost(points.size - 1)] }
    }

    /**
     * Flat x/y in metres around the route's middle, north up. Good enough for
     * the size of one ride; returns pairs in the same order as [points].
     */
    fun project(points: List<RoutePoint>): List<Pair<Double, Double>> {
        if (points.isEmpty()) return emptyList()
        val lat0 = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
        val k = cos(Math.toRadians(lat0))
        val mPerDeg = 111_320.0
        return points.map { Pair(it.lng * k * mPerDeg, -it.lat * mPerDeg) }
    }

    /** Index of the fastest point, or -1. */
    fun fastest(points: List<RoutePoint>): Int =
        if (points.isEmpty()) -1 else points.indices.maxByOrNull { points[it].speedKmh } ?: -1

    /**
     * A Google Maps directions link through the route: start, end and up to
     * [MAX_WAYPOINTS] stops in between, which is all a link can carry.
     */
    fun googleMapsUrl(points: List<RoutePoint>): String? {
        if (points.size < 2) return null
        val thinned = thin(points, MAX_WAYPOINTS + 2)
        fun ll(p: RoutePoint) = String.format(Locale.US, "%.5f,%.5f", p.lat, p.lng)
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving")
        sb.append("&origin=").append(ll(thinned.first()))
        sb.append("&destination=").append(ll(thinned.last()))
        val mid = thinned.subList(1, thinned.size - 1)
        if (mid.isNotEmpty()) sb.append("&waypoints=").append(mid.joinToString("%7C") { ll(it) })
        return sb.toString()
    }

    /** True if the point is a usable new one after [last]: moved a few metres, not a GPS jump. */
    fun worthKeeping(last: RoutePoint?, next: RoutePoint): Boolean {
        if (last == null) return true
        val m = metres(last, next)
        val s = abs(next.at - last.at) / 1000.0
        if (m < MIN_STEP_M) return false
        // Over 300 km/h between two fixes is a GPS glitch.
        return s <= 0.0 || m / s < 83.0
    }

    const val MAX_WAYPOINTS = 8
    const val MIN_STEP_M = 8.0
}
