package com.th4rv4.zukinav.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How quickly a missed call reaches the dash. */
class CallEndTest {
    private fun call(key: String, type: Int, name: String = "Mom") =
        Notice(key, "com.google.android.dialer", "call", type, name, name, null, ongoing = true, groupSummary = false)

    @Test fun missedNoticeEndsTheWaitStraightAway() {
        val t = AlertTracker(phoneState = { AlertTracker.PhoneState.RINGING })
        t.posted(call("c", 1), 0)
        t.removed(call("c", 1), 2_000)
        t.tick(4_000)
        val notice = Notice("m", call("c", 1).pkg, "missed_call", 0, null, "Missed call", "Mom", false, false)
        assertEquals(listOf(Alert.Missed("Mom", 1, false)), t.posted(notice, 24_000))
        assertTrue(!t.hasPending)
    }
}
