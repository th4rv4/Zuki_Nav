package com.th4rv4.zukinav.trips

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One month of riding, for the recap image. */
data class MonthRecap(
    val month: YearMonth,
    val km: Double,
    val rides: Int,
    val ridingSeconds: Long,
    val longestKm: Double,
    val topSpeedKmh: Int,
    /** The month's rides, longest first. */
    val trips: List<Trip>,
) {
    companion object {
        /** Days at the start of a month when last month's recap is offered. */
        const val OFFER_DAYS = 7

        fun of(all: List<Trip>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): MonthRecap {
            val mine = all.filter { YearMonth.from(Instant.ofEpochMilli(it.start).atZone(zone)) == month }
                .sortedByDescending { it.distanceKm }
            return MonthRecap(
                month = month,
                km = mine.sumOf { it.distanceKm },
                rides = mine.size,
                ridingSeconds = mine.sumOf { it.ridingSeconds },
                longestKm = mine.firstOrNull()?.distanceKm ?: 0.0,
                topSpeedKmh = mine.maxOfOrNull { it.topSpeedKmh } ?: 0,
                trips = mine,
            )
        }

        /** Last month, while it is still being offered (the first week of this month), else null. */
        fun offered(today: LocalDate = LocalDate.now()): YearMonth? =
            if (today.dayOfMonth <= OFFER_DAYS) YearMonth.from(today).minusMonths(1) else null
    }
}
