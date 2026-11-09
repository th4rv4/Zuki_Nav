package com.th4rv4.zukinav.trips

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How trips are worded in the app. */
object TripFormat {

    /** "1 h 32 min", "38 min", "45 s". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }

    /** "61.3 km", "800 m". */
    fun distance(km: Double): String =
        if (km < 1.0) "${Math.round(km * 1000 / 100) * 100} m" else String.format(Locale.US, "%.1f km", km)

    fun odo(km: Int): String = String.format(Locale.US, "%,d", km)

    /** The rider's name for it, else "From → To", else "Ride, 7 Nov". */
    fun title(t: Trip, zone: ZoneId = ZoneId.systemDefault()): String {
        t.name?.takeIf { it.isNotBlank() }?.let { return it }
        val from = t.startPlace
        val to = t.endPlace
        return when {
            from != null && to != null && from != to -> "$from → $to"
            from != null && (to == null || to == from) -> "Ride In $from"
            to != null -> "Ride To $to"
            else -> "Ride, " + date(t.start, zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
        }
    }

    /** "Today, 6:12 AM", "Yesterday, 9:05 AM", "7 Nov, 7:40 PM", "7 Nov 2025, ...". */
    fun whenStarted(ms: Long, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        day(ms, zone, today) + ", " + time(ms, is24h, zone)

    /** "Today, 6:12 – 8:21 AM" style range for the details screen. */
    fun span(t: Trip, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        day(t.start, zone, today) + ", " + time(t.start, is24h, zone) + " – " + time(t.end, is24h, zone)

    fun day(ms: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val d = date(ms, zone)
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
            else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
        }
    }

    fun time(ms: Long, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
            .format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.US))

    /** Total distance and number of rides that started this calendar month. */
    fun monthTotals(trips: List<Trip>, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): Pair<Double, Int> {
        val mine = trips.filter { date(it.start, zone).let { d -> d.year == today.year && d.month == today.month } }
        return mine.sumOf { it.distanceKm } to mine.size
    }

    /** Plain text for sharing a trip. */
    fun shareText(t: Trip, is24h: Boolean, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        buildString {
            appendLine(title(t, zone))
            appendLine(span(t, is24h, zone, today))
            appendLine("Distance: ${distance(t.distanceKm)}")
            appendLine("Moving time: ${duration(t.movingSeconds)}")
            t.averageKmh?.let { appendLine("Average speed: ${it.toInt()} km/h") }
            appendLine("Top speed: ${t.topSpeedKmh} km/h")
            appendLine("ODO: ${odo(t.startOdo)} → ${odo(t.endOdo)} km")
            append("Recorded with Zuki Nav")
        }

    private fun date(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
