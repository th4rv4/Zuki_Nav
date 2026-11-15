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

class TripStopsTest {
    private val min = 60_000L

    @Test fun mergeKeepsLegsAndStops() {
        val a = Trip(start = 0, end = 6 * min, distanceKm = 1.7, movingSeconds = 300, topSpeedKmh = 47, startOdo = 1, endOdo = 2,
            startPlace = "Sutrehr", endPlace = "Nagrota Bagwan", endLat = 32.1, endLng = 76.3)
        val b = Trip(start = 15 * min, end = 26 * min, distanceKm = 5.6, movingSeconds = 600, topSpeedKmh = 92, startOdo = 2, endOdo = 7,
            startPlace = "Nagrota Bagwan", endPlace = "Matyari")
        val c = Trip(start = 32 * min, end = 38 * min, distanceKm = 1.5, movingSeconds = 280, topSpeedKmh = 47, startOdo = 7, endOdo = 8,
            startPlace = "Matyari", endPlace = "Sutrehr")
        val m = TripMerge.merge(TripMerge.merge(a, b), c)
        assertEquals(3, m.legs.size)
        assertEquals(2, m.stopList.size)
        assertEquals("Nagrota Bagwan", m.stopList[0].place)
        assertEquals(6 * min, m.stopList[0].arrive)
        assertEquals(15 * min, m.stopList[0].depart)
        assertEquals("Matyari", m.stopList[1].place)
        assertEquals("Sutrehr Round Trip", TripFormat.title(m))
        assertEquals(92, m.legs[1].topSpeedKmh)
    }

    @Test fun olderMergeWithoutDetailsStaysEmpty() {
        val old = Trip(start = 0, end = 10 * min, distanceKm = 3.0, movingSeconds = 400, topSpeedKmh = 50, startOdo = 1, endOdo = 4, stops = 1, stopSeconds = 120)
        val next = Trip(start = 20 * min, end = 25 * min, distanceKm = 1.0, movingSeconds = 300, topSpeedKmh = 40, startOdo = 4, endOdo = 5)
        val m = TripMerge.merge(old, next)
        assertEquals(2, m.stops)
        assertTrue(m.legs.isEmpty())
    }

    @Test fun stopsFromRouteGaps() {
        fun p(at: Long, lat: Double) = RoutePoint(lat, 76.0, at, 40)
        val pts = listOf(p(0, 32.0), p(60_000, 32.01), p(9 * min, 32.01), p(10 * min, 32.02), p(16 * min, 32.02), p(17 * min, 32.0))
        val (legs, stops) = TripStops.fromRoute(pts)!!
        assertEquals(3, legs.size)
        assertEquals(2, stops.size)
        assertEquals(60_000L, stops[0].arrive)
        assertEquals(9 * min, stops[0].depart)
        assertTrue(legs[0].distanceKm > 1.0)
        assertNull(TripStops.fromRoute(listOf(p(0, 32.0), p(60_000, 32.01))))
    }
}

class TripStopsExpectedTest {
    @Test fun longRedLightIsNotAStop() {
        val min = 60_000L
        fun p(at: Long, lat: Double) = RoutePoint(lat, 76.0, at, 40)
        // 3-minute red light, then a real 9-minute stop.
        val pts = listOf(p(0, 32.0), p(min, 32.01), p(4 * min, 32.02), p(5 * min, 32.03), p(14 * min, 32.03), p(15 * min, 32.04))
        val (legs, stops) = TripStops.fromRoute(pts, expected = 1)!!
        assertEquals(2, legs.size)
        assertEquals(5 * min, stops[0].arrive)
        assertEquals(14 * min, stops[0].depart)
    }
}
