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

    private fun msg(key: String, title: String, text: String, pkg: String = "com.whatsapp") =
        Notice(key, pkg, "msg", 0, null, title, text, ongoing = false, groupSummary = false)

    @Test fun unsavedMissedCallIsNotKept() {
        val t = AlertTracker(missedSavedOnly = { true })
        t.posted(call("c", 1, "+91 62300 40764"), 0)
        t.removed(call("c", 1, "+91 62300 40764"), 10_000)
        assertEquals(listOf(Alert.Missed("62300 40764", 1, false, fleeting = true)), t.tick(12_000))
        // A saved contact's missed call counts as the first.
        t.posted(call("d", 1, "Mom"), 20_000)
        t.removed(call("d", 1, "Mom"), 30_000)
        assertEquals(listOf(Alert.Missed("Mom", 1, false)), t.tick(32_000))
    }

    @Test fun networkNameNotSavedCountsAsUnknownWithContacts() {
        val t = AlertTracker(missedSavedOnly = { true })
        val n = call("c", 1, "Purushottam Agnihotri").copy(number = "+916230040764", contactsChecked = true)
        t.posted(n, 0)
        t.removed(n, 10_000)
        assertEquals(true, (t.tick(12_000).single() as Alert.Missed).fleeting)
    }

    @Test fun switchOffKeepsEveryMissedCall() {
        val t = AlertTracker(missedSavedOnly = { false })
        t.posted(call("c", 1, "+91 62300 40764"), 0)
        t.removed(call("c", 1, "+91 62300 40764"), 10_000)
        assertEquals(listOf(Alert.Missed("62300 40764", 1, false)), t.tick(12_000))
    }

    @Test fun unsavedChatsAndSmsPopUpButAreNotUnread() {
        val t = AlertTracker(messagesSavedOnly = { true })
        assertEquals(listOf(Alert.Message("62300 40764", 1, true, fleeting = true)), t.posted(msg("b", "\u202A+91 62300 40764\u202C", "hello"), 0))
        assertEquals(listOf(Alert.Message("Rahul", 1, true, fleeting = true)), t.posted(msg("a", "~ Rahul", "hi"), 0))
        assertEquals(
            listOf(Alert.Message("62300 40764", 1, false, fleeting = true)),
            t.posted(msg("s", "+916230040764", "otp", pkg = "com.google.android.apps.messaging"), 0),
        )
        // Saved contacts count as unread.
        assertEquals(listOf(Alert.Message("Mummy", 1, true)), t.posted(msg("m", "Mummy", "khana"), 0))
        // An unsaved number now keeps the count as it is.
        assertEquals(listOf(Alert.Message("62300 40764", 1, true)), t.posted(msg("b", "+91 62300 40764", "again"), 0))
    }

    @Test fun switchOffCountsUnsavedAsUnread() {
        val t = AlertTracker(messagesSavedOnly = { false })
        assertEquals(listOf(Alert.Message("62300 40764", 1, true)), t.posted(msg("b", "+91 62300 40764", "hello"), 0))
    }

    @Test fun yourOwnReplyIsNotANewMessage() {
        val t = AlertTracker()
        assertEquals(listOf(Alert.Message("Mummy", 1, true)), t.posted(msg("m", "Mummy", "khana"), 0))
        // The auto-reply lands in the same chat notification.
        assertTrue(t.posted(msg("m", "Mummy", "Riding, will reply soon").copy(fromMe = true), 500).isEmpty())
        assertTrue(t.posted(msg("m", "You", "Riding, will reply soon"), 600).isEmpty())
        // Her next message still counts.
        assertEquals(listOf(Alert.Message("Mummy", 2, true)), t.posted(msg("m", "Mummy", "ok"), 9_000))
    }
}
