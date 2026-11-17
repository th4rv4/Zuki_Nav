package com.th4rv4.zukinav.trips

import com.th4rv4.zukinav.protocol.BikeData
import org.junit.Assert.assertEquals
import org.junit.Test

class DropJudgeTest {

    // About 0.0009° of latitude is 100 m.
    private val lat = 30.7
    private val lng = 76.8

    @Test fun switchedOffAndWalkedAway() {
        val j = DropJudge(0, lastDashKmh = 0)
        j.setDropPoint(lat, lng)
        var t = 0L
        // Walking: 5 km/h, a few metres every 2 s.
        repeat(25) {
            t += 2_000
            assertEquals(DropJudge.Verdict.WAITING, j.onFix(t, lat + it * 0.00002, lng, 5f))
        }
        t += 10_000
        assertEquals(DropJudge.Verdict.BIKE_OFF, j.onTime(t))
    }

    @Test fun dashSaidMovingWhenItDropped() {
        assertEquals(DropJudge.Verdict.DASH_LOST, DropJudge(0, lastDashKmh = 42).verdict)
    }

    @Test fun riderStillMovingOnGps() {
        // The 10:39 drop: no recent dash speed, but the phone keeps going.
        val j = DropJudge(0, lastDashKmh = null)
        assertEquals(DropJudge.Verdict.WAITING, j.onFix(2_000, lat, lng, 38f))
        assertEquals(DropJudge.Verdict.DASH_LOST, j.onFix(4_000, lat + 0.0002, lng, 40f))
    }

    @Test fun oneFastFixIsNotEnough() {
        val j = DropJudge(0, lastDashKmh = null)
        j.onFix(2_000, lat, lng, 30f) // a GPS jump
        assertEquals(DropJudge.Verdict.WAITING, j.onFix(4_000, lat, lng, 0f))
        assertEquals(DropJudge.Verdict.WAITING, j.onFix(6_000, lat, lng, 25f))
    }

    @Test fun farFromTheDropPoint() {
        val j = DropJudge(0, lastDashKmh = null)
        j.setDropPoint(lat, lng)
        assertEquals(DropJudge.Verdict.DASH_LOST, j.onFix(20_000, lat + 0.0015, lng, null))
    }

    @Test fun withoutLocationGoesByTheOldWait() {
        val j = DropJudge(0, lastDashKmh = 0)
        assertEquals(DropJudge.Verdict.WAITING, j.onTime(20_000, hasGps = false))
        assertEquals(DropJudge.Verdict.BIKE_OFF, j.onTime(30_000, hasGps = false))
    }

    @Test fun gapWhileDashLostIsRidingNotAStop() {
        val t = TripRecorder()
        var now = 0L
        var km = 100.0
        fun reading(speed: Int, gap: Boolean = false) =
            t.onReading(BikeData(speed, km.toInt(), Math.round(km * 10) / 10.0, 0.0, 4, null), now, gapWasRiding = gap)
        repeat(60) { now += 5_000; km += 0.06; reading(43) }
        // 4 min without the dash, 3 km covered.
        now += 4 * 60_000
        km += 3.0
        reading(40, gap = true)
        val trip = t.finish()!!
        assertEquals(0, trip.stops)
        assertEquals(6.6, trip.distanceKm, 0.11)
        assertEquals(9 * 60.0, trip.movingSeconds.toDouble(), 6.0)
    }
}
