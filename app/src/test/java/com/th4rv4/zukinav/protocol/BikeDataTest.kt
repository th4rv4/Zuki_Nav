package com.th4rv4.zukinav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BikeDataTest {

    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // Received from a Gixxer SF 250 dash.
    private val sf250 = "A5 37 30 30 30 30 30 33 32 39 32 30 30 30 37 34 38 30 30 34 37 31 31 4E 35 26 FF FF FE 7F"

    @Test fun gixxerFrame() {
        assertEquals(BikeData(0, 3292, 74.8, 471.1, 5, null, 38), BikeData.parse(bytes(sf250)))
    }

    @Test fun badChecksumIgnored() {
        assertNull(BikeData.parse(bytes(sf250.replace("FE 7F", "FD 7F"))))
    }

    @Test fun otherFramesIgnored() {
        assertNull(BikeData.parse(SuzukiFrames.hello("Mom", true)))
    }

    @Test fun electricBattery() {
        val f = bytes(sf250)
        f[24] = (64 + 54).toByte()
        f[28] = SuzukiFrames.checksum(f, SuzukiFrames.Checksum.SUM)
        assertEquals(54, BikeData.parse(f)!!.batteryPercent)
        assertNull(BikeData.parse(f)!!.fuelBars)
    }

    @Test fun engineTemperature() {
        assertEquals(38, BikeData.parse(bytes(sf250))!!.engineTempC)
        val f = bytes(sf250)
        f[25] = 0xFF.toByte()
        f[28] = SuzukiFrames.checksum(f, SuzukiFrames.Checksum.SUM)
        assertNull(BikeData.parse(f)!!.engineTempC)
    }

    @Test fun saveAndLoad() {
        val d = BikeData(0, 3292, 74.8, 471.1, 5, null, 86)
        assertEquals(d, BikeData.decode(d.encode()))
        // Saved before engine temperature was added.
        assertEquals(BikeData(0, 3292, 74.8, 471.1, 5, null), BikeData.decode("3292;74.8;471.1;5;"))
        assertNull(BikeData.decode("garbage"))
    }
}
