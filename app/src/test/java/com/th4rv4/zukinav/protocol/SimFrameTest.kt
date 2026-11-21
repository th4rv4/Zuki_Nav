package com.th4rv4.zukinav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimFrameTest {
    @Test fun roundTrip() {
        val f = SimFrame(1_794_993_018_803L, 60.0, 28.6129123, 77.2295456, 98, ignitionOn = true, engineOn = false, headingDeg = 271)
        val bytes = f.encode()
        assertEquals(30, bytes.size)
        val back = SimFrame.parse(bytes)!!
        assertEquals(f.rideTime, back.rideTime)
        assertEquals(60.0, back.speedUp, 0.0)
        assertEquals(28.6129123, back.lat!!, 1e-7)
        assertEquals(77.2295456, back.lng!!, 1e-7)
        assertEquals(98, back.speedKmh)
        assertEquals(true, back.ignitionOn)
        assertEquals(false, back.engineOn)
        assertEquals(271, back.headingDeg)
        // Not a bike reading.
        assertNull(BikeData.parse(bytes))
    }

    @Test fun noPosition() {
        val back = SimFrame.parse(SimFrame(5_000L, 1.0, null, null, 0).encode())!!
        assertNull(back.lat)
    }

    @Test fun damagedFrameIgnored() {
        val b = SimFrame(5_000L, 10.0, 1.0, 2.0, 40).encode()
        b[5] = (b[5] + 1).toByte()
        assertNull(SimFrame.parse(b))
    }

    @Test fun moveOneKmNorthAndEast() {
        val (lat, lng) = SimFrame.move(32.0, 76.0, 0.0, 1.0)
        assertEquals(32.00898, lat, 1e-4)
        assertEquals(76.0, lng, 1e-9)
        val (lat2, lng2) = SimFrame.move(32.0, 76.0, 90.0, 1.0)
        assertEquals(32.0, lat2, 1e-9)
        assertEquals(76.01059, lng2, 1e-4)
    }
}
