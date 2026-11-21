package com.th4rv4.zukidashsim

import com.th4rv4.zukinav.protocol.BikeData
import com.th4rv4.zukinav.protocol.SuzukiFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeTest {
    private fun running(): Bike = Bike().apply {
        ignitionOn = true
        engineOn = true
    }

    @Test fun readingIsWhatTheAppReads() {
        val b = running().apply { odoKm = 12_345.6; tripAKm = 12.34; tripBKm = 999.9; fuelL = 6.0; engineC = 71.4 }
        val r = BikeData.parse(b.reading())!!
        assertEquals(12_345, r.odoKm)
        assertEquals(12.3, r.tripAKm, 1e-9)
        assertEquals(999.9, r.tripBKm, 1e-9)
        assertEquals(3, r.fuelBars)
        assertEquals(71, r.engineTempC)
    }

    @Test fun ridesAnHourAtSixty() {
        val b = running()
        b.targetKmh = 60
        b.step(60_000) // get up to speed
        val odo = b.odoKm
        repeat(36) { b.step(100_000) }
        assertEquals(60.0, b.odoKm - odo, 0.01)
    }

    @Test fun warmsUpStandingStillAndCoolsWhenOff() {
        val b = running()
        b.step(60_000)
        assertTrue(b.engineC > Bike.AMBIENT_C + 10) // enough for Engine Warm Up
        b.step(10 * 60_000)
        val hot = b.engineC
        b.engineOn = false
        b.step(2 * 60 * 60_000)
        assertTrue(b.engineC < hot - 40)
    }

    @Test fun noEngineWithoutIgnitionOrFuel() {
        val b = Bike()
        b.engineOn = true
        assertFalse(b.engineOn)
        b.ignitionOn = true
        b.fuelL = 0.0
        b.engineOn = true
        assertFalse(b.engineOn)
    }

    @Test fun phoneFramesInWords() {
        val call = FrameText.describe(SuzukiFrames.call("ASHA", ringing = true))
        assertEquals("Call", call.screen)
        assertEquals("phone ASHA: ringing", call.text)
        val msg = FrameText.describe(SuzukiFrames.message("98765 43210", 2, whatsapp = true))
        assertEquals("2 unread, WhatsApp 98765 43210 (pops up)", msg.text)
    }

    @Test fun longRidesComeRoundInALoop() {
        val b = running().apply { fuelL = 1_000.0 }
        val lat0 = b.lat
        val lng0 = b.lng
        b.targetKmh = 100
        var farthest = 0.0
        repeat(2_000) {
            b.step(60_000)
            val dLat = (b.lat - lat0) * 111.0
            val dLng = (b.lng - lng0) * 111.0 * Math.cos(Math.toRadians(lat0))
            farthest = maxOf(farthest, Math.hypot(dLat, dLng))
        }
        // 3000+ km of riding never gets more than about 70 km from home.
        assertTrue("got $farthest km away", farthest < 90)
    }
}
