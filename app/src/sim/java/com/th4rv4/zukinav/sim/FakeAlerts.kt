package com.th4rv4.zukinav.sim

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.th4rv4.zukinav.alerts.AlertTracker
import com.th4rv4.zukinav.alerts.Notice
import com.th4rv4.zukinav.nav.MapsNavListener
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.SimHooks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zuki Sim's made-up calls and messages. They go through the same code as
 * real ones from the moment a notification would arrive, so what the dash
 * shows can be tested without anyone calling. (Reading Android's real call
 * screen and WhatsApp notifications is what they skip; real rides cover that.)
 */
object FakeAlerts {

    enum class Caller(val label: String) { SAVED("Saved"), UNKNOWN("Unknown"), CNAP("Caller ID"), WHATSAPP("WhatsApp") }
    enum class CallEnd(val label: String) { MISSED("Missed"), ANSWERED("Answered"), REJECTED("Rejected") }
    enum class From(val label: String) { SAVED("Saved"), UNKNOWN("Unknown"), GROUP("Group"), OWN("Own Reply") }

    val RING_SECONDS = listOf(5, 15, 30, 60)

    private const val DIALER = "com.google.android.dialer"
    private const val WHATSAPP = "com.whatsapp"
    private const val SMS = "com.google.android.apps.messaging"
    private const val NUMBER = "+919876543210"
    private const val NUMBER_SHOWN = "+91 98765 43210"
    private const val OTHER_NUMBER = "+919812345678"
    private const val GROUP = "Riders Club"
    private const val ANSWERED_FOR_MS = 4_000L

    private val main = Handler(Looper.getMainLooper())
    private var next = 0

    /** What was sent, newest first, for the page. */
    val sent = ArrayDeque<String>()
    var onChange: (() -> Unit)? = null

    private var call: Notice? = null
    private var callEnd = CallEnd.MISSED
    private var callLine = ""
    private val endCall = Runnable { finishCall() }
    private val leftOver = LinkedHashMap<String, Notice>()
    private var messageNo = 0

    /** Null when it went out, else why it couldn't. */
    fun ring(ctx: Context, caller: Caller, end: CallEnd, seconds: Int): String? {
        val l = MapsNavListener.instance ?: return noAccess()
        if (call != null) finishCall()
        val id = ++next
        val contact = contactName(ctx)
        val n = when (caller) {
            Caller.SAVED -> callNotice(id, DIALER, contact, savedName = contact, number = NUMBER)
            Caller.UNKNOWN -> callNotice(id, DIALER, NUMBER_SHOWN, savedName = null, number = NUMBER)
            Caller.CNAP -> callNotice(id, DIALER, "Likely: RAHUL SHARMA", savedName = null, number = NUMBER)
            Caller.WHATSAPP -> callNotice(id, WHATSAPP, contact, savedName = null, number = null)
        }
        call = n
        callEnd = end
        callLine = "Call from ${caller.label.lowercase()} (${n.title}), ${seconds} s, ${end.label.lowercase()}"
        SimHooks.fakePhoneState = AlertTracker.PhoneState.RINGING
        l.fakePosted(n)
        AppLog.d("Sim", "Fake call ringing: ${n.title} (${caller.label})")
        main.removeCallbacks(endCall)
        main.postDelayed(endCall, seconds * 1000L)
        note(callLine)
        return null
    }

    /** Ends the ringing call now, the way it was set to end. */
    fun hangUp() {
        if (call == null) return
        main.removeCallbacks(endCall)
        finishCall()
    }

    private fun finishCall() {
        val n = call ?: return
        val l = MapsNavListener.instance
        call = null
        when (callEnd) {
            CallEnd.ANSWERED -> {
                // Picked up: the notification turns into an ongoing call, then the call ends.
                SimHooks.fakePhoneState = AlertTracker.PhoneState.IN_CALL
                val ongoing = n.copy(callType = AlertTracker.TYPE_ONGOING, ongoing = true)
                l?.fakePosted(ongoing)
                AppLog.d("Sim", "Fake call answered")
                main.postDelayed({
                    SimHooks.fakePhoneState = null
                    l?.fakeRemoved(ongoing)
                    AppLog.d("Sim", "Fake call ended")
                }, ANSWERED_FOR_MS)
            }
            CallEnd.MISSED -> {
                SimHooks.fakePhoneState = null
                l?.fakeRemoved(n)
                AppLog.d("Sim", "Fake call stopped ringing: missed")
                // The phone app's own missed-call notification follows.
                main.postDelayed({ postMissedNotice(n) }, 1_500L)
            }
            CallEnd.REJECTED -> {
                SimHooks.fakePhoneState = null
                l?.fakeRemoved(n)
                AppLog.d("Sim", "Fake call rejected")
            }
        }
    }

    private fun postMissedNotice(call: Notice) {
        val l = MapsNavListener.instance ?: return
        val whatsapp = AlertTracker.isWhatsApp(call.pkg)
        val n = Notice(
            key = "sim|missed|${call.key}",
            pkg = call.pkg,
            category = if (whatsapp) null else AlertTracker.CATEGORY_MISSED_CALL,
            callType = 0,
            callPerson = null,
            title = if (whatsapp) call.title else "Missed call",
            text = if (whatsapp) "Missed voice call" else call.title,
            ongoing = false,
            groupSummary = false,
            savedName = call.savedName,
            number = call.number,
            contactsChecked = !whatsapp,
        )
        leftOver[n.key] = n
        l.fakePosted(n)
    }

    /** Null when it went out, else why it couldn't. */
    fun message(ctx: Context, from: From, whatsapp: Boolean): String? {
        val l = MapsNavListener.instance ?: return noAccess()
        val contact = contactName(ctx)
        val pkg = if (whatsapp) WHATSAPP else SMS
        val text = "Test message ${++messageNo}"
        val n = when (from) {
            From.SAVED -> messageNotice(pkg, contact, text)
            From.UNKNOWN -> if (whatsapp) {
                messageNotice(pkg, "~ Rahul", text, senderNumber = OTHER_NUMBER)
            } else {
                messageNotice(pkg, OTHER_NUMBER, text)
            }
            From.GROUP -> if (whatsapp) {
                messageNotice(pkg, "$GROUP: Rahul", text, groupName = GROUP, isGroup = true, senderNumber = OTHER_NUMBER)
            } else {
                messageNotice(pkg, contact, text)
            }
            From.OWN -> messageNotice(pkg, contact, text, fromMe = true)
        }
        leftOver[n.key] = n
        l.fakePosted(n)
        val app = if (whatsapp) "WhatsApp" else "SMS"
        AppLog.d("Sim", "Fake $app message: ${n.title} (${from.label})")
        note("$app from ${from.label.lowercase()} (${n.title})")
        return null
    }

    /** Clears the made-up missed calls and messages, as if read on the phone. */
    fun clearAll() {
        val l = MapsNavListener.instance ?: return
        leftOver.values.forEach { l.fakeRemoved(it) }
        leftOver.clear()
        AppLog.d("Sim", "Fake alerts cleared")
        note("Cleared missed calls and messages")
    }

    private fun callNotice(id: Int, pkg: String, name: String, savedName: String?, number: String?) = Notice(
        key = "sim|call|$id",
        pkg = pkg,
        category = AlertTracker.CATEGORY_CALL,
        callType = AlertTracker.TYPE_INCOMING,
        callPerson = name,
        title = name,
        text = "Incoming call",
        ongoing = false,
        groupSummary = false,
        savedName = savedName,
        number = number,
        contactsChecked = !AlertTracker.isWhatsApp(pkg),
    )

    private fun messageNotice(
        pkg: String,
        title: String,
        text: String,
        groupName: String? = null,
        isGroup: Boolean = false,
        senderNumber: String? = null,
        fromMe: Boolean = false,
    ) = Notice(
        // One notification per chat, as the apps do.
        key = "sim|msg|$pkg|${groupName ?: title}",
        pkg = pkg,
        category = AlertTracker.CATEGORY_MESSAGE,
        callType = 0,
        callPerson = null,
        title = title,
        text = text,
        ongoing = false,
        groupSummary = false,
        groupName = groupName,
        isGroup = isGroup,
        senderNumber = senderNumber,
        fromMe = fromMe,
    )

    /** A real name from the contacts, so the saved-contact path is the real one. */
    private fun contactName(ctx: Context): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return "Test Rider"
        return try {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Test Rider"
        } catch (_: Exception) {
            "Test Rider"
        }
    }

    private fun noAccess(): String {
        AppLog.d("Sim", "Fake alert not sent: Zuki Sim has no notification access")
        return "Zuki Sim needs Notification Access first (Setup Checklist)."
    }

    private fun note(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        sent.addFirst("$t  $line")
        while (sent.size > 8) sent.removeLast()
        onChange?.invoke()
    }
}
