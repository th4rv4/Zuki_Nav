package com.th4rv4.zukinav.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelBookTest {

    private val sf250 = FuelBook.specFor("Gixxer 250 / SF 250 / FFV")!!

    /** Feeds readings every 5 s; each pair is (odo, bars). */
    private fun ride(start: FuelState, vararg readings: Pair<Int, Int>): Pair<FuelState, List<Refill>> {
        var s = start
        val refills = ArrayList<Refill>()
        var t = 1_000L
        for ((odo, bars) in readings) {
            val (next, r) = FuelBook.onReading(s, odo, bars, t)
            s = next
            if (r != null) refills += r
            t += 5_000
        }
        return s to refills
    }

    /** Holds [bars] at [odo] for three readings, as a steady gauge does. */
    private fun hold(odo: Int, bars: Int) = arrayOf(odo to bars, odo to bars, odo to bars)

    @Test fun specs() {
        assertEquals(12.0, sf250.tankLitres, 0.0)
        assertEquals(2.4, FuelBook.specFor("V-Strom SX")!!.reserveLitres, 0.0)
        assertEquals(5.5, FuelBook.specFor("Burgman Street")!!.tankLitres, 0.0)
        assertNull(FuelBook.specFor("e-ACCESS"))
    }

    @Test fun learnsKmPerBarFromLiveChanges() {
        // The gauge drops while riding: the ODO has just moved on at the old level.
        val (s, _) = ride(FuelState(), 1000 to 6, *hold(1000, 6), 1090 to 6, *hold(1090, 5), 1150 to 5, *hold(1150, 4))
        // Bar 6 started before the app was watching, so it is not learned.
        assertEquals(0, s.bars[6].samples)
        // Bar 5 was seen from start to end: 60 km.
        assertEquals(60.0, s.bars[5].average!!, 0.0)
        assertEquals(4, s.gaugeBars)
    }

    @Test fun flickerIsIgnored() {
        val (s, refills) = ride(FuelState(), 1000 to 4, 1000 to 4, 1001 to 3, 1001 to 4, 1001 to 4, 1002 to 5, 1002 to 4)
        assertEquals(4, s.gaugeBars)
        assertTrue(refills.isEmpty())
    }

    @Test fun refillDetectedAndTopBarLearnedAfterIt() {
        val (s, refills) = ride(FuelState(), 1000 to 1, *hold(1000, 1), *hold(1000, 6), 1088 to 6, *hold(1088, 5))
        assertEquals(1, refills.size)
        assertEquals(1, refills[0].barsBefore)
        assertEquals(6, refills[0].barsAfter)
        assertEquals(1000, refills[0].odoKm)
        assertEquals(88.0, s.bars[6].average!!, 0.0)
    }

    @Test fun changeMissedWhileDisconnectedIsNotLearned() {
        // Bar 5 starts live, then the app misses 40 km and finds bar 4.
        val (s, _) = ride(FuelState(), 1000 to 6, *hold(1000, 6), 1090 to 6, *hold(1090, 5), 1150 to 4)
        assertEquals(0, s.bars[5].samples)
        assertFalse(s.segClean)
    }

    @Test fun refillAfterGapStillCounts() {
        val (_, refills) = ride(FuelState(), 1000 to 2, 1130 to 6)
        assertEquals(1, refills.size)
    }

    @Test fun mileageFullToFull() {
        val refills = listOf(
            Refill(1, 1000, 1, 6, litres = 10.0, amount = 1000.0, full = true),
            Refill(2, 1200, 3, 5, litres = 4.0, amount = 420.0, full = false),
            Refill(3, 1400, 2, 6, litres = 7.0, amount = 735.0, full = true),
        )
        val m = FuelBook.mileage(refills)
        // 400 km on 4 + 7 litres.
        assertEquals(400.0 / 11.0, m.average!!, 0.001)
        assertEquals(1, m.perTank.size)
        assertEquals((420.0 + 735.0) / 400.0, m.costPerKm!!, 0.001)
    }

    @Test fun skippedRefillBreaksTheStretch() {
        val refills = listOf(
            Refill(1, 1000, 1, 6, litres = 10.0, full = true),
            Refill(2, 1200, 3, 5),
            Refill(3, 1400, 2, 6, litres = 7.0, full = true),
            Refill(4, 1750, 1, 6, litres = 10.0, full = true),
        )
        val m = FuelBook.mileage(refills)
        assertEquals(listOf(35.0), m.perTank)
        assertNull(m.costPerKm)
    }

    @Test fun litresFromAmountUsesLastPrice() {
        val refills = listOf(Refill(1, 1000, 1, 6, litres = 10.0, amount = 1050.0, full = true))
        assertEquals(5.0, FuelBook.litresFromAmount(refills, 525.0, 2)!!, 0.001)
        assertNull(FuelBook.litresFromAmount(emptyList(), 525.0, 2))
    }

    @Test fun rangeFromTankSizeUntilLearned() {
        val s = FuelState(gaugeBars = 6, lastOdoKm = 1000, segBar = 6, segStartKm = 1000, segClean = true)
        val r = FuelBook.range(s, 1000, sf250, FuelBook.mileage(emptyList()))!!
        // 12 L at 35 km/l.
        assertEquals(420, r.km)
        assertTrue(r.estimate)
    }

    @Test fun rangeUsesLearnedBars() {
        val learned = List(FuelBook.MAX_BARS + 1) { BarStat(60.0, 1) }
        val s = FuelState(bars = learned, gaugeBars = 3, lastOdoKm = 1000, segBar = 3, segStartKm = 980, segClean = true)
        val m = FuelBook.mileage(listOf(Refill(1, 0, 1, 6, litres = 10.0, full = true), Refill(2, 350, 1, 6, litres = 10.0, full = true)))
        val r = FuelBook.range(s, 1000, sf250, m)!!
        // 40 km left on bar 3, 60 on bar 2, reserve 2 L x 35 km/l on bar 1.
        assertEquals(40 + 60 + 70, r.km)
        assertFalse(r.estimate)
        assertNotNull(m.average)
    }
}
