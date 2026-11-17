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

    @Test fun businessSenders() {
        listOf("AD-HPGOVT-S", "VM-HDFCBK", "JD-ICICIT-T", "ax-amazon", "56767").forEach {
            assertTrue(it, AlertTracker.isBusinessSender(it))
        }
        listOf("Mom", "+91 98765 43210", "Raj Kumar", "9876543210").forEach {
            assertTrue(it, !AlertTracker.isBusinessSender(it))
        }
    }

    @Test fun skipsBusinessSmsOnlyWhenAsked() {
        val sms = Notice("s", "com.google.android.apps.messaging", "msg", 0, null, "AD-HPGOVT-S", "Update", false, false)
        assertEquals(1, AlertTracker().posted(sms, 0).size)
        val t = AlertTracker(skipBusinessSms = { true })
        assertTrue(t.posted(sms, 0).isEmpty())
        // People still come through, and WhatsApp is never filtered.
        assertEquals(1, t.posted(sms.copy(key = "p", title = "Mom"), 0).size)
        assertEquals(1, t.posted(msg("w", "VM-HDFCBK", "hi"), 0).size)
    }

    @Test fun networkNameSwappedForSavedNameWhileRinging() {
        val t = AlertTracker()
        // The phone app first shows the SIM's registered name, then the saved one.
        assertEquals(listOf(Alert.Ringing("Ramesh Chand", false)), t.posted(call("c", 1, "Ramesh Chand"), 0))
        assertEquals(listOf(Alert.Ringing("Nitin Cllg", false)), t.posted(call("c", 1, "Nitin Cllg"), 800))
        // Only once.
        assertEquals(emptyList<Alert>(), t.posted(call("c", 1, "Someone Else"), 1_200))
        t.removed(call("c", 1), 20_000)
        assertEquals(listOf(Alert.Missed("Nitin Cllg", 1, false)), t.tick(21_600))
    }

    @Test fun savedNameFromContactsWins() {
        val t = AlertTracker()
        val n = call("c", 1, "Ramesh Chand").copy(savedName = "Nitin Cllg", number = "+919876543210", contactsChecked = true)
        assertEquals(listOf(Alert.Ringing("Nitin Cllg", false)), t.posted(n, 0))
        // The phone app's later update can't change a name from the contacts.
        assertEquals(emptyList<Alert>(), t.posted(call("c", 1, "Nitin"), 800))
    }

    @Test fun missedNoticeUsesSavedName() {
        val t = AlertTracker()
        val n = Notice("m", "com.google.android.dialer", "missed_call", 0, null, "Missed call", "Ramesh Chand", false, false,
            savedName = "Nitin Cllg")
        assertEquals(listOf(Alert.Missed("Nitin Cllg", 1, false)), t.posted(n, 0))
    }

    @Test fun screenNameWithAndWithoutContacts() {
        val cnap = call("c", 1, "Ramesh Chand")
        // Without Contacts: goes by what the phone app shows.
        assertEquals("Ramesh Chand", cnap.copy(number = "+919876543210").screenName())
        // With Contacts and the number not saved: judged by the number, so it's unknown.
        val checked = cnap.copy(number = "+919876543210", contactsChecked = true)
        assertTrue(CallFilter.isUnknown(checked.screenName()))
        // Saved.
        assertEquals("Nitin Cllg", checked.copy(savedName = "Nitin Cllg").screenName())
    }

    @Test fun screenOffCallKeepsRingingUntilThePhoneStops() {
        var state = AlertTracker.PhoneState.RINGING
        val t = AlertTracker(phoneState = { state })
        t.posted(call("c", 1), 0)
        // The phone app drops its notification for the full-screen call page.
        t.removed(call("c", 1), 2_000)
        assertEquals(emptyList<Alert>(), t.tick(4_000))
        // Shown again while it rings.
        assertEquals(listOf(Alert.Ringing("Mom", false)), t.tick(20_000))
        assertTrue(t.hasPending)
        state = AlertTracker.PhoneState.IDLE
        assertEquals(listOf(Alert.Missed("Mom", 1, false)), t.tick(25_000))
    }

    @Test fun screenOffCallAnsweredOnTheCallPage() {
        var state = AlertTracker.PhoneState.RINGING
        val t = AlertTracker(phoneState = { state })
        t.posted(call("c", 1), 0)
        t.removed(call("c", 1), 2_000)
        t.tick(4_000)
        state = AlertTracker.PhoneState.IN_CALL
        assertEquals(listOf(Alert.Answered("Mom", false)), t.tick(9_000))
        assertTrue(!t.hasPending)
    }

    @Test fun ringingNotificationComingBackIsTheSameCall() {
        val state = AlertTracker.PhoneState.RINGING
        val t = AlertTracker(phoneState = { state })
        t.posted(call("c", 1), 0)
        t.removed(call("c", 1), 2_000)
        t.tick(4_000)
        // Screen turned on: a new ringing notification, no second alert.
        assertEquals(emptyList<Alert>(), t.posted(call("d", 1), 6_000))
        // Ringing again, not a missed call waiting.
        assertEquals(emptyList<Alert>(), t.tick(8_000).filterIsInstance<Alert.Missed>())
        assertEquals(listOf(Alert.Answered("Mom", false)), t.posted(call("d", 2), 9_000))
    }

    @Test fun answeredAfterLongRingWithOngoingNotification() {
        val t = AlertTracker(phoneState = { AlertTracker.PhoneState.RINGING })
        t.posted(call("c", 1), 0)
        t.removed(call("c", 1), 2_000)
        t.tick(4_000)
        assertEquals(listOf(Alert.Answered("Mom", false)), t.posted(call("o", 2), 10_000))
    }

    @Test fun ringingShownAgainWhileThePhoneRings() {
        var state = AlertTracker.PhoneState.RINGING
        val t = AlertTracker(phoneState = { state })
        t.posted(call("c", 1), 0)
        assertTrue(t.hasPending)
        assertEquals(emptyList<Alert>(), t.tick(5_000))
        assertEquals(listOf(Alert.Ringing("Mom", false)), t.tick(10_000))
        // Notification gone with the screen off; still ringing.
        t.removed(call("c", 1), 12_000)
        assertEquals(emptyList<Alert>(), t.tick(14_000))
        assertEquals(listOf(Alert.Ringing("Mom", false)), t.tick(20_000))
        assertEquals(listOf(Alert.Ringing("Mom", false)), t.tick(30_000))
        state = AlertTracker.PhoneState.IDLE
        assertEquals(listOf(Alert.Missed("Mom", 1, false)), t.tick(44_000))
        assertTrue(!t.hasPending)
    }

    @Test fun answeredCallIsNotShownAgain() {
        val t = AlertTracker()
        t.posted(call("c", 1), 0)
        t.posted(call("c", 2), 3_000)
        assertEquals(emptyList<Alert>(), t.tick(20_000))
        assertTrue(!t.hasPending)
    }

    @Test fun groupMessagesShowTheGroup() {
        val t = AlertTracker()
        val n = msg("g", "Gixxer Gang: ~ 💲🫀", "hi").copy(isGroup = true, groupName = "Gixxer Gang")
        // Pops up, but isn't kept as unread.
        assertEquals(listOf(Alert.Message("Gixxer Gang", 1, true, fleeting = true)), t.posted(n, 0))
        // A saved contact in a group shows the group too.
        val saved = msg("h", "Gixxer Gang: Prakhar SF250", "yo").copy(isGroup = true, groupName = "Gixxer Gang")
        assertEquals(listOf(Alert.Message("Gixxer Gang", 1, true, fleeting = true)), t.posted(saved, 0))
        // A chat message counts as the first unread.
        assertEquals(listOf(Alert.Message("Prakhar SF250", 1, true)), t.posted(msg("p", "Prakhar SF250", "on my way"), 0))
        // A group message now keeps the unread count as it is.
        val more = msg("g", "Gixxer Gang: Rahul", "ride?").copy(isGroup = true, groupName = "Gixxer Gang")
        assertEquals(listOf(Alert.Message("Gixxer Gang", 1, true)), t.posted(more, 0))
    }

    @Test fun unsavedSenderInADmShowsTheNumber() {
        val t = AlertTracker()
        val n = msg("d", "~ Rahul", "hello").copy(senderNumber = "+919876543210")
        assertEquals(listOf(Alert.Message("98765 43210", 1, true)), t.posted(n, 0))
        // No number on the notification: the name they chose, without the ~.
        assertEquals(listOf(Alert.Message("Rahul", 2, true)), t.posted(msg("e", "~ Rahul", "again"), 0))
        // Nothing the dash can draw.
        assertEquals(listOf(Alert.Message("WhatsApp", 3, true)), t.posted(msg("f", "~ 💲🫀", "hi"), 0))
    }

    @Test fun whatsAppsOwnNoticeIsSkipped() {
        val t = AlertTracker()
        assertTrue(t.posted(msg("w", "WhatsApp", "5 messages from 2 chats"), 0).isEmpty())
        assertEquals(1, t.posted(msg("p", "Prakhar SF250", "on my way"), 0).size)
    }

    @Test fun unreadableCallerShowsTheNumber() {
        val t = AlertTracker()
        val n = call("c", 1, "माँ").copy(number = "+919876543210")
        assertEquals(listOf(Alert.Ringing("98765 43210", false)), t.posted(n, 0))
    }
}
