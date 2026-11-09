package com.th4rv4.zukinav.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/**
 * Golden frames generated from a line-by-line Java port of Suzuki Ride
 * Connect 8.0's own frame code. The port was also diffed against
 * [DashDistance] for every metre from 0 to 150 km with zero mismatches.
 */
class SuzukiFramesTest {

    @Test fun navFrameMatchesOriginal() {
        val f = SuzukiFrames.navigation(
            iconId = ClusterIcon.TURN_RIGHT.id,
            toTurn = DashDistance.fromMeters(356.0, roundDownTo10 = true),
            eta = SuzukiFrames.etaField(LocalTime.of(19, 42), use24h = false),
            remaining = DashDistance.fromMeters(12_345.0),
        )
        assertEquals(
            "A5 31 04 FF 30 33 35 30 4D 30 37 34 32 50 4D FF FF FF 31 32 2E 33 4B 31 31 FF FF FF 1E 7F",
            SuzukiFrames.hex(f),
        )
    }

    @Test fun navStatusOtherThan135ShowsPlaceholder() {
        val f = SuzukiFrames.navigation(
            ClusterIcon.TURN_LEFT.id, DashDistance.ZERO, "000000", DashDistance.ZERO,
            status = SuzukiFrames.NavStatus.GPS_LOST,
        )
        assertEquals(ClusterIcon.STATUS_PLACEHOLDER, f[2].toInt())
    }

    @Test fun endNavigationFrameClearsActiveFlag() {
        val f = SuzukiFrames.navigation(8, DashDistance.ZERO, "000000", DashDistance.ZERO, active = false)
        assertEquals('0'.code, f[24].toInt())
    }

    @Test fun checksumSumAndInverted() {
        val f = ByteArray(30)
        for (i in 1..27) f[i] = 0x10
        assertEquals(0xB0.toByte(), SuzukiFrames.checksum(f, SuzukiFrames.Checksum.SUM))
        assertEquals(0x4F.toByte(), SuzukiFrames.checksum(f, SuzukiFrames.Checksum.INVERTED))
    }

    @Test fun helloFrame() {
        assertEquals(
            "A5 36 41 74 68 61 72 76 61 00 00 00 00 00 00 00 00 00 00 00 00 00 FF FF FF FF FF 52 4A 7F",
            SuzukiFrames.hex(SuzukiFrames.hello("Atharva", firstPairing = false)),
        )
    }

    @Test fun statusFrame() {
        val f = SuzukiFrames.status(LocalTime.of(13, 7, 59), speedAlertKmh = 0, signalBars = 2)
        assertEquals(
            "A5 33 31 4E FF FF FF 32 30 31 30 37 35 39 4E 4E FF FF FF FF FF 00 00 01 FF FF FF FF AB 7F",
            SuzukiFrames.hex(f),
        )
    }

    @Test fun distances() {
        assertEquals(DashDistance("0350", 'M'), DashDistance.fromMeters(356.0, true))
        assertEquals(DashDistance("0356", 'M'), DashDistance.fromMeters(356.0))
        assertEquals(DashDistance("02.5", 'K'), DashDistance.fromMeters(2_580.0))
        assertEquals(DashDistance("10.1", 'K'), DashDistance.fromMeters(10_150.0))
        assertEquals(DashDistance("0123", 'K'), DashDistance.fromMeters(123_400.0))
    }

    @Test fun eta() {
        assertEquals("0742PM", SuzukiFrames.etaField(LocalTime.of(19, 42), false))
        assertEquals("1205AM", SuzukiFrames.etaField(LocalTime.of(0, 5), false))
        assertEquals("001942", SuzukiFrames.etaField(LocalTime.of(19, 42), true))
    }

    @Test fun aSeriesStatusFrame() {
        val f = SuzukiFrames.status(
            LocalTime.of(13, 7, 59), signalBars = 0,
            mode = SuzukiFrames.Checksum.INVERTED, layout = ClusterModel.StatusLayout.A,
        )
        assertEquals(
            "A5 33 31 4E FF FF FF 30 30 31 30 37 35 39 4E 4E FF FF FF FF FF FF FF FF FF FF FF FF 5A 7F",
            SuzukiFrames.hex(f),
        )
    }

    @Test fun cSeriesEtaLeadingSpace() {
        val f = SuzukiFrames.navigation(
            ClusterIcon.TURN_RIGHT.id, DashDistance.fromMeters(356.0, true), "0742PM", DashDistance.fromMeters(2_345.0),
            etaLeadingSpace = true,
        )
        assertEquals(
            "A5 31 04 FF 30 33 35 30 4D 20 37 34 32 50 4D FF FF FF 30 32 2E 33 4B 31 31 FF FF FF 0D 7F",
            SuzukiFrames.hex(f),
        )
    }

    // Expected bytes from Suzuki Ride Connect's own frame code.
    @Test fun callFrames() {
        assertEquals(
            "A5 32 4D 6F 6D 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 4E 31 FF FF FF FF D6 7F",
            SuzukiFrames.hex(SuzukiFrames.call("Mom", ringing = true)),
        )
        assertEquals(
            "A5 34 FF 02 4D 6F 6D 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 4E FF FF FF A9 7F",
            SuzukiFrames.hex(SuzukiFrames.missedCall("Mom", 2)),
        )
        assertEquals(
            "A5 35 FF 4E 03 4D 6F 6D 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 57 FF FF 03 7F",
            SuzukiFrames.hex(SuzukiFrames.message("Mom", 3, whatsapp = true)),
        )
    }
}
