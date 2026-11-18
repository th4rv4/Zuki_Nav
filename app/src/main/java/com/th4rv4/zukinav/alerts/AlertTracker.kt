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
    /** Chat apps: the group's name, when the message is in a group. */
    val groupName: String? = null,
    val isGroup: Boolean = false,
    /** Chat apps: the sender's phone number, when the notification carries it. */
    val senderNumber: String? = null,
    /** Chat apps: the latest message in the chat is your own, e.g. an auto-reply. */
    val fromMe: Boolean = false,
) {
    /**
     * Who Reject Unknown Callers judges the call by. With the contacts read,
     * a number that isn't saved counts as unknown even when the network sends
     * a name with it.
     */
    fun screenName(): String? = savedName
        ?: number?.takeIf { contactsChecked }
        ?: callPerson?.takeIf { it.isNotBlank() }?.let { AlertTracker.cleanCaller(it) }
        ?: title?.let { AlertTracker.cleanCaller(it) }
}

/** What to show on the dash. */
sealed interface Alert {
    data class Ringing(val caller: String, val whatsapp: Boolean) : Alert
    data class Answered(val caller: String, val whatsapp: Boolean) : Alert
    /** [fleeting]: shown, then cleared from the dash, so it doesn't stay as unread. */
    data class Missed(val caller: String, val count: Int, val whatsapp: Boolean, val fleeting: Boolean = false) : Alert
    data class Message(val sender: String, val count: Int, val whatsapp: Boolean, val fleeting: Boolean = false) : Alert
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
    /** Missed calls from numbers you haven't saved aren't kept on the dash. */
    private val missedSavedOnly: () -> Boolean = { false },
    /** Chats and SMS from numbers you haven't saved pop up, but aren't kept as unread. */
    private val messagesSavedOnly: () -> Boolean = { false },
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
        /** A saved contact, or at least someone with a name; not a bare or hidden number. */
        var known: Boolean = true,
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
    /** The same for messages that aren't counted as unread: groups, people you haven't saved. */
    private val groupNotices = HashMap<String, String>()

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
        groupNotices.remove(n.key)
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
            missed(p.call, now, out)
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
                calls[n.key] = Call(
                    caller, n.pkg, whatsapp, answered = false, saved = n.savedName != null,
                    since = now, shownAt = now, known = knownCaller(n),
                )
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
        call.known = call.known || knownCaller(n)
        call.shownAt = now
        out.add(Alert.Ringing(caller, whatsapp))
    }

    /**
     * A missed call for the dash. One from a number you haven't saved is
     * shown just long enough to close the ringing screen, then cleared, so
     * it doesn't stay as a missed call (when [missedSavedOnly] is on).
     */
    private fun missed(call: Call, now: Long, out: MutableList<Alert>) {
        lastMissAt = now
        if (!call.known && missedSavedOnly()) {
            log("Missed call from a number you haven't saved: not kept on the dash")
            out.add(Alert.Missed(call.caller, maxOf(missedCount, 1), call.whatsapp, fleeting = missedCount == 0))
            return
        }
        missedCount++
        out.add(Alert.Missed(call.caller, missedCount, call.whatsapp))
    }

    private fun onMissedNotice(n: Notice, now: Long, out: MutableList<Alert>) {
        val isNew = missedNotices.add(n.key)
        // The phone app says the call was missed: no need to wait for the
        // ringing to wind down, tell the dash now.
        val waiting = pendingMisses.firstOrNull { it.call.pkg == n.pkg && !it.call.answered }
        if (isNew && waiting != null) {
            pendingMisses.remove(waiting)
            missed(waiting.call, now, out)
            return
        }
        // The phone app's own "Missed call" notification. Usually we have
        // already counted this call from its ringing; only count it when we
        // did not (e.g. the call came in before the dash link was up).
        if (isNew && now - lastMissAt > MISSED_NOTICE_WINDOW_MS && pendingMisses.isEmpty()) {
            val caller = callerFromMissedNotice(n)
            val known = n.savedName != null || !CallFilter.isUnknown(caller)
            missed(Call(caller, n.pkg, isWhatsApp(n.pkg), answered = false, known = known), now, out)
        }
    }

    private fun isCall(n: Notice): Boolean =
        n.category == CATEGORY_CALL && (n.callType != 0 || n.pkg in PHONE_PACKAGES || isWhatsApp(n.pkg))

    private fun isMissedCallNotice(n: Notice): Boolean =
        n.category == CATEGORY_MISSED_CALL ||
            (n.callType == 0 && n.category != CATEGORY_MESSAGE && !n.ongoing &&
                (n.pkg in PHONE_PACKAGES || isWhatsApp(n.pkg)) &&
                MISSED_WORDS.containsMatchIn("${n.title} ${n.text}".lowercase()))

    private fun callerName(n: Notice): String {
        val name = cleanCaller(n.savedName ?: n.callPerson?.takeIf { it.isNotBlank() } ?: n.title?.takeIf { it.isNotBlank() } ?: UNKNOWN)
        // The dash can't show emoji or non-Latin letters: show the number instead.
        return forDash(if (readable(name)) name else n.number ?: UNKNOWN)
    }

    /**
     * A saved contact, or a caller with a name. With the contacts read, a
     * number that isn't saved is unknown even when the network sends a name.
     */
    private fun knownCaller(n: Notice): Boolean {
        if (n.savedName != null) return true
        val who = n.screenName()?.trim().orEmpty()
        return who.isNotEmpty() && !who.startsWith("~") && !CallFilter.isUnknown(who)
    }

    private fun callerFromMissedNotice(n: Notice): String {
        n.savedName?.let { return it }
        // Phone apps put "Missed call" in the title and the caller in the text,
        // or the other way round.
        val title = n.title.orEmpty()
        val text = n.text.orEmpty()
        val name = if (MISSED_WORDS.containsMatchIn(title.lowercase())) text else title
        return forDash(cleanCaller(name).ifEmpty { UNKNOWN })
    }

    // ---- messages ---------------------------------------------------------

    private fun isMessage(n: Notice): Boolean {
        if (n.ongoing) return false
        if (isWhatsApp(n.pkg)) return n.category == null || n.category == CATEGORY_MESSAGE
        return n.pkg in SMS_PACKAGES || n.pkg == defaultSmsPackage()
    }

    private fun onMessage(n: Notice, out: MutableList<Alert>) {
        // A reply of yours added to the chat (an auto-reply, or one sent from
        // the notification): not a new message, and not from "You".
        if (n.fromMe || senderName(n)?.let { ME_NAMES.matches(it) } == true) return
        val name = senderName(n) ?: return
        // WhatsApp's own "new messages" notice, not a person.
        if (isWhatsApp(n.pkg) && APP_TITLES.matches(name)) return
        val whatsapp = isWhatsApp(n.pkg)
        val sender = forDash(if (whatsapp) whatsAppSender(n, name) else if (readable(name)) name else "Message")
        if (!whatsapp && skipBusinessSms() && isBusinessSender(sender)) return
        val group = whatsapp && n.isGroup
        // Groups, and with the switch on people you haven't saved, pop up
        // but aren't kept as unread.
        val passing = group || (messagesSavedOnly() && unsavedSender(name))
        val content = "$sender\u0000${n.text.orEmpty()}"
        // Messaging apps repost the same notification when anything changes;
        // only a different last message counts as new.
        if (passing) {
            if (groupNotices[n.key] == content) return
            groupNotices[n.key] = content
            if (groupNotices.size > 50) groupNotices.clear()
            // Unread messages from saved contacts stay as they were.
            out.add(Alert.Message(sender, maxOf(messageCount, 1), whatsapp, fleeting = messageCount == 0))
            return
        }
        if (messageNotices[n.key] == content) return
        messageNotices[n.key] = content
        messageCount++
        out.add(Alert.Message(sender, messageCount, whatsapp))
    }

    /**
     * Who a WhatsApp message is from, as the dash can show it:
     *  - in a group: the group's name, whoever sent it (a group message
     *    is often not for you; anything that is comes as a chat of its own)
     *  - a saved contact: the saved name
     *  - someone not saved: their number
     * WhatsApp marks people who aren't saved with a "~" before the name they
     * chose; that name can be anything, emoji included.
     */
    /** WhatsApp marks people you haven't saved with "~"; otherwise a bare number. */
    private fun unsavedSender(name: String): Boolean {
        val n = name.trim()
        return n.startsWith("~") || CallFilter.isUnknown(n)
    }

    private fun whatsAppSender(n: Notice, name: String): String {
        if (n.isGroup) {
            val group = (n.groupName ?: n.title?.substringBefore(": ", ""))?.replace(COUNT_SUFFIX, "")?.trim()
            if (group != null && readable(group)) return group
        }
        val unsaved = name.startsWith("~") || !readable(name)
        if (!unsaved) return name
        n.senderNumber?.let { return it }
        // The number isn't on the notification: the name they chose, if the dash can show it.
        val chosen = name.removePrefix("~").trim()
        return if (readable(chosen)) chosen else "WhatsApp"
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
         * A caller name as the phone app shows it, tidied for the dash:
         * without the "Likely:" the phone app puts before a name the network
         * sent for a number you haven't saved, and without the invisible
         * direction marks around numbers.
         */
        fun cleanCaller(name: String): String =
            name.replace(DIRECTION_MARKS, "").replace(GUESS_PREFIX, "").trim().ifEmpty { name.trim() }

        /**
         * A phone number as it fits the dash, which shows about 13
         * characters and can't draw "+": without the country code, as
         * "98765 43210". Anything that isn't a number stays as it is.
         */
        fun forDash(name: String): String {
            val compact = name.replace(DIRECTION_MARKS, "").replace(Regex("""[\s\-().]"""), "")
            if (!PHONE.matches(compact)) return name
            val digits = compact.removePrefix("+")
            val national = when {
                // With a country code: "+91…", or "91…" as WhatsApp writes it.
                compact.startsWith("+") || digits.length > 11 -> withoutCountryCode(digits)
                // A trunk 0 in front of a local number.
                digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
                else -> digits
            }
            return if (national.length == 10) "${national.substring(0, 5)} ${national.substring(5)}" else national
        }

        /** Country calling codes never start with one another, so the first match is the one. */
        private fun withoutCountryCode(digits: String): String {
            for (len in 1..3) {
                if (digits.take(len) in COUNTRY_CODES && digits.length - len >= 6) return digits.substring(len)
            }
            // A code not in the list: keep the last 10 digits, the usual length of a mobile number.
            return digits.takeLast(10)
        }

        private val COUNTRY_CODES = setOf(
            "1", "7",
            "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49",
            "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63", "64", "65", "66",
            "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98",
            "211", "212", "213", "216", "218", "220", "221", "222", "223", "224", "225", "226", "227", "228", "229",
            "230", "231", "232", "233", "234", "235", "236", "237", "238", "239", "240", "241", "242", "243", "244",
            "245", "246", "248", "249", "250", "251", "252", "253", "254", "255", "256", "257", "258", "260", "261",
            "262", "263", "264", "265", "266", "267", "268", "269", "290", "291", "297", "298", "299",
            "350", "351", "352", "353", "354", "355", "356", "357", "358", "359", "370", "371", "372", "373", "374",
            "375", "376", "377", "378", "380", "381", "382", "383", "385", "386", "387", "389",
            "420", "421", "423", "500", "501", "502", "503", "504", "505", "506", "507", "508", "509",
            "590", "591", "592", "593", "594", "595", "596", "597", "598", "599",
            "670", "672", "673", "674", "675", "676", "677", "678", "679", "680", "681", "682", "683", "685", "686",
            "687", "688", "689", "690", "691", "692", "850", "852", "853", "855", "856", "880", "886",
            "960", "961", "962", "963", "964", "965", "966", "967", "968", "970", "971", "972", "973", "974", "975",
            "976", "977", "992", "993", "994", "995", "996", "998",
        )

        private val PHONE = Regex("""^\+?\d{6,15}$""")

        private val DIRECTION_MARKS = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]")
        private val GUESS_PREFIX = Regex("""^(?i)(likely|probably|possibly)\s*:\s*""")

        /** True if the dash can show something of [name]: at least one plain letter or digit. */
        fun readable(name: String): Boolean = name.any { it.code < 0x7F && it.isLetterOrDigit() }

        private val ME_NAMES = Regex("""(?i)you|me""")

        private val APP_TITLES = Regex("""(?i)whatsapp( business)?""")

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
