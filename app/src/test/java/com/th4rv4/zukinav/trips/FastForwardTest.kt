package com.th4rv4.zukinav.trips

import com.th4rv4.zukinav.protocol.BikeData
import org.junit.Assert.assertEquals
import org.junit.Test

/** Zuki Sim at 60×: readings come 15 to 20 s apart in ride time. */
class FastForwardTest {

    private fun ride(maxStepMs: Long): Trip {
        val t = TripRecorder()
        var now = 0L
        var km = 100.0
        // An hour at 60 km/h, readings 18 s apart.
        repeat(200) {
            now += 18_000
            km += 60 * 18.0 / 3600
            t.onReading(BikeData(60, km.toInt(), Math.round(km * 10) / 10.0, 0.0, 4, null, 80), now, maxStepMs = maxStepMs)
        }
        return t.finish()!!
    }

    @Test fun fastForwardedRideCountsAsMoving() {
        val trip = ride(TripRecorder.MAX_STEP_MS * 12)
        assertEquals(3600.0, trip.movingSeconds.toDouble(), 60.0)
    }

    @Test fun realTimeStillTreatsLongGapsAsDropouts() {
        val trip = ride(TripRecorder.MAX_STEP_MS)
        assertEquals(0L, trip.movingSeconds)
    }
}
