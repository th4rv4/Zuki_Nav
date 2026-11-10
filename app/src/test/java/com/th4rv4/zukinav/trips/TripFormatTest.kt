package com.th4rv4.zukinav.trips

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TripFormatTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 10)
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, ist).toInstant().toEpochMilli()
    private fun trip(start: Long, end: Long, km: Double = 61.3) = Trip(start, end, km, 5520, 96, 3231, 3292)

    @Test fun durations() {
        assertEquals("1 h 32 min", TripFormat.duration(5520))
        assertEquals("2 h", TripFormat.duration(7200))
        assertEquals("38 min", TripFormat.duration(2280))
        assertEquals("45 s", TripFormat.duration(45))
        assertEquals("2h 35m", TripFormat.shortDuration(9300))
        assertEquals("46m", TripFormat.shortDuration(2760))
        assertEquals("2h", TripFormat.shortDuration(7200))
        assertEquals("11h", TripFormat.shortDuration(40500))
    }

    @Test fun distances() {
        assertEquals("61.3 km", TripFormat.distance(61.3))
        assertEquals("800 m", TripFormat.distance(0.8))
        assertEquals("3,292", TripFormat.odo(3292))
    }

    @Test fun titles() {
        val t = trip(at(7, 19, 40), at(7, 19, 51))
        assertEquals("Ride, 7 Nov", TripFormat.title(t, ist))
        assertEquals("Koramangala → Nandi Hills", TripFormat.title(t.copy(startPlace = "Koramangala", endPlace = "Nandi Hills"), ist))
        assertEquals("Ride In Indiranagar", TripFormat.title(t.copy(startPlace = "Indiranagar", endPlace = "Indiranagar"), ist))
        assertEquals("Office Run", TripFormat.title(t.copy(name = "Office Run", startPlace = "A", endPlace = "B"), ist))
    }

    @Test fun dates() {
        assertEquals("Today, 6:12 AM", TripFormat.whenStarted(at(10, 6, 12), false, ist, today))
        assertEquals("Yesterday, 09:05", TripFormat.whenStarted(at(9, 9, 5), true, ist, today))
        assertEquals("Today, 6:12 AM – 8:21 AM", TripFormat.span(trip(at(10, 6, 12), at(10, 8, 21)), false, ist, today))
        assertEquals("7 Nov", TripFormat.day(at(7, 1, 0), ist, today))
    }

    @Test fun month() {
        val trips = listOf(trip(at(10, 6, 0), at(10, 7, 0), 61.3), trip(at(2, 6, 0), at(2, 7, 0), 14.8),
            Trip(ZonedDateTime.of(2026, 10, 30, 6, 0, 0, 0, ist).toInstant().toEpochMilli(), 0, 99.0, 0, 0, 0, 0))
        val (km, n) = TripFormat.monthTotals(trips, ist, today)
        assertEquals(76.1, km, 0.001)
        assertEquals(2, n)
        assertEquals(7200L, TripFormat.monthSeconds(trips, ist, today))
    }
}
