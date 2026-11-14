package com.th4rv4.zukinav.thermal

import com.th4rv4.zukinav.thermal.ThermalSession.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalSessionTest {

    /** Feeds one reading every 5 s from [start]; returns the events per reading. */
    private fun ThermalSession.feed(temps: List<Int>, start: Long = 1_000, warm: Int = 70, hot: Int = 110) =
        temps.mapIndexed { i, t -> onReading(t, start + i * 5_000L, warm, hot) }

    @Test fun warmedUpOnceFromCold() {
        val s = ThermalSession()
        val ev = s.feed(listOf(30, 50, 69, 70, 72, 65, 75))
        assertEquals(setOf(Event.WARMED_UP), ev[3])
        assertTrue(ev.drop(4).all { it.isEmpty() })
        assertEquals(15_000L, s.warmUpMs)
    }

    @Test fun startedWarmNoWarmedUpAlert() {
        val s = ThermalSession()
        val ev = s.feed(listOf(80, 85, 90))
        assertTrue(ev.all { it.isEmpty() })
        assertNull(s.warmUpMs)
    }

    @Test fun overheatRepeatsEveryFiveMinutesAndResetsWhenCool() {
        val s = ThermalSession()
        // 108, then 112 for 6 minutes (72 readings).
        val ev = s.feed(listOf(108) + List(72) { 112 } + listOf(106, 111))
        val hot = ev.indices.filter { Event.OVERHEAT in ev[it] }
        // First at reading 1, again 5 minutes (60 readings) later, and after cooling below 107.
        assertEquals(listOf(1, 61, 74), hot)
        // Above hot for the 72 steps that start at 112 (the step out of 106 doesn't count).
        assertEquals(72 * 5_000L, s.aboveHotMs)
    }

    @Test fun statsAndWindow() {
        val s = ThermalSession()
        s.feed(listOf(30, 60, 90, 100))
        assertEquals(100, s.maxC)
        assertEquals(70, s.averageC)
        // 40 minutes later in the same ride: only the last 30 minutes are kept.
        s.feed(List(480) { 90 }, start = 21_000)
        assertTrue(s.samples.first().at >= s.lastAt - ThermalSession.WINDOW_MS)
    }

    @Test fun ignitionOffStartsNewRide() {
        val s = ThermalSession()
        s.feed(listOf(30, 75))
        s.feed(listOf(40), start = 21_000 + ThermalSession.GAP_MS)
        assertEquals(40, s.maxC)
        assertNull(s.warmUpMs)
        assertEquals(1, s.samples.size)
    }

    @Test fun limitsOffNoEvents() {
        val s = ThermalSession()
        val ev = s.feed(listOf(30, 120, 130), warm = 0, hot = 0)
        assertTrue(ev.all { it.isEmpty() })
        assertEquals(0L, s.aboveHotMs)
    }
}
