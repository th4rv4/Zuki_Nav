package com.th4rv4.zukinav.trips

import com.th4rv4.zukinav.nav.RouteState
import com.th4rv4.zukinav.protocol.BikeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays of real rides against the trip and stop rules. */
class TripRulesTest {

    private val min = 60_000L

    private fun near(expected: Long, actual: Long, within: Long) =
        assertTrue("expected about $expected, was $actual", kotlin.math.abs(expected - actual) <= within)

    /** A bike on a ride: readings every 5 s while the key is on, and a Maps route that may be open. */
    private inner class Ride {
        val t = TripRecorder()
        val route = RouteState()
        var now = 0L
        var km = 100.0
        val saved = ArrayList<Trip>()

        private fun reading(speed: Int) {
            t.onReading(BikeData(speed, km.toInt(), Math.round(km * 10) / 10.0, 0.0, 4, null), now, route.isOpen(now))
                ?.let { saved += it }
        }

        fun ride(minutes: Double, kmh: Int) {
            val steps = (minutes * 12).toInt()
            repeat(steps) {
                now += 5_000
                km += kmh * 5.0 / 3600
                reading(kmh)
            }
        }

        fun halt(minutes: Double) = ride(minutes, 0)

        /** Key off: no readings; the app checks every minute whether the ride ended. */
        fun keyOff(minutes: Double) {
            val until = now + (minutes * min).toLong()
            while (now + min <= until) {
                now += min
                t.finishIfIdle(now, route.isOpen(now))?.let { saved += it }
            }
            now = until
        }

        fun end() {
            t.finish()?.let { saved += it }
        }
    }

    @Test fun cityRideWithSignals() {
        val r = Ride()
        repeat(6) {
            r.ride(4.0, 30)
            r.halt(1.5) // a red light
        }
        r.end()
        assertEquals(1, r.saved.size)
        val trip = r.saved[0]
        assertEquals(0, trip.stops)
        // Signals are time not moving, not stops.
        assertTrue(trip.ridingSeconds - trip.movingSeconds >= 8 * 60)
    }

    @Test fun longJamWithKeyOnIsAStop() {
        val r = Ride()
        r.ride(10.0, 40)
        r.halt(7.0)
        r.ride(10.0, 40)
        r.end()
        val trip = r.saved.single()
        assertEquals(1, trip.stops)
        // The stop starts when the bike came to rest.
        near(7 * 60L, trip.stopList[0].seconds, 10)
        assertEquals(2, trip.legs.size)
    }

    @Test fun shortKeyOffIsTimeNotAStop() {
        val r = Ride()
        r.ride(10.0, 40)
        r.halt(0.5)
        r.keyOff(1.0) // stalled, started again
        r.ride(10.0, 40)
        r.end()
        assertEquals(0, r.saved.single().stops)
    }

    @Test fun teaBreakKeepsTheRide() {
        // Today's ride: out to Lahru, 12 minutes off, back home.
        val r = Ride()
        r.ride(15.0, 50)
        r.halt(0.2)
        r.keyOff(12.5)
        r.ride(15.0, 50)
        r.end()
        // No route was open and the break was over 10 minutes: two rides.
        assertEquals(2, r.saved.size)

        val r2 = Ride()
        r2.ride(15.0, 50)
        r2.halt(0.2)
        r2.keyOff(8.0)
        r2.ride(15.0, 50)
        r2.end()
        val trip = r2.saved.single()
        assertEquals(1, trip.stops)
        near(8 * 60L + 12, trip.stopList[0].seconds, 10)
    }

    @Test fun routeOpenedAtTheStopKeepsTheRideGoing() {
        // Key off, then the rider plans the way back in Maps before the 10 minutes run out.
        val r = Ride()
        r.ride(15.0, 50)
        r.keyOff(9.0)
        r.route.onPosted("500 m · Head north", r.now)
        r.keyOff(4.0)
        r.ride(15.0, 50)
        r.end()
        assertEquals(1, r.saved.size)
        assertEquals(1, r.saved[0].stops)
    }

    @Test fun delhiToHimachal() {
        val r = Ride()
        r.route.onPosted("2 km · Turn right", 0)
        r.ride(90.0, 60)
        r.halt(0.2)
        r.keyOff(25.0) // breakfast
        r.ride(120.0, 60)
        r.halt(0.2)
        r.keyOff(5.0) // fuel
        r.ride(120.0, 50)
        r.halt(6.0) // roadworks, key on
        r.ride(60.0, 40)
        r.route.onPosted("Arriving", r.now)
        r.route.onRemoved(r.now + 5_000)
        r.halt(0.2)
        r.keyOff(30.0)
        assertEquals(1, r.saved.size)
        val trip = r.saved[0]
        assertEquals(3, trip.stops)
        assertEquals(4, trip.legs.size)
        // The ride ended when the key went off at the hotel, not 10 minutes later.
        near(r.now - 30 * min, trip.end, 1_000)
    }

    @Test fun closingMapsAtAPumpEndsAfterTenMinutes() {
        val r = Ride()
        r.route.onPosted("5 km · Continue", 0)
        r.ride(30.0, 50)
        r.keyOff(1.0)
        r.route.onRemoved(r.now) // the rider closed Maps
        r.keyOff(8.0)
        assertTrue(r.saved.isEmpty())
        r.keyOff(1.5)
        assertEquals(1, r.saved.size)
        near(30 * min, r.saved[0].end, 5_000)
    }

    @Test fun mapsOpenOvernightEndsAfterTwoHours() {
        val r = Ride()
        r.route.onPosted("5 km · Continue", 0)
        r.ride(30.0, 50)
        r.keyOff(119.0)
        assertTrue(r.saved.isEmpty())
        r.keyOff(2.0)
        assertEquals(1, r.saved.size)
        near(30 * min, r.saved[0].end, 5_000)
    }

    @Test fun bluetoothBlipChangesNothing() {
        val r = Ride()
        r.ride(10.0, 60)
        r.now += 40_000 // no readings for 40 s
        r.ride(10.0, 60)
        r.end()
        val trip = r.saved.single()
        assertEquals(0, trip.stops)
        assertEquals(20.0, trip.distanceKm, 0.8)
    }

    @Test fun stateWithStopsSurvivesARestart() {
        val r = Ride()
        r.ride(10.0, 40)
        r.halt(6.0)
        r.ride(1.0, 40)
        val saved = r.t.active!!.encode()
        val again = TripRecorder.Active.decode(saved)
        assertEquals(r.t.active, again)
        assertEquals(1, again!!.stops.size)
    }

    @Test fun routeState() {
        val s = RouteState()
        assertFalse(s.isOpen(0))
        s.onPosted("200 m · Turn left", 0)
        assertTrue(s.isOpen(1_000))
        // Maps takes it down for a moment mid-route.
        s.onRemoved(2_000)
        assertTrue(s.isOpen(30_000))
        s.onPosted("Rerouting...", 31_000)
        assertTrue(s.isOpen(31_000))
        // Gone for a minute without arriving: closed.
        s.onRemoved(40_000)
        assertTrue(s.isOpen(99_000))
        assertFalse(s.isOpen(100_000))
        // Arriving, then gone: closed at once.
        s.onPosted("Arriving", 200_000)
        s.onRemoved(205_000)
        assertFalse(s.isOpen(205_000))
        // Saved and read back.
        s.onPosted("1 km · Continue", 300_000)
        assertTrue(RouteState.decode(s.encode()).isOpen(400_000))
        assertNull(RouteState.decode("x").closesAt(0))
    }

    @Test fun gpsPlan() {
        val p = GpsPlan()
        p.onReading(40)
        p.update(0, keyOn = true, routeOpen = false)
        assertEquals(GpsPlan.Mode.FAST, p.mode)
        p.onReading(0)
        p.update(5_000, keyOn = true, routeOpen = false)
        assertEquals(GpsPlan.Mode.FAST, p.mode)
        p.onReading(0)
        p.update(10_000, keyOn = true, routeOpen = false)
        assertEquals(GpsPlan.Mode.SLOW, p.mode)
        p.onFix(12_000, 15f)
        p.update(12_000, keyOn = true, routeOpen = false)
        assertEquals(GpsPlan.Mode.FAST, p.mode)
        // A route opens: Maps' fixes.
        p.update(15_000, keyOn = true, routeOpen = true)
        assertEquals(GpsPlan.Mode.SHARED, p.mode)
        p.onFix(18_000, 40f)
        p.update(20_000, keyOn = true, routeOpen = true)
        assertEquals(GpsPlan.Mode.SHARED, p.mode)
        // None for 6 s while moving: own GPS for a minute, then try again.
        p.update(25_000, keyOn = true, routeOpen = true)
        assertEquals(GpsPlan.Mode.FAST, p.mode)
        p.update(60_000, keyOn = true, routeOpen = true)
        assertEquals(GpsPlan.Mode.FAST, p.mode)
        p.update(86_000, keyOn = true, routeOpen = true)
        assertEquals(GpsPlan.Mode.SHARED, p.mode)
        p.update(90_000, keyOn = false, routeOpen = true)
        assertEquals(GpsPlan.Mode.OFF, p.mode)
        assertNotNull(p)
    }
}
