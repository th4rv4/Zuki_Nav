package com.th4rv4.zukinav.trips

import com.th4rv4.zukinav.protocol.BikeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {

    private fun r(speed: Int, odo: Int, tripA: Double) = BikeData(speed, odo, tripA, 100.0, 4, null)

    @Test fun rideEndsAfterIgnitionOff() {
        val t = TripRecorder()
        var now = 0L
        assertNull(t.onReading(r(0, 3274, 50.0), now))
        assertTrue(t.justStarted)
        // 10 minutes at about 60 km/h, a reading every 5 s.
        var tripA = 50.0
        repeat(120) {
            now += 5_000
            tripA += 0.0833
            t.onReading(r(60 + it % 20, 3274 + ((tripA - 50) .toInt()), Math.round(tripA * 10) / 10.0), now)
        }
        assertTrue(!t.justStarted)
        // Ignition off: nothing ends yet...
        assertNull(t.finishIfIdle(now + 60_000))
        // ...until the dash has been quiet for 3 minutes.
        val trip = t.finishIfIdle(now + TripRecorder.GAP_MS)
        assertNotNull(trip)
        trip!!
        assertEquals(10.0, trip.distanceKm, 0.15)
        assertEquals(600, trip.movingSeconds)
        assertEquals(79, trip.topSpeedKmh)
        assertEquals(60.0, trip.averageKmh!!, 1.0)
        assertEquals(3274, trip.startOdo)
        assertNull(t.active)
    }

    @Test fun shortStopKeepsTheSameRide() {
        val t = TripRecorder()
        t.onReading(r(40, 100, 1.0), 0)
        t.onReading(r(40, 100, 1.5), 5_000)
        // Engine off for 2 minutes at a fuel stop, then on again.
        assertNull(t.onReading(r(0, 100, 1.5), 125_000))
        t.onReading(r(30, 101, 2.0), 130_000)
        assertEquals(1.0, t.finish()!!.distanceKm, 0.01)
    }

    @Test fun nextRideAfterLongStopIsANewTrip() {
        val t = TripRecorder()
        t.onReading(r(40, 100, 1.0), 0)
        t.onReading(r(40, 101, 2.0), 5_000)
        val first = t.onReading(r(0, 101, 2.0), 5_000 + TripRecorder.GAP_MS)
        assertEquals(1.0, first!!.distanceKm, 0.01)
        assertTrue(t.justStarted)
    }

    @Test fun tripAResetUsesOdo() {
        val t = TripRecorder()
        t.onReading(r(50, 200, 10.0), 0)
        t.onReading(r(50, 201, 0.0), 5_000)
        t.onReading(r(50, 201, 0.4), 10_000)
        assertEquals(1.4, t.finish()!!.distanceKm, 0.01)
    }

    @Test fun keepsStateAcrossRestarts() {
        val t = TripRecorder()
        t.onReading(r(40, 100, 1.0), 0)
        t.setStartPlace(12.97, 77.59)
        val saved = t.active!!.encode()
        val again = TripRecorder().apply { active = TripRecorder.Active.decode(saved) }
        assertEquals(t.active, again.active)
        assertNull(TripRecorder.Active.decode("nope"))
    }

    @Test fun engineTemperature() {
        val t = TripRecorder()
        var now = 0L
        listOf(30, 60, 90, null, 100).forEachIndexed { i, c ->
            t.onReading(BikeData(40, 100 + i, 1.0 + i, 100.0, 4, null, c), now)
            now += 5_000
        }
        // Survives a restart part-way.
        t.active = TripRecorder.Active.decode(t.active!!.encode())
        val trip = t.finishIfIdle(now + TripRecorder.GAP_MS)!!
        assertEquals(100, trip.maxEngineC)
        assertEquals(70, trip.avgEngineC)
        // Saved before engine temperature was added.
        val old = "0;5000;100;101;2.0;40;1.0;5000;40;;;;"
        assertNull(TripRecorder.Active.decode(old)!!.engineMax)
    }

    @Test fun noEngineTemperature() {
        val t = TripRecorder()
        t.onReading(r(40, 100, 1.0), 0)
        val trip = t.finishIfIdle(TripRecorder.GAP_MS)!!
        assertNull(trip.maxEngineC)
        assertNull(trip.avgEngineC)
    }
}
