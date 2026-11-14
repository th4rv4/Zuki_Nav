package com.th4rv4.zukinav.maint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintBookTest {
    private val t0 = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun chain(s: MaintState) = s.reminders.first { it.id == MaintBook.CHAIN_ID }
    private fun on() = MaintState(MaintBook.defaults().map { it.copy(enabled = true) })

    @Test fun defaultsAreOffAndDontCount() {
        var s = MaintBook.onOdo(MaintState(), 3_000, t0)
        s = MaintBook.onOdo(s, 9_000, t0)
        assertNull(chain(s).lastKm)
        assertTrue(MaintBook.check(s, t0, 50).second.isEmpty())
    }

    @Test fun turningOnCountsFromThatOdo() {
        var s = MaintBook.onOdo(MaintState(), 3_000, t0)
        s = MaintBook.onOdo(s, 3_789, t0)
        val c = MaintBook.setEnabled(chain(s), true, 3_789, t0)
        assertEquals(3_789, c.lastKm)
        assertEquals(500, MaintBook.status(c, 3_789, t0, 50).leftKm)
        // Turned off and on again later: a fresh start, nothing carried over.
        val again = MaintBook.setEnabled(MaintBook.setEnabled(c, false, 4_000, t0), true, 4_100, t0)
        assertEquals(4_100, again.lastKm)
    }

    @Test fun lastDoneTypedWhileOffIsKept() {
        val typed = Reminder(MaintBook.CHAIN_ID, "Chain Maintenance", everyKm = 500, lastKm = 3_689, enabled = false)
        val c = MaintBook.setEnabled(typed, true, 3_789, t0)
        assertEquals(400, MaintBook.status(c, 3_789, t0, 50).leftKm)
    }

    @Test fun firstOdoStartsCounting() {
        val s = MaintBook.onOdo(on(), 12_000, t0)
        assertEquals(12_000, chain(s).lastKm)
        val (_, alerts) = MaintBook.check(s, t0, 50)
        assertTrue(alerts.isEmpty())
    }

    @Test fun soonThenDueOnceEach() {
        var s = MaintBook.onOdo(on(), 12_000, t0)
        s = MaintBook.onOdo(s, 12_449, t0)
        assertTrue(MaintBook.check(s, t0, 50).second.isEmpty())
        s = MaintBook.onOdo(s, 12_455, t0)
        var r = MaintBook.check(s, t0, 50)
        assertEquals(listOf(Kind.SOON), r.second.map { it.kind })
        s = r.first
        assertTrue(MaintBook.check(s, t0, 50).second.isEmpty())
        s = MaintBook.onOdo(s, 12_500, t0)
        r = MaintBook.check(s, t0, 50)
        assertEquals(listOf(Kind.DUE), r.second.map { it.kind })
        s = MaintBook.onOdo(r.first, 12_600, t0)
        assertTrue(MaintBook.check(s, t0, 50).second.isEmpty())
        val st = MaintBook.status(chain(s), 12_600, t0, 50)
        assertEquals("100 KM OVER", MaintBook.chip(chain(s), st))
    }

    @Test fun jumpPastDueSendsOnlyDue() {
        var s = MaintBook.onOdo(on(), 12_000, t0)
        s = MaintBook.onOdo(s, 12_800, t0)
        assertEquals(listOf(Kind.DUE), MaintBook.check(s, t0, 50).second.map { it.kind })
    }

    @Test fun snoozeRemindsAgainAfter100Km() {
        var s = MaintBook.onOdo(on(), 12_000, t0)
        s = MaintBook.check(MaintBook.onOdo(s, 12_510, t0), t0, 50).first
        val snoozed = MaintBook.snooze(chain(s), 12_510)!!
        s = s.copy(reminders = s.reminders.map { if (it.id == snoozed.id) snoozed else it })
        assertTrue(MaintBook.check(MaintBook.onOdo(s, 12_600, t0), t0, 50).second.isEmpty())
        val r = MaintBook.check(MaintBook.onOdo(s, 12_610, t0), t0, 50)
        assertEquals(listOf(Kind.DUE), r.second.map { it.kind })
        assertNull(chain(r.first).snoozeUntilKm)
    }

    @Test fun doneRestartsCount() {
        var s = MaintBook.onOdo(on(), 12_000, t0)
        s = MaintBook.check(MaintBook.onOdo(s, 12_520, t0), t0, 50).first
        val d = MaintBook.done(chain(s), 12_520, t0)
        assertEquals(0, d.stage)
        val st = MaintBook.status(d, 12_520, t0, 50)
        assertEquals(500, st.leftKm)
        assertEquals("500 KM", MaintBook.chip(d, st))
    }

    @Test fun serviceWithoutIntervalNeverAlerts() {
        var s = MaintBook.onOdo(on(), 12_000, t0)
        s = MaintBook.onOdo(s, 99_000, t0 + 900 * day)
        val alerts = MaintBook.check(s, t0 + 900 * day, 50).second
        assertTrue(alerts.none { it.reminder.id == MaintBook.SERVICE_ID })
    }

    @Test fun turnedOffSendsNothing() {
        var s = MaintBook.onOdo(on(), 12_000, t0)
        s = s.copy(reminders = s.reminders.map { MaintBook.setEnabled(it, false, 12_000, t0) })
        s = MaintBook.onOdo(s, 12_800, t0)
        assertTrue(MaintBook.check(s, t0, 50).second.isEmpty())
        assertEquals("OFF", MaintBook.chip(chain(s), MaintBook.status(chain(s), 12_800, t0, 50)))
    }

    @Test fun monthsAloneComeDue() {
        val r = Reminder(9, "Coolant", everyMonths = 6, lastKm = 12_000, lastAt = t0)
        val s = MaintState(listOf(r), 12_100)
        assertTrue(MaintBook.check(s, t0 + 170 * day, 50).second.isEmpty())
        val soon = MaintBook.check(s, t0 + 178 * day, 50)
        assertEquals(listOf(Kind.SOON), soon.second.map { it.kind })
        val due = MaintBook.check(soon.first, t0 + 190 * day, 50)
        assertEquals(listOf(Kind.DUE), due.second.map { it.kind })
        assertEquals("OVERDUE", MaintBook.chip(r, MaintBook.status(r, 12_100, t0 + 190 * day, 50)))
    }
}
