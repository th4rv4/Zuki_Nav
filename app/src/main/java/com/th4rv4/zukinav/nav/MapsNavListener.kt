package com.th4rv4.zukinav.nav

import android.app.Notification
import android.app.Person
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.th4rv4.zukinav.alerts.Alert
import com.th4rv4.zukinav.alerts.AlertTracker
import com.th4rv4.zukinav.alerts.AutoReply
import com.th4rv4.zukinav.alerts.CallFilter
import com.th4rv4.zukinav.alerts.ContactNames
import com.th4rv4.zukinav.alerts.Notice
import com.th4rv4.zukinav.protocol.ClusterIcon
import com.th4rv4.zukinav.ble.ClusterLink
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs
import java.time.LocalTime

/**
 * Reads Google Maps' turn-by-turn notification and publishes it to
 * [NavRepository], and passes call and message notifications on to the dash
 * as alerts. The user grants access once under
 * Settings > Notifications > Device & app notifications / Notification access.
 */
class MapsNavListener : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private var lastIcon: ClusterIcon? = null
    // While Maps says "Rerouting..." its status line is a bare "Arrive ";
    // keep showing the last ETA / remaining distance instead of zeros.
    private var lastEta: LocalTime? = null
    private var lastRemaining: Double? = null
    /** Same arrow bitmap -> same answer; Maps reposts the notification every second or so. */
    private val arrowCache = HashMap<Int, ClusterIcon>()
    private var lastLogLine: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val alerts = AlertTracker(
        defaultSmsPackage = { try { Telephony.Sms.getDefaultSmsPackage(this) } catch (_: Exception) { null } },
        skipBusinessSms = { prefs.skipBusinessSms },
        phoneState = { phoneState() },
        log = { AppLog.d("Calls", it) },
    )
    /** Reject Unknown Callers: who was declined, and which call notifications were already looked at. */
    private val callFilter = CallFilter()
    private val screenedCalls = HashMap<String, Boolean>()
    /** Caller names found for each call notification, by what it carried. */
    private val callerLookups = HashMap<String, Pair<String, ContactNames.Lookup>>()
    private val autoReply by lazy { AutoReply(this, prefs) }

    private val alertTick = object : Runnable {
        override fun run() {
            deliver(alerts.tick(System.currentTimeMillis()))
            // Quick checks so the dash hears soon after the phone stops ringing.
            if (alerts.hasPending) main.postDelayed(this, 250)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onListenerConnected() {
        AppLog.d("Maps", "Notification access active")
        try {
            val guidance = activeNotifications?.filter { isMapsGuidance(it) }.orEmpty()
            guidance.forEach { handle(it) }
            if (guidance.isEmpty()) RouteWatch.onNoneShown(this)
        } catch (e: Exception) {
            AppLog.d("Maps", "Could not read active notifications: $e")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isMapsGuidance(sbn)) handle(sbn) else if (sbn.packageName != packageName) {
            val n = notice(sbn)
            if (declineIfUnknown(sbn, n)) return
            autoReply.onNotification(sbn, DashService.linkState == ClusterLink.State.READY)
            deliver(alerts.posted(n, System.currentTimeMillis()))
        }
    }

    /** Why the last notification went, for the log. */
    private var removeReason = 0

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        removeReason = reason
        super.onNotificationRemoved(sbn, rankingMap, reason)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isMapsGuidance(sbn)) {
            AppLog.d("Maps", "Guidance notification removed")
            RouteWatch.onRemoved(this)
            lastEta = null
            lastRemaining = null
            NavRepository.scheduleEnd()
        } else if (sbn.packageName != packageName) {
            val n = notice(sbn)
            if (n.category == AlertTracker.CATEGORY_CALL) {
                AppLog.d("Calls", "Call notification removed (${reasonName(removeReason)}), phone ${phoneState().name.lowercase()}")
            }
            callerLookups.remove(sbn.key)
            if (screenedCalls.remove(sbn.key) == true) return
            deliver(alerts.removed(n, System.currentTimeMillis()))
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- calls and messages ----------------------------------------------

    private fun notice(sbn: StatusBarNotification): Notice {
        val n = sbn.notification
        val e = n.extras
        val person = callPerson(e)
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val isCall = n.category == AlertTracker.CATEGORY_CALL || n.category == AlertTracker.CATEGORY_MISSED_CALL
        val lookup = if (isCall && !AlertTracker.isWhatsApp(sbn.packageName)) callerLookup(sbn, person, title) else null
        return Notice(
            key = sbn.key,
            pkg = sbn.packageName,
            category = n.category,
            callType = e.getInt(EXTRA_CALL_TYPE, 0),
            callPerson = person?.name?.toString(),
            title = title,
            text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            ongoing = sbn.isOngoing,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            savedName = lookup?.name,
            number = lookup?.number,
            contactsChecked = lookup?.checked == true,
        )
    }

    /**
     * The saved contact name for a phone call, looked up once per call
     * notification and again only when what it carries changes (the phone
     * app updates it while ringing).
     */
    private fun callerLookup(sbn: StatusBarNotification, person: Person?, title: String?): ContactNames.Lookup {
        val uris = (listOfNotNull(person?.uri) + peopleUris(sbn.notification.extras)).distinct()
        val sig = "${uris.joinToString()}|$title|${ContactNames.allowed(this)}"
        callerLookups[sbn.key]?.let { (s, l) -> if (s == sig) return l }
        val l = ContactNames.lookup(this, uris, title)
        if (callerLookups.size > 20) callerLookups.clear()
        callerLookups[sbn.key] = sig to l
        AppLog.d("Calls", "Caller name from ${l.source}")
        return l
    }

    @Suppress("DEPRECATION")
    private fun peopleUris(e: android.os.Bundle): List<String> = try {
        val list = if (android.os.Build.VERSION.SDK_INT >= 33) {
            e.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
        } else {
            e.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
        }
        list.orEmpty().mapNotNull { it.uri } + e.getStringArray(Notification.EXTRA_PEOPLE).orEmpty().toList()
    } catch (_: Exception) { emptyList() }

    /** Ringing or on a call, from Android's audio mode; needs no permission. */
    private fun phoneState(): AlertTracker.PhoneState = try {
        when (getSystemService(android.media.AudioManager::class.java)?.mode) {
            android.media.AudioManager.MODE_RINGTONE -> AlertTracker.PhoneState.RINGING
            android.media.AudioManager.MODE_IN_CALL,
            android.media.AudioManager.MODE_IN_COMMUNICATION,
            -> AlertTracker.PhoneState.IN_CALL
            else -> AlertTracker.PhoneState.IDLE
        }
    } catch (_: Exception) {
        AlertTracker.PhoneState.IDLE
    }

    private fun reasonName(r: Int): String = when (r) {
        REASON_CLICK -> "tapped"
        REASON_CANCEL -> "swiped"
        REASON_CANCEL_ALL -> "cleared"
        REASON_APP_CANCEL -> "by the phone app"
        REASON_APP_CANCEL_ALL -> "by the phone app"
        REASON_LISTENER_CANCEL -> "by Zuki Nav"
        REASON_SNOOZED -> "snoozed"
        REASON_GROUP_SUMMARY_CANCELED -> "group cleared"
        else -> "reason $r"
    }

    @Suppress("DEPRECATION")
    private fun callPerson(e: android.os.Bundle): Person? = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) e.getParcelable(EXTRA_CALL_PERSON, Person::class.java)
        else e.getParcelable(EXTRA_CALL_PERSON) as? Person
    } catch (_: Exception) { null }

    /**
     * Reject Unknown Callers: an incoming phone call (not WhatsApp) from a
     * number not in the contacts is declined with the phone app's own
     * Decline button. True if it was, so nothing about it goes to the dash.
     */
    private fun declineIfUnknown(sbn: StatusBarNotification, n: Notice): Boolean {
        screenedCalls[sbn.key]?.let { return it }
        if (!prefs.rejectUnknown) return false
        if (n.category != AlertTracker.CATEGORY_CALL || AlertTracker.isWhatsApp(n.pkg)) return false
        if (n.pkg !in AlertTracker.PHONE_PACKAGES && n.callType == 0) return false
        val incoming = n.callType == AlertTracker.TYPE_INCOMING || (n.callType == 0 && !n.ongoing)
        if (!incoming) return false
        if (!prefs.rejectAlways && DashService.linkState != ClusterLink.State.READY) return false
        val caller = n.screenName()
        val decline = callFilter.shouldDecline(caller, prefs.rejectAllowRepeat, System.currentTimeMillis())
        screenedCalls[sbn.key] = decline
        if (!decline) {
            if (CallFilter.isUnknown(caller)) AppLog.d("Calls", "Unknown caller rang again, let through")
            return false
        }
        val intent = declineIntent(sbn.notification)
        if (intent == null) {
            AppLog.d("Calls", "Unknown caller, but ${n.pkg} has no Decline button to press")
            screenedCalls[sbn.key] = false
            return false
        }
        return try {
            intent.send()
            AppLog.d("Calls", "Declined an unknown caller")
            if (prefs.rejectShowOnDash) DashService.instance?.showRejectedCall()
            true
        } catch (e: Exception) {
            AppLog.d("Calls", "Could not decline: $e")
            screenedCalls[sbn.key] = false
            false
        }
    }

    /** The phone app's Decline action: the CallStyle one, else a button labelled Decline or Reject. */
    @Suppress("DEPRECATION")
    private fun declineIntent(n: Notification): android.app.PendingIntent? {
        try {
            val cs = if (android.os.Build.VERSION.SDK_INT >= 33) {
                n.extras.getParcelable(EXTRA_DECLINE_INTENT, android.app.PendingIntent::class.java)
            } else {
                n.extras.getParcelable(EXTRA_DECLINE_INTENT) as? android.app.PendingIntent
            }
            if (cs != null) return cs
        } catch (_: Exception) {
        }
        return n.actions?.firstOrNull { a -> DECLINE_WORDS.containsMatchIn(a.title?.toString().orEmpty()) }?.actionIntent
    }

    private fun deliver(list: List<Alert>) {
        if (alerts.hasPending) {
            main.removeCallbacks(alertTick)
            main.postDelayed(alertTick, 250)
        }
        for (a in list) {
            // The phone app's own missed-call notice for a call we declined.
            if (a is Alert.Missed && !a.whatsapp && callFilter.recentlyDeclined(a.caller, System.currentTimeMillis())) continue
            val wanted = when (a) {
                is Alert.Ringing, is Alert.Answered, is Alert.Missed, Alert.ClearMissed -> prefs.callAlerts
                is Alert.Message, Alert.ClearMessages -> prefs.messageAlerts
            }
            if (!wanted) continue
            AppLog.d("Alert", a.toString())
            DashService.sendAlert(a)
        }
    }

    private fun isMapsGuidance(sbn: StatusBarNotification): Boolean =
        sbn.packageName == MapsIconTemplates.MAPS_PKG &&
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
            (sbn.notification.category == Notification.CATEGORY_NAVIGATION || sbn.isOngoing)

    private fun handle(sbn: StatusBarNotification) {
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val critical = e.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString()
        val lht = prefs.leftHandTraffic

        RouteWatch.onPosted(this, title)
        val parsed = NavText.parse(title, text, sub, critical, LocalTime.now(), lht)
        logExtrasIfNew(e)

        // The text wins when it has a distance; otherwise use the progress bar.
        val (barToTurn, barRemaining) = NavText.fromProgress(
            e.getInt(Notification.EXTRA_PROGRESS, 0),
            e.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            progressPoints(e),
        )
        val toTurn = parsed.metersToTurn ?: barToTurn
        val remaining = parsed.remainingMeters ?: barRemaining

        var source = "none"
        var icon: ClusterIcon? = null

        val sig = largeIconSignature(sbn)
        if (sig != null) {
            val key = sig.cells.contentHashCode()
            val cachedIcon = arrowCache[key]
            if (cachedIcon != null) {
                icon = cachedIcon
                source = "maps-icon"
            } else {
                val m = MapsIconTemplates.matcher(this).match(sig)
                if (m != null && m.distance <= IconMatcher.MAX_DISTANCE && m.margin >= IconMatcher.MIN_MARGIN) {
                    icon = m.icon
                    source = "maps-icon"
                    if (arrowCache.size > 64) arrowCache.clear()
                    arrowCache[key] = m.icon
                    AppLog.d("Maps", "Arrow ${m.drawableName} -> ${m.icon.label} (d=%.3f, margin=%.3f)".format(m.distance, m.margin))
                } else if (m != null) {
                    AppLog.d("Maps", "Arrow unsure: best ${m.drawableName} d=%.3f margin=%.3f".format(m.distance, m.margin))
                }
            }
        }
        // Maps draws a plain "depart" arrow for "Head northeast"; the dash has
        // a compass arrow for it, as the Suzuki app shows.
        if (parsed.textIcon != null && ClusterIcon.compassDegrees(parsed.textIcon) != null) {
            icon = parsed.textIcon
            source = "text"
        }
        if (icon == null && parsed.textIcon != null) {
            icon = parsed.textIcon
            source = "text"
        }
        if (icon == null && lastIcon != null) {
            icon = lastIcon
            source = "previous"
        }
        if (icon != null) lastIcon = icon

        // Maps reposts every second or so: log only what changed.
        val logLine = "title=\"$title\" text=\"$text\" sub=\"$sub\" -> ${icon?.label} [$source]"
        if (logLine != lastLogLine) {
            AppLog.d("Maps", logLine)
            lastLogLine = logLine
        }
        NavRepository.update(
            NavSnapshot(
                icon = icon,
                iconSource = source,
                metersToTurn = toTurn,
                instruction = parsed.instruction,
                remainingMeters = (remaining ?: lastRemaining).also { lastRemaining = it },
                eta = (parsed.eta ?: lastEta).also { lastEta = it },
            ),
        )
    }

    private var lastExtrasKeys: Set<String> = emptySet()
    private var pointsLogged = false

    /**
     * Positions of the ProgressStyle points (one per maneuver). Stored by
     * the framework as a list of Bundles under "android.progressPoints",
     * each with an int "position". Read defensively; the first point is
     * logged for diagnostics.
     */
    @Suppress("DEPRECATION")
    private fun progressPoints(e: android.os.Bundle): List<Int> = try {
        val raw = e.get(EXTRA_PROGRESS_POINTS)
        val bundles: List<android.os.Bundle> = when (raw) {
            is List<*> -> raw.filterIsInstance<android.os.Bundle>()
            is Array<*> -> raw.filterIsInstance<android.os.Bundle>()
            else -> emptyList()
        }
        if (!pointsLogged && bundles.isNotEmpty()) {
            pointsLogged = true
            val first = bundles.first()
            AppLog.d("Maps", "progress points: ${bundles.size}, first: " +
                first.keySet().joinToString(", ") { k -> "$k=${first.get(k)}" })
        }
        bundles.mapNotNull { b -> b.get("position") as? Int }
    } catch (ex: Exception) {
        AppLog.d("Maps", "Could not read progress points: $ex")
        emptyList()
    }

    /**
     * Logs every text field of the Maps notification whenever a key appears
     * that was not there before. Maps keeps moving the remaining distance
     * around, so this shows where it went.
     */
    @Suppress("DEPRECATION") // Bundle.get: we want every value, whatever its type.
    private fun logExtrasIfNew(e: android.os.Bundle) {
        val keys = e.keySet().toSet()
        if (keys == lastExtrasKeys) return
        lastExtrasKeys = keys
        val dump = keys.sorted().joinToString("; ") { k ->
            when (val v = e.get(k)) {
                is CharSequence, is Number, is Boolean -> "$k=$v"
                is List<*> -> "$k=[${v.size} x ${v.firstOrNull()?.javaClass?.simpleName}]"
                is Array<*> -> "$k=[${v.size} x ${v.firstOrNull()?.javaClass?.simpleName}]"
                null -> "$k=null"
                else -> "$k=<${v.javaClass.simpleName}>"
            }
        }
        AppLog.d("Maps", "extras: $dump")
    }

    private fun largeIconSignature(sbn: StatusBarNotification): IconSignature? {
        val icon = sbn.notification.getLargeIcon() ?: return null
        return try {
            val d = icon.loadDrawable(this) ?: return null
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bmp))
            MapsIconTemplates.signatureOf(bmp).also { bmp.recycle() }
        } catch (e: Exception) {
            AppLog.d("Maps", "Could not read arrow: $e")
            null
        }
    }

    companion object {
        /** Notification.Builder.setShortCriticalText (Android 16). */
        const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

        /** Notification.ProgressStyle points (Android 16). */
        const val EXTRA_PROGRESS_POINTS = "android.progressPoints"

        /** Notification.CallStyle (Android 12). */
        const val EXTRA_CALL_TYPE = "android.callType"
        const val EXTRA_CALL_PERSON = "android.callPerson"
        /** Notification.CallStyle's Decline button. */
        const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        private val DECLINE_WORDS = Regex("""(?i)\b(decline|reject|dismiss)\b|अस्वीकार|रिजेक्ट""")
    }
}
