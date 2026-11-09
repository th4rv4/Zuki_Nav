package com.th4rv4.zukinav.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTrackerTest {

    private fun call(key: String, type: Int, name: String = "Mom", pkg: String = "com.google.android.dialer") =
        Notice(key, pkg, "call", type, name, name, null, ongoing = true, groupSummary = false)

    private fun msg(key: String, title: String, text: String, pkg: String = "com.whatsapp") =
        Notice(key, pkg, "msg", 0, null, title, text, ongoing = false, groupSummary = false)

    @Test fun answeredCall() {
        val t = AlertTracker()
        assertEquals(listOf(Alert.Ringing("Mom", false)), t.posted(call("c", 1), 0))
        assertEquals(emptyList<Alert>(), t.posted(call("c", 1), 500))
        assertEquals(listOf(Alert.Answered("Mom", false)), t.posted(call("c", 2), 4_000))
        t.removed(call("c", 2), 60_000)
        assertEquals(emptyList<Alert>(), t.tick(70_000))
    }

    @Test fun missedCallAfterRinging() {
        val t = AlertTracker()
        t.posted(call("c", 1), 0)
        t.removed(call("c", 1), 20_000)
        assertEquals(emptyList<Alert>(), t.tick(20_500))
        assertEquals(listOf(Alert.Missed("Mom", 1, false)), t.tick(21_600))
        // The phone app's own missed-call notification is the same call.
        val notice = Notice("m", "com.google.android.dialer", "missed_call", 0, null, "Missed call", "Mom", false, false)
        assertEquals(emptyList<Alert>(), t.posted(notice, 22_000))
        // Clearing it on the phone clears the dash.
        assertEquals(listOf(Alert.ClearMissed), t.removed(notice, 90_000))
    }

    @Test fun ringingSwappedForOngoingIsAnswered() {
        val t = AlertTracker()
        t.posted(call("ring", 1), 0)
        t.removed(call("ring", 1), 3_000)
        assertEquals(listOf(Alert.Answered("Mom", false)), t.posted(call("talk", 2), 3_200))
        assertEquals(emptyList<Alert>(), t.tick(10_000))
    }

    @Test fun whatsAppCall() {
        val t = AlertTracker()
        assertEquals(listOf(Alert.Ringing("Raj", true)), t.posted(call("w", 1, "Raj", "com.whatsapp"), 0))
    }

    @Test fun missedNoticeWithoutRinging() {
        val t = AlertTracker()
        val n = Notice("m", "com.whatsapp", null, 0, null, "Raj", "Missed voice call", false, false)
        assertEquals(listOf(Alert.Missed("Raj", 1, true)), t.posted(n, 0))
    }

    @Test fun messagesCountUntilCleared() {
        val t = AlertTracker()
        assertEquals(listOf(Alert.Message("Mom", 1, true)), t.posted(msg("a", "Mom", "hi"), 0))
        // Reposted unchanged: not a new message.
        assertEquals(emptyList<Alert>(), t.posted(msg("a", "Mom", "hi"), 100))
        assertEquals(listOf(Alert.Message("Mom", 2, true)), t.posted(msg("a", "Mom (2 messages)", "where are you"), 200))
        assertEquals(listOf(Alert.Message("Raj", 3, true)), t.posted(msg("b", "Riders: Raj", "ride at 6?"), 300))
        assertEquals(emptyList<Alert>(), t.removed(msg("a", "Mom", ""), 400))
        assertEquals(listOf(Alert.ClearMessages), t.removed(msg("b", "Raj", ""), 500))
        assertEquals(listOf(Alert.Message("Mom", 1, true)), t.posted(msg("a", "Mom", "hi"), 600))
    }

    @Test fun smsFromDefaultApp() {
        val t = AlertTracker(defaultSmsPackage = { "com.example.sms" })
        assertEquals(listOf(Alert.Message("AX-HDFCBK", 1, false)),
            t.posted(Notice("s", "com.example.sms", null, 0, null, "AX-HDFCBK", "OTP", false, false), 0))
    }

    @Test fun ignoresOtherApps() {
        val t = AlertTracker()
        assertTrue(t.posted(msg("x", "Sale", "50% off", pkg = "com.shop"), 0).isEmpty())
        assertTrue(t.posted(Notice("g", "com.whatsapp", "msg", 0, null, "WhatsApp", "2 new", false, true), 0).isEmpty())
        // A WhatsApp message that mentions a missed call is still a message.
        assertEquals(listOf(Alert.Message("Mom", 1, true)), t.posted(msg("a", "Mom", "I missed call you"), 0))
    }
}
