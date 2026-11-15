package com.th4rv4.zukinav.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMergeTest {
    private val min = 60_000L
    private val a = Trip(
        start = 0, end = 6 * min, distanceKm = 1.7, movingSeconds = 300, topSpeedKmh = 47,
        startOdo = 3319, endOdo = 3321, startPlace = "Sutrehr", endPlace = "Nagrota Bagwan",
        maxEngineC = 88, avgEngineC = 80,
    )
    private val b = Trip(
        start = 15 * min, end = 26 * min, distanceKm = 5.6, movingSeconds = 600, topSpeedKmh = 92,
        startOdo = 3321, endOdo = 3326, starred = true, maxEngineC = 98, avgEngineC = 92,
    )

    @Test fun mergesStats() {
        val m = TripMerge.merge(a, b)
        assertEquals(0L, m.id)
        assertEquals(26 * min, m.end)
        assertEquals(7.3, m.distanceKm, 1e-9)
        assertEquals(900L, m.movingSeconds)
        assertEquals(92, m.topSpeedKmh)
        assertEquals(3319, m.startOdo)
        assertEquals(3326, m.endOdo)
        assertTrue(m.starred)
        assertEquals("Sutrehr", m.startPlace)
        // b had no end place: keep a's rather than lose it.
        assertEquals("Nagrota Bagwan", m.endPlace)
        assertEquals(98, m.maxEngineC)
        assertEquals(88, m.avgEngineC) // (80*300 + 92*600) / 900
        assertEquals(1, m.stops)
        assertEquals(9 * 60L, m.stopSeconds)
        assertEquals(17 * 60L, m.ridingSeconds)
    }

    @Test fun orderDoesNotMatter() {
        assertEquals(TripMerge.merge(a, b), TripMerge.merge(b, a))
    }

    @Test fun mergingAgainAddsStops() {
        val c = Trip(start = 32 * min, end = 38 * min, distanceKm = 1.5, movingSeconds = 280, topSpeedKmh = 47, startOdo = 3326, endOdo = 3327)
        val m = TripMerge.merge(TripMerge.merge(a, b), c)
        assertEquals(2, m.stops)
        assertEquals(15 * 60L, m.stopSeconds)
        assertEquals(38 * min, m.end)
    }

    @Test fun nextRide() {
        assertEquals(b, TripMerge.next(a, listOf(b, a)))
        assertNull(TripMerge.next(b, listOf(b, a)))
    }
}
