package com.th4rv4.zukinav.trips

import com.th4rv4.zukinav.protocol.BikeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine Warm Up, and stops as the rider sees them. */
class WarmUpTest {

    private val min = 60_000L

    private fun near(expected: Long, actual: Long, within: Int) =
        assertTrue("expected about $expected, was $actual", kotlin.math.abs(expected - actual) <= within)

    /** Readings every 5 s with an engine temperature that rises while running. */
    private inner class Bike {
        val t = TripRecorder()
        var now = 0L
        var km = 100.0
        var temp = 18.0
        var engineOn = true

        private fun reading(speed: Int) {
            t.onReading(BikeData(speed, km.toInt(), Math.round(km * 10) / 10.0, 0.0, 4, null, temp.toInt()), now)
        }

        fun go(minutes: Double, kmh: Int) {
            repeat((minutes * 12).toInt()) {
                now += 5_000
                km += kmh * 5.0 / 3600
                // About 2 °C every 5 s while running, up to 90.
                if (engineOn) temp = minOf(90.0, temp + 2) else temp = maxOf(18.0, temp - 0.1)
                reading(kmh)
            }
        }

        fun keyOff(minutes: Double, coolTo: Double? = null) {
            now += (minutes * min).toLong()
            coolTo?.let { temp = it }
        }

        fun finish(): Trip = t.finish()!!.tidy()
    }

    @Test fun warmedUpBeforeMovingOff() {
        val b = Bike()
        b.go(5.0, 0) // idling
        b.go(10.0, 40)
        val trip = b.finish()
        assertTrue(trip.warmUp)
        assertEquals(0, trip.stops)
        assertEquals(trip.start, trip.rideStart)
        near(5 * 60L, trip.warmUpSeconds, 5)
        assertTrue(trip.warmToC!! - trip.warmFromC!! >= 10)
    }

    @Test fun dashOnEngineOffIsNotAWarmUp() {
        val b = Bike()
        b.engineOn = false
        b.go(5.0, 0) // dash on, engine off: temperature flat
        b.engineOn = true
        b.go(10.0, 40)
        val trip = b.finish()
        assertFalse(trip.warmUp)
        assertEquals(0, trip.stops)
        // The ride starts when the bike first moved.
        near(5 * min, trip.rideStart - trip.start, 5_000)
        near(10 * 60L, trip.durationSeconds, 10)
    }

    @Test fun shortIdleIsNotAWarmUp() {
        val b = Bike()
        b.go(0.5, 0)
        b.go(10.0, 40)
        assertFalse(b.finish().warmUp)
    }

    @Test fun keyOffThenIdlingIsOneStop() {
        val b = Bike()
        b.go(10.0, 40)
        b.go(1.0, 0)
        b.keyOff(8.0)
        b.go(4.0, 0) // switched back on, sat a while
        b.go(10.0, 40)
        val trip = b.finish()
        assertEquals(1, trip.stops)
        val s = trip.stopList.single()
        // From coming to rest until moving again.
        near(13 * 60L, s.seconds, 10)
    }

    @Test fun coldRestartMidRideShowsInTheStop() {
        val b = Bike()
        b.go(20.0, 40)
        b.go(0.5, 0)
        b.keyOff(9.0, coolTo = 30.0) // long enough to stay one ride, engine cooled
        b.go(3.0, 0) // warming up again, at rest
        b.go(10.0, 40)
        val trip = b.finish()
        val s = trip.stopList.single()
        near(3 * 60L, s.warmUpSeconds, 10)
        // Only the start of a ride is an Engine Warm Up item.
        assertFalse(trip.warmUp)
    }

    @Test fun hotRestartHasNoWarmUp() {
        val b = Bike()
        b.go(20.0, 40) // engine at 90
        b.go(0.5, 0)
        b.keyOff(3.0)
        b.go(3.0, 0) // still hot: no 10 °C rise
        b.go(10.0, 40)
        assertEquals(0L, b.finish().stopList.single().warmUpSeconds)
    }

    @Test fun savedRidesAreTidied() {
        // Your friend's ride: a stop before moving, and two stops with nothing between.
        val l = { s: Long, e: Long, km: Double -> Leg(s * min, e * min, km, if (km > 0) (e - s) * 60 else 0, if (km > 0) 70 else 0) }
        val trip = Trip(
            start = 0, end = 100 * min, distanceKm = 30.0, movingSeconds = 60 * 60, topSpeedKmh = 76, startOdo = 0, endOdo = 30,
            stops = 3, stopSeconds = (6 + 8 + 4) * 60,
            legs = listOf(l(0, 0, 0.0), l(6, 23, 13.0), l(31, 31, 0.0), l(35, 100, 17.0)),
            stopList = listOf(Stop(0, 6 * min), Stop(23 * min, 31 * min), Stop(31 * min, 35 * min, label = "Tea")),
        ).tidy()
        assertEquals(1, trip.stops)
        assertEquals(Stop(23 * min, 35 * min, label = "Tea"), trip.stopList.single())
        assertEquals(12 * 60L, trip.stopSeconds)
        assertEquals(6 * min, trip.rideStart)
        assertEquals(2, trip.legs.size)
    }

    @Test fun joiningRidesKeepsOneWarmUp() {
        val first = Trip(0, 60 * min, 40.0, 50 * 60, 90, 0, 40, movedAt = 5 * min, warmUp = true)
        // After lunch: switched on at 180, warmed up, moved at 185.
        val second = Trip(180 * min, 240 * min, 40.0, 50 * 60, 90, 40, 80, movedAt = 185 * min, warmUp = true)
        val t = TripMerge.merge(first, second)
        assertTrue(t.warmUp)
        assertEquals(5 * 60L, t.warmUpSeconds)
        val lunch = t.stopList.single()
        assertEquals(60 * min, lunch.arrive)
        assertEquals(185 * min, lunch.depart)
        assertEquals(125 * 60L, t.stopSeconds)
    }
}
