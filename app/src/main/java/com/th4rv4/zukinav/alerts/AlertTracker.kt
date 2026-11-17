package com.th4rv4.zukinav.alerts

/**
 * The parts of a phone notification that decide whether the dash shows a
 * call or message alert. Kept free of Android types so it can be tested.
 */
data class Notice(
    val key: String,
    val pkg: String,
    val category: String?,
    /** Notification.CallStyle type: 1 incoming, 2 ongoing, 3 screening; 0 when not a CallStyle. */
    val callType: Int,
    /** Caller name from the CallStyle person, if any. */
    val callPerson: String?,
    val title: String?,
    val text: String?,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    /** The name the caller is saved under in the phone's contacts, when the app may read them. */
    val savedName: String? = null,
    /** The caller's number, when the notification carries it. */
    val number: String? = null,
    /** The number was looked up in the contacts, so no [savedName] means it isn't saved. */
    val contactsChecked: Boolean = false,
) {
    /**
     * Who Reject Unknown Callers judges the call by. With the contacts read,
     * a number that isn't saved counts as unknown even when the network sends
     * a name with it.
     */
    fun screenName(): String? = savedName
        ?: number?.takeIf { contactsChecked }
        ?: callPerson?.takeIf { it.isNotBlank() }
        ?: title
}

/** What to show on the dash. */
sealed interface Alert {
    data class Ringing(val caller: String, val whatsapp: Boolean) : Alert
    data class Answered(val caller: String, val whatsapp: Boolean) : Alert
    data class Missed(val caller: String, val count: Int, val whatsapp: Boolean) : Alert
    data class Message(val sender: String, val count: Int, val whatsapp: Boolean) : Alert
    /** The rider has dealt with the missed calls on the phone. */
    data object ClearMissed : Alert
    /** The rider has read or dismissed the messages on the phone. */
    data object ClearMessages : Alert
}

/**
 * Turns notifications into dash alerts, the way Suzuki Ride Connect uses
 * the phone's call state and incoming messages:
 *  - an incoming call shows the caller while it rings
 *  - answering it tells the dash the call was picked up
 *  - a call that stops ringing without being answered is a missed call,
 *    counted until the rider clears the missed-call notification
 *  - each new WhatsApp or SMS message shows the sender and the unread count,
 *    until the rider clears those notifications
 *
 * Not thread safe: call it from one thread.
 */
class AlertTracker(
    /** Package of the phone's default SMS app, if known. */
    private val defaultSmsPackage: () -> String? = { null },
    /** Leave out SMS from bank, OTP, government and promo senders. */
    private val skipBusinessSms: () -> Boolean = { false },
    /** Whether the phone is ringing or on a call right now, whatever its notifications say. */
    private val phoneState: () -> PhoneState = { PhoneState.IDLE },
    private val log: (String) -> Unit = {},
) {

    enum class PhoneState { IDLE, RINGING, IN_CALL }

    private class Call(
        var caller: String,
        val pkg: String,
        val whatsapp: Boolean,
        var answered: Boolean,
        /** The name came from the contacts, so later updates can't improve it. */
        var saved: Boolean = false,
        var renamed: Boolean = false,
        /** When it started ringing, and when the dash was last told. */
        var since: Long = 0L,
        var shownAt: Long = 0L,
        /** Still being shown again on the dash; stops after [MAX_RING_WAIT_MS]. */
        var repeating: Boolean = true,
    )

    private class PendingMiss(val call: Call, val at: Long) {
        var waitingLogged = false
    }

    private val calls = HashMap<String, Call>()
    private val pendingMisses = ArrayList<PendingMiss>()
    private var missedCount = 0
    private val missedNotices = HashSet<String>()
    private var lastMissAt = Long.MIN_VALUE / 2

    private var messageCount = 0
    /** Message notifications still on the phone, with what they last said. */
    private val messageNotices = HashMap<String, String>()

    fun posted(n: Notice, now: Long): List<Alert> {
        if (n.groupSummary) return emptyList()
        val out = ArrayList<Alert>()
        when {
            isMissedCallNotice(n) -> onMissedNotice(n, now, out)
            isCall(n) -> onCall(n, now, out)
            isMessage(n) -> onMessage(n, out)
        }
        return out
    }

    fun removed(n: Notice, now: Long): List<Alert> {
        val out = ArrayList<Alert>()
        calls.remove(n.key)?.let { call ->
            // Some phone apps swap the ringing notification for a new
            // "ongoing call" one; wait a moment before calling it missed.
            if (!call.answered) pendingMisses.add(PendingMiss(call, now))
        }
        if (missedNotices.remove(n.key) && missedNotices.isEmpty() && missedCount > 0) {
            missedCount = 0
            out.add(Alert.ClearMissed)
        }
        if (messageNotices.remove(n.key) != null && messageNotices.isEmpty() && messageCount > 0) {
            messageCount = 0
            out.add(Alert.ClearMessages)
        }
        return out
    }

    /** Settles calls that stopped ringing at least [MISS_DELAY_MS] ago. */
    fun tick(now: Long): List<Alert> {
        val out = ArrayList<Alert>()
        // The dash drops its incoming-call screen after about 15 s; show it
        // again for as long as the phone rings.
        for (c in calls.values) if (!c.answered) repeatRinging(c, now, out)
        val it = pendingMisses.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (now - p.at < MISS_DELAY_MS) continue
            // With the screen off the phone app swaps its ringing notification
            // for the full-screen call page, so the notification goes while
            // the phone is still ringing. Wait for the ringing itself to stop.
            val state = if (now - p.at < MAX_RING_WAIT_MS) phoneState() else PhoneState.IDLE
            if (state == PhoneState.RINGING) {
                if (!p.waitingLogged) {
                    p.waitingLogged = true
                    log("Call notification gone, phone still ringing")
                }
                repeatRinging(p.call, now, out)
                continue
            }
            it.remove()
            if (state == PhoneState.IN_CALL) {
                // Picked up on the full-screen call page or a headset.
                out.add(Alert.Answered(p.call.caller, p.call.whatsapp))
                continue
            }
            missedCount++
            lastMissAt = now
            out.add(Alert.Missed(p.call.caller, missedCount, p.call.whatsapp))
        }
        return out
    }

    private fun repeatRinging(c: Call, now: Long, out: MutableList<Alert>) {
        if (!c.repeating) return
        if (now - c.since > MAX_RING_WAIT_MS) {
            c.repeating = false
            return
        }
        if (now - c.shownAt < RING_REPEAT_MS) return
        c.shownAt = now
        out.add(Alert.Ringing(c.caller, c.whatsapp))
    }

    /** True while [tick] has work: a call ringing, or a missed-call decision waiting. */
    val hasPending: Boolean get() = pendingMisses.isNotEmpty() || calls.values.any { !it.answered && it.repeating }

    // ---- calls ------------------------------------------------------------

    private fun onCall(n: Notice, now: Long, out: MutableList<Alert>) {
        val incoming = when (n.callType) {
            TYPE_INCOMING, TYPE_SCREENING -> true
            TYPE_ONGOING -> false
            // Phone apps without CallStyle: guess from the wording.
            else -> !n.ongoing || INCOMING_WORDS.containsMatchIn("${n.title} ${n.text}".lowercase())
        }
        val caller = callerName(n)
        val whatsapp = isWhatsApp(n.pkg)
        val existing = calls[n.key]
        if (incoming) {
            // The same call coming back as a new notification, e.g. when the
            // screen turns on while it rings: carry on with it.
            val call = existing ?: pendingMisses.firstOrNull { it.call.pkg == n.pkg && !it.call.answered }?.let { back ->
                pendingMisses.remove(back)
                calls[n.key] = back.call
                back.call
            }
            if (call == null) {
                calls[n.key] = Call(caller, n.pkg, whatsapp, answered = false, saved = n.savedName != null, since = now, shownAt = now)
                out.add(Alert.Ringing(caller, whatsapp))
            } else {
                renameIfBetter(call, caller, n, whatsapp, now, out)
            }
            return
        }
        // Ongoing call.
        if (existing != null) {
            if (!existing.answered) {
                existing.answered = true
                out.add(Alert.Answered(existing.caller, existing.whatsapp))
            }
            return
        }
        // A new "ongoing" notification from the app that was just ringing:
        // the same call, answered.
        val swapped = pendingMisses.firstOrNull { it.call.pkg == n.pkg && (now - it.at < MISS_DELAY_MS || it.waitingLogged) }
        if (swapped != null) {
            pendingMisses.remove(swapped)
            calls[n.key] = Call(swapped.call.caller, n.pkg, whatsapp, answered = true)
            out.add(Alert.Answered(swapped.call.caller, swapped.call.whatsapp))
        } else {
            // Outgoing call, or one already answered before we saw it.
            calls[n.key] = Call(caller, n.pkg, whatsapp, answered = true)
        }
    }

    /**
     * The phone app first shows the name the network sent, then swaps in the
     * saved contact name a moment later. Show the better name once; a missed
     * call uses it too.
     */
    private fun renameIfBetter(call: Call, caller: String, n: Notice, whatsapp: Boolean, now: Long, out: MutableList<Alert>) {
        if (call.answered || call.saved || call.renamed || caller == call.caller || caller == UNKNOWN) return
        call.caller = caller
        call.saved = n.savedName != null
        call.renamed = true
        call.shownAt = now
        out.add(Alert.Ringing(caller, whatsapp))
    }

    private fun onMissedNotice(n: Notice, now: Long, out: MutableList<Alert>) {
        val isNew = missedNotices.add(n.key)
        // The phone app says the call was missed: no need to wait for the
        // ringing to wind down, tell the dash now.
        val waiting = pendingMisses.firstOrNull { it.call.pkg == n.pkg && !it.call.answered }
        if (isNew && waiting != null) {
            pendingMisses.remove(waiting)
            missedCount++
            lastMissAt = now
            out.add(Alert.Missed(waiting.call.caller, missedCount, waiting.call.whatsapp))
            return
        }
        // The phone app's own "Missed call" notification. Usually we have
        // already counted this call from its ringing; only count it when we
        // did not (e.g. the call came in before the dash link was up).
        if (isNew && now - lastMissAt > MISSED_NOTICE_WINDOW_MS && pendingMisses.isEmpty()) {
            missedCount++
            lastMissAt = now
            out.add(Alert.Missed(callerFromMissedNotice(n), missedCount, isWhatsApp(n.pkg)))
        }
    }

    private fun isCall(n: Notice): Boolean =
        n.category == CATEGORY_CALL && (n.callType != 0 || n.pkg in PHONE_PACKAGES || isWhatsApp(n.pkg))

    private fun isMissedCallNotice(n: Notice): Boolean =
        n.category == CATEGORY_MISSED_CALL ||
            (n.callType == 0 && n.category != CATEGORY_MESSAGE && !n.ongoing &&
                (n.pkg in PHONE_PACKAGES || isWhatsApp(n.pkg)) &&
                MISSED_WORDS.containsMatchIn("${n.title} ${n.text}".lowercase()))

    private fun callerName(n: Notice): String =
        (n.savedName ?: n.callPerson?.takeIf { it.isNotBlank() } ?: n.title?.takeIf { it.isNotBlank() } ?: UNKNOWN).trim()

    private fun callerFromMissedNotice(n: Notice): String {
        n.savedName?.let { return it }
        // Phone apps put "Missed call" in the title and the caller in the text,
        // or the other way round.
        val title = n.title.orEmpty()
        val text = n.text.orEmpty()
        val name = if (MISSED_WORDS.containsMatchIn(title.lowercase())) text else title
        return name.trim().ifEmpty { "Unknown" }
    }

    // ---- messages ---------------------------------------------------------

    private fun isMessage(n: Notice): Boolean {
        if (n.ongoing) return false
        if (isWhatsApp(n.pkg)) return n.category == null || n.category == CATEGORY_MESSAGE
        return n.pkg in SMS_PACKAGES || n.pkg == defaultSmsPackage()
    }

    private fun onMessage(n: Notice, out: MutableList<Alert>) {
        val sender = senderName(n) ?: return
        if (!isWhatsApp(n.pkg) && skipBusinessSms() && isBusinessSender(sender)) return
        val content = "$sender\u0000${n.text.orEmpty()}"
        // Messaging apps repost the same notification when anything changes;
        // only a different last message counts as new.
        if (messageNotices[n.key] == content) return
        messageNotices[n.key] = content
        messageCount++
        out.add(Alert.Message(sender, messageCount, isWhatsApp(n.pkg)))
    }

    private fun senderName(n: Notice): String? {
        val title = n.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // "Mom (2 messages)", "Family Group: Mom" -> "Mom"
        return title.replace(COUNT_SUFFIX, "").substringAfterLast(": ").trim().ifEmpty { null }
    }

    companion object {
        const val UNKNOWN = "Unknown"

        const val CATEGORY_CALL = "call"
        const val CATEGORY_MISSED_CALL = "missed_call"
        const val CATEGORY_MESSAGE = "msg"

        const val TYPE_INCOMING = 1
        const val TYPE_ONGOING = 2
        const val TYPE_SCREENING = 3

        /** How long a call may be gone before it counts as missed. */
        const val MISS_DELAY_MS = 1_500L
        /** The dash shows an incoming call for about 15 s; show it again this often while ringing. */
        const val RING_REPEAT_MS = 10_000L
        /** Longest a call is taken to ring after its notification went. */
        const val MAX_RING_WAIT_MS = 120_000L
        /** A phone-app missed-call notification this soon after we counted one is the same call. */
        const val MISSED_NOTICE_WINDOW_MS = 30_000L

        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging",
        )
        val PHONE_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.server.telecom",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.android.incallui",
        )

        fun isWhatsApp(pkg: String): Boolean = pkg in WHATSAPP_PACKAGES

        /**
         * Indian business SMS come from registered headers: two letters, a
         * dash and a 6-character name, sometimes ending in -S, -P, -T or -G
         * ("AD-HPGOVT-S", "VM-HDFCBK"). Short codes ("56767") count too.
         */
        fun isBusinessSender(sender: String): Boolean {
            val s = sender.trim()
            return BUSINESS_HEADER.matches(s) || SHORT_CODE.matches(s)
        }

        private val BUSINESS_HEADER = Regex("""(?i)[A-Z]{2}-[A-Z0-9]{3,9}(-[SPTG])?""")
        private val SHORT_CODE = Regex("""\d{3,6}""")

        private val INCOMING_WORDS = Regex("""\bincoming\b""")
        private val MISSED_WORDS = Regex("""\bmissed\s+(voice\s+|video\s+)?calls?\b""")
        private val COUNT_SUFFIX = Regex("""\s*\(\d+\s+\w+\)\s*$""")
    }
}
