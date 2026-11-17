package com.th4rv4.zukinav.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caller labels and how quickly a missed call reaches the dash. */
class CallEndTest {
    private fun call(key: String, type: Int, name: String = "Mom") =
        Notice(key, "com.google.android.dialer", "call", type, name, name, null, ongoing = true, groupSummary = false)

    @Test fun networkNameWithoutTheLikelyLabel() {
        val t = AlertTracker()
        assertEquals(listOf(Alert.Ringing("Purushottam Agnihotri", false)), t.posted(call("c", 1, "Purushottam Agnihotri"), 0))
        // The phone app adds its "Likely:" guess label a moment later: same name, nothing new.
        assertEquals(emptyList<Alert>(), t.posted(call("c", 1, "Likely: Purushottam Agnihotri"), 300))
        assertEquals("+91 62300 40764", AlertTracker.cleanCaller("\u202A+91 62300 40764\u202C"))
    }

    @Test fun missedNoticeEndsTheWaitStraightAway() {
        val t = AlertTracker(phoneState = { AlertTracker.PhoneState.RINGING })
        t.posted(call("c", 1), 0)
        t.removed(call("c", 1), 2_000)
        t.tick(4_000)
        val notice = Notice("m", call("c", 1).pkg, "missed_call", 0, null, "Missed call", "Mom", false, false)
        assertEquals(listOf(Alert.Missed("Mom", 1, false)), t.posted(notice, 24_000))
        assertTrue(!t.hasPending)
    }

    @Test fun numbersFitTheDash() {
        assertEquals("62300 40764", AlertTracker.forDash("\u202A+91 62300 40764\u202C"))
        assertEquals("62300 40764", AlertTracker.forDash("+916230040764"))
        assertEquals("62300 40764", AlertTracker.forDash("06230040764"))
        assertEquals("62300 40764", AlertTracker.forDash("916230040764"))
        assertEquals("41555 52671", AlertTracker.forDash("+1 415-555-2671"))
        assertEquals("501234567", AlertTracker.forDash("+971 50 123 4567"))
        assertEquals("7700900123", AlertTracker.forDash("+44 7700 900123").replace(" ", ""))
        assertEquals("Mom", AlertTracker.forDash("Mom"))
        val t = AlertTracker()
        assertEquals(listOf(Alert.Ringing("62300 40764", false)), t.posted(call("c", 1, "\u202A+91 62300 40764\u202C"), 0))
    }
}
