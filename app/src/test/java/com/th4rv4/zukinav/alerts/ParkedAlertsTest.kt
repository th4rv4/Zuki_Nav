package com.th4rv4.zukinav.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedAlertsTest {

    private val min = 60_000L
    private val at = 1_000_000L

    @Test fun neverKeepsNothing() {
        assertFalse(ParkedAlerts.keeps(ParkedAlerts.NEVER, at, at + min))
    }

    @Test fun halfHour() {
        assertTrue(ParkedAlerts.keeps(ParkedAlerts.MIN_30, at, at + 20 * min))
        assertTrue(ParkedAlerts.keeps(ParkedAlerts.MIN_30, at, at + 30 * min))
        assertFalse(ParkedAlerts.keeps(ParkedAlerts.MIN_30, at, at + 31 * min))
    }

    @Test fun hour() {
        assertTrue(ParkedAlerts.keeps(ParkedAlerts.HOUR_1, at, at + 45 * min))
        assertFalse(ParkedAlerts.keeps(ParkedAlerts.HOUR_1, at, at + 61 * min))
    }

    @Test fun alwaysKeepsOvernight() {
        assertTrue(ParkedAlerts.keeps(ParkedAlerts.ALWAYS, at, at + 17 * 60 * min))
    }

    @Test fun unknownChoiceIsNever() {
        assertFalse(ParkedAlerts.keeps(9, at, at + min))
    }
}
