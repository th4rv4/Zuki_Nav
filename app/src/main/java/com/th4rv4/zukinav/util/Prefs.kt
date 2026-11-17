package com.th4rv4.zukinav.util

import android.content.Context
import android.content.SharedPreferences
import com.th4rv4.zukinav.protocol.BikeData

/** All user settings, in one SharedPreferences file. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("zuki_nav", Context.MODE_PRIVATE)

    var clusterAddress: String?
        get() = sp.getString(K_ADDR, null)
        set(v) = sp.edit().putString(K_ADDR, v).apply()

    var clusterName: String?
        get() = sp.getString(K_NAME, null)
        set(v) = sp.edit().putString(K_NAME, v).apply()

    var riderName: String
        get() = sp.getString(K_RIDER, "") ?: ""
        set(v) = sp.edit().putString(K_RIDER, v).apply()

    /** India drives on the left, which decides U-turn and roundabout artwork. */
    var leftHandTraffic: Boolean
        get() = sp.getBoolean(K_LHT, true)
        set(v) = sp.edit().putBoolean(K_LHT, v).apply()

    /** 0 = off. */
    var speedAlertKmh: Int
        get() = sp.getInt(K_SPEED, 0)
        set(v) = sp.edit().putInt(K_SPEED, v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /** Show incoming and missed calls on the dash. */
    var callAlerts: Boolean
        get() = sp.getBoolean(K_CALLS, true)
        set(v) = sp.edit().putBoolean(K_CALLS, v).apply()

    /** Show new WhatsApp and SMS messages on the dash. */
    var messageAlerts: Boolean
        get() = sp.getBoolean(K_MSGS, true)
        set(v) = sp.edit().putBoolean(K_MSGS, v).apply()

    /** Start the dash link when the phone boots. */
    var startOnBoot: Boolean
        get() = sp.getBoolean(K_BOOT, true)
        set(v) = sp.edit().putBoolean(K_BOOT, v).apply()

    /** Don't show SMS from bank, OTP, government and promo senders on the dash. */
    var skipBusinessSms: Boolean
        get() = sp.getBoolean(K_SKIP_BIZ_SMS, false)
        set(v) = sp.edit().putBoolean(K_SKIP_BIZ_SMS, v).apply()

    /** Missed calls from numbers you haven't saved aren't kept on the dash. */
    var missedSavedOnly: Boolean
        get() = sp.getBoolean(K_MISSED_SAVED_ONLY, true)
        set(v) = sp.edit().putBoolean(K_MISSED_SAVED_ONLY, v).apply()

    /** Chats and SMS from numbers you haven't saved pop up on the dash, but aren't kept as unread. */
    var messagesSavedOnly: Boolean
        get() = sp.getBoolean(K_MESSAGES_SAVED_ONLY, true)
        set(v) = sp.edit().putBoolean(K_MESSAGES_SAVED_ONLY, v).apply()

    /** Spin the compass arrow on the dash after "Welcome", when no route is active. */
    var startupAnimation: Boolean
        get() = sp.getBoolean(K_SPLASH, false)
        set(v) = sp.edit().putBoolean(K_SPLASH, v).apply()

    /** Short text shown on the dash after "Welcome"; empty for none. */
    var startupGreeting: String
        get() = sp.getString(K_GREETING, "") ?: ""
        set(v) = sp.edit().putString(K_GREETING, v.trim()).apply()

    /** App colours: [THEME_SYSTEM], [THEME_LIGHT], [THEME_DARK] or [THEME_AMOLED]. */
    var theme: Int
        get() = sp.getInt(K_THEME, THEME_SYSTEM)
        set(v) = sp.edit().putInt(K_THEME, v).apply()

    /** Below this engine temperature (°C) the Ride tab says Warming Up; 0 for never. */
    var engineWarmC: Int
        get() = sp.getInt(K_ENGINE_WARM, 0)
        set(v) = sp.edit().putInt(K_ENGINE_WARM, v).apply()

    /** At or above this engine temperature (°C) the Ride tab warns Hot; 0 for never. */
    var engineHotC: Int
        get() = sp.getInt(K_ENGINE_HOT, 0)
        set(v) = sp.edit().putInt(K_ENGINE_HOT, v).apply()

    /** Show WARMED UP on the dash once per ride when the engine reaches [engineWarmC]. */
    var warmedUpDashAlert: Boolean
        get() = sp.getBoolean(K_ALERT_WARM, false)
        set(v) = sp.edit().putBoolean(K_ALERT_WARM, v).apply()

    /** Show ENGINE OVERHEAT on the dash when the engine reaches [engineHotC]. */
    var overheatDashAlert: Boolean
        get() = sp.getBoolean(K_ALERT_HOT, false)
        set(v) = sp.edit().putBoolean(K_ALERT_HOT, v).apply()

    /** Maintenance reminders notify this many km before they are due. */
    var maintEarlyKm: Int
        get() = sp.getInt(K_MAINT_EARLY, 50)
        set(v) = sp.edit().putInt(K_MAINT_EARLY, v).apply()

    /** Seconds the startup greeting stays on the dash, one of [MESSAGE_SECONDS]. */
    var greetingSeconds: Int
        get() = seconds(K_GREETING_SECS)
        set(v) = sp.edit().putInt(K_GREETING_SECS, v).apply()

    /** Seconds WARMED UP stays on the dash, one of [MESSAGE_SECONDS]. */
    var warmedUpAlertSeconds: Int
        get() = seconds(K_ALERT_WARM_SECS)
        set(v) = sp.edit().putInt(K_ALERT_WARM_SECS, v).apply()

    /** Seconds ENGINE OVERHEAT stays on the dash, one of [MESSAGE_SECONDS]. */
    var overheatAlertSeconds: Int
        get() = seconds(K_ALERT_HOT_SECS)
        set(v) = sp.edit().putInt(K_ALERT_HOT_SECS, v).apply()

    private fun seconds(key: String): Int =
        sp.getInt(key, MESSAGE_SECONDS[0]).takeIf { it in MESSAGE_SECONDS } ?: MESSAGE_SECONDS[0]

    /** Fuel tank size in litres; 0 or less uses the bike model's. */
    var fuelTank: Float
        get() = sp.getFloat(K_FUEL_TANK, 0f)
        set(v) = sp.edit().putFloat(K_FUEL_TANK, v).apply()

    /** Fuel left when the last bar blinks, in litres; 0 or less uses the model's. */
    var fuelReserve: Float
        get() = sp.getFloat(K_FUEL_RESERVE, 0f)
        set(v) = sp.edit().putFloat(K_FUEL_RESERVE, v).apply()

    /** Ask for litres and amount paid after a refill. */
    var askAtRefill: Boolean
        get() = sp.getBoolean(K_FUEL_ASK, true)
        set(v) = sp.edit().putBoolean(K_FUEL_ASK, v).apply()

    /** Warn on the Ride tab when range drops below this many km; 0 for never. */
    var lowRangeKm: Int
        get() = sp.getInt(K_FUEL_LOW, 30)
        set(v) = sp.edit().putInt(K_FUEL_LOW, v).apply()

    /** Rider avatar: "initial", an icon key such as "helmet", or "photo". */
    var avatar: String
        get() = sp.getString(K_AVATAR, null) ?: "initial"
        set(v) = sp.edit().putString(K_AVATAR, v).apply()

    /** Accent preset key, see Accents; "wallpaper" follows the phone's wallpaper colours. */
    var accent: String
        get() = sp.getString(K_ACCENT, null) ?: "aurora"
        set(v) = sp.edit().putString(K_ACCENT, v).apply()

    /** Look for a new release when the app opens, at most once a day. */
    var checkUpdatesOnLaunch: Boolean
        get() = sp.getBoolean(K_UPDATE_AUTO, true)
        set(v) = sp.edit().putBoolean(K_UPDATE_AUTO, v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(K_UPDATE_LAST, 0)
        set(v) = sp.edit().putLong(K_UPDATE_LAST, v).apply()

    /** Last fuel, ODO and trip readings from the dash, kept after the ignition is off. */
    var lastBike: BikeData?
        get() = BikeData.decode(sp.getString(K_BIKE, null))
        set(v) = sp.edit().putString(K_BIKE, v?.encode()).apply()

    /** When [lastBike] was received, in ms. */
    var lastBikeAt: Long
        get() = sp.getLong(K_BIKE_AT, 0)
        set(v) = sp.edit().putLong(K_BIKE_AT, v).apply()

    /** Save each ride as a trip. */
    var recordTrips: Boolean
        get() = sp.getBoolean(K_TRIPS, true)
        set(v) = sp.edit().putBoolean(K_TRIPS, v).apply()

    /** Don't save rides under half a kilometre (moving the bike around). */
    var skipShortTrips: Boolean
        get() = sp.getBoolean(K_TRIPS_SHORT, true)
        set(v) = sp.edit().putBoolean(K_TRIPS_SHORT, v).apply()

    /** Note where each ride starts and ends (needs location). */
    var tripPlaces: Boolean
        get() = sp.getBoolean(K_TRIPS_PLACES, false)
        set(v) = sp.edit().putBoolean(K_TRIPS_PLACES, v).apply()

    /** Record each ride's GPS route (needs location, allowed all the time). */
    var recordRoute: Boolean
        get() = sp.getBoolean(K_TRIPS_ROUTE, false)
        set(v) = sp.edit().putBoolean(K_TRIPS_ROUTE, v).apply()

    /** How the trip map colours the route: [ROUTE_SPEED], [ROUTE_ENGINE] or [ROUTE_PLAIN]. */
    var routeColours: Int
        get() = sp.getInt(K_ROUTE_COLOURS, ROUTE_SPEED)
        set(v) = sp.edit().putInt(K_ROUTE_COLOURS, v).apply()

    /** The rider's own stop labels, newest first, at most 3. */
    val recentStopLabels: List<String>
        get() = sp.getString(K_STOP_LABELS, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun rememberStopLabel(label: String) {
        val list = (listOf(label) + recentStopLabels.filterNot { it.equals(label, ignoreCase = true) }).take(3)
        sp.edit().putString(K_STOP_LABELS, list.joinToString("\n")).apply()
    }

    /** Backup & Restore: the folder backups go to (a content:// tree address). */
    var backupFolder: String?
        get() = sp.getString(K_BACKUP_FOLDER, null)
        set(v) = sp.edit().putString(K_BACKUP_FOLDER, v).apply()

    /** The latest backup file in that folder, deleted when the next one is written. */
    var backupUri: String?
        get() = sp.getString(K_BACKUP_URI, null)
        set(v) = sp.edit().putString(K_BACKUP_URI, v).apply()
    var backupLastAt: Long
        get() = sp.getLong(K_BACKUP_LAST, 0L)
        set(v) = sp.edit().putLong(K_BACKUP_LAST, v).apply()
    var backupLastSize: Long
        get() = sp.getLong(K_BACKUP_LAST_SIZE, 0L)
        set(v) = sp.edit().putLong(K_BACKUP_LAST_SIZE, v).apply()

    /** Update the backup file after every saved trip. */
    var backupAfterTrip: Boolean
        get() = sp.getBoolean(K_BACKUP_AFTER_TRIP, false)
        set(v) = sp.edit().putBoolean(K_BACKUP_AFTER_TRIP, v).apply()

    /** Every setting with its type, for a backup; [skip] keys are left out. */
    fun exportAll(skip: Set<String>): org.json.JSONObject {
        val o = org.json.JSONObject()
        for ((k, v) in sp.all) {
            if (k in skip || v == null) continue
            val (t, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is String -> "s" to v
                is Set<*> -> "S" to org.json.JSONArray(v.map { it.toString() })
                else -> continue
            }
            o.put(k, org.json.JSONObject().put("t", t).put("v", value))
        }
        return o
    }

    /** Replaces the settings with a backup's, keeping the [keep] keys as they are. */
    fun importAll(o: org.json.JSONObject, keep: Set<String>) {
        val e = sp.edit()
        for (k in sp.all.keys) if (k !in keep) e.remove(k)
        for (k in o.keys()) {
            if (k in keep) continue
            val item = o.optJSONObject(k) ?: continue
            when (item.optString("t")) {
                "b" -> e.putBoolean(k, item.optBoolean("v"))
                "i" -> e.putInt(k, item.optInt("v"))
                "l" -> e.putLong(k, item.optLong("v"))
                "f" -> e.putFloat(k, item.optDouble("v").toFloat())
                "s" -> e.putString(k, item.optString("v"))
                "S" -> {
                    val a = item.optJSONArray("v") ?: continue
                    e.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet())
                }
            }
        }
        e.commit()
    }

    /** Reject Unknown Callers: decline phone calls from numbers not in the contacts. */
    var rejectUnknown: Boolean
        get() = sp.getBoolean(K_REJECT, false)
        set(v) = sp.edit().putBoolean(K_REJECT, v).apply()

    /** Reject even when the bike isn't connected (otherwise only while riding). */
    var rejectAlways: Boolean
        get() = sp.getBoolean(K_REJECT_ALWAYS, false)
        set(v) = sp.edit().putBoolean(K_REJECT_ALWAYS, v).apply()

    /** Let a second call from the same unknown number within 5 minutes ring through. */
    var rejectAllowRepeat: Boolean
        get() = sp.getBoolean(K_REJECT_REPEAT, true)
        set(v) = sp.edit().putBoolean(K_REJECT_REPEAT, v).apply()

    /** The month (yyyy-MM) whose recap card was closed on the Trips tab. */
    var recapDismissed: String?
        get() = sp.getString(K_RECAP_DISMISSED, null)
        set(v) = sp.edit().putString(K_RECAP_DISMISSED, v).apply()

    /** Where the bike was parked: "lat;lng;time;place", or null. */
    var parkedSpot: String?
        get() = sp.getString(K_PARKED, null)
        set(v) = sp.edit().putString(K_PARKED, v).apply()

    /** Answer WhatsApp chats while the bike is connected. */
    var autoReply: Boolean
        get() = sp.getBoolean(K_AUTO_REPLY, false)
        set(v) = sp.edit().putBoolean(K_AUTO_REPLY, v).apply()

    /** What the auto-reply says. */
    var autoReplyText: String
        get() = sp.getString(K_AUTO_REPLY_TEXT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTO_REPLY
        set(v) = sp.edit().putString(K_AUTO_REPLY_TEXT, v.trim()).apply()

    /** Skip numbers that aren't saved contacts, and group chats. */
    var autoReplyContactsOnly: Boolean
        get() = sp.getBoolean(K_AUTO_REPLY_CONTACTS, true)
        set(v) = sp.edit().putBoolean(K_AUTO_REPLY_CONTACTS, v).apply()

    /** Minutes before the same chat gets another reply; 0 means once per ride. */
    var autoReplyEveryMin: Int
        get() = sp.getInt(K_AUTO_REPLY_EVERY, 30)
        set(v) = sp.edit().putInt(K_AUTO_REPLY_EVERY, v).apply()

    /** Show REJECTED CALL on the dash for a moment. */
    var rejectShowOnDash: Boolean
        get() = sp.getBoolean(K_REJECT_DASH, false)
        set(v) = sp.edit().putBoolean(K_REJECT_DASH, v).apply()

    /** Share Image: background, line colour and what to show; see ShareImage. */
    var shareBackground: Int
        get() = sp.getInt(K_SHARE_BG, 0)
        set(v) = sp.edit().putInt(K_SHARE_BG, v).apply()
    var shareLine: Int
        get() = sp.getInt(K_SHARE_LINE, 0)
        set(v) = sp.edit().putInt(K_SHARE_LINE, v).apply()

    /** One Share Image switch, e.g. "stats", "distance", "engine"; [default] when never set. */
    fun shareShows(item: String, default: Boolean): Boolean = sp.getBoolean("share_show_$item", default)
    fun setShareShows(item: String, on: Boolean) = sp.edit().putBoolean("share_show_$item", on).apply()

    /** The ride in progress, see TripRecorder.Active. */
    var activeTrip: String?
        get() = sp.getString(K_TRIP_ACTIVE, null)
        set(v) = sp.edit().putString(K_TRIP_ACTIVE, v).apply()

    /** Shown on the setup screen after a restore, until setup is finished. */
    var restoredNote: String?
        get() = sp.getString(K_RESTORED_NOTE, null)
        set(v) = sp.edit().putString(K_RESTORED_NOTE, v).apply()

    /** Times the rider said no to the Contacts prompt; after two Android stops asking, so Allow opens App Info. */
    var contactsDenials: Int
        get() = sp.getInt(K_CONTACTS_DENIALS, 0)
        set(v) = sp.edit().putInt(K_CONTACTS_DENIALS, v).apply()

    /** Whether a Maps route is open, see RouteState. */
    var routeState: String?
        get() = sp.getString(K_ROUTE_STATE, null)
        set(v) = sp.edit().putString(K_ROUTE_STATE, v).apply()

    /** Dash Test results. */
    var iconMarks: IconMarks
        get() = IconMarks.decode(sp.getString(K_MARKS, null))
        set(v) = sp.edit().putString(K_MARKS, v.encode()).apply()

    /** Clusters this phone has already said hello to (decides 'F' vs 'R'). */
    fun isFirstPairing(clusterName: String): Boolean =
        !(sp.getStringSet(K_SEEN, emptySet()) ?: emptySet()).contains(clusterName)

    fun markPaired(clusterName: String) {
        val set = HashSet(sp.getStringSet(K_SEEN, emptySet()) ?: emptySet())
        if (set.add(clusterName)) sp.edit().putStringSet(K_SEEN, set).apply()
    }

    companion object {
        /** Choices for how long a greeting or alert stays on the dash, in seconds. */
        val MESSAGE_SECONDS = listOf(3, 5, 10, 15)

        private const val K_ADDR = "cluster_address"
        private const val K_NAME = "cluster_name"
        private const val K_RIDER = "rider_name"
        private const val K_LHT = "left_hand_traffic"
        private const val K_SPEED = "speed_alert_kmh"
        private const val K_AUTO = "auto_connect"
        private const val K_SEEN = "seen_clusters"
        private const val K_BOOT = "start_on_boot"
        private const val K_MARKS = "icon_marks"
        private const val K_CALLS = "call_alerts"
        private const val K_BIKE = "last_bike"
        private const val K_TRIPS = "record_trips"
        private const val K_TRIPS_SHORT = "skip_short_trips"
        private const val K_TRIPS_PLACES = "trip_places"
        private const val K_TRIP_ACTIVE = "active_trip"
        private const val K_ROUTE_STATE = "route_state"
        private const val K_RESTORED_NOTE = "restored_note"
        const val K_CONTACTS_DENIALS = "contacts_denials"
        private const val K_BIKE_AT = "last_bike_at"
        private const val K_UPDATE_AUTO = "update_check_on_launch"
        private const val K_THEME = "theme"
        private const val K_SPLASH = "startup_animation"
        private const val K_SKIP_BIZ_SMS = "skip_business_sms"
        private const val K_MISSED_SAVED_ONLY = "missed_saved_only"
        private const val K_MESSAGES_SAVED_ONLY = "messages_saved_only"
        private const val K_GREETING = "startup_greeting"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_AMOLED = 3
        private const val K_ACCENT = "accent"
        private const val K_AVATAR = "avatar"
        private const val K_FUEL_TANK = "fuel_tank"
        private const val K_ENGINE_WARM = "engine_warm_c"
        private const val K_ENGINE_HOT = "engine_hot_c"
        private const val K_ALERT_WARM = "alert_warmed_up_dash"
        private const val K_ALERT_HOT = "alert_overheat_dash"
        private const val K_GREETING_SECS = "greeting_seconds"
        private const val K_TRIPS_ROUTE = "trips_route"
        private const val K_ROUTE_COLOURS = "route_colours"
        private const val K_SHARE_BG = "share_background"
        private const val K_REJECT = "reject_unknown"
        const val K_BACKUP_URI = "backup_uri"
        private const val K_STOP_LABELS = "stop_labels_recent"
        const val K_BACKUP_FOLDER = "backup_folder"
        const val K_BACKUP_LAST = "backup_last"
        const val K_BACKUP_LAST_SIZE = "backup_last_size"
        const val K_BACKUP_AFTER_TRIP = "backup_after_trip"
        private const val K_REJECT_ALWAYS = "reject_unknown_always"
        private const val K_REJECT_REPEAT = "reject_unknown_repeat"
        private const val K_REJECT_DASH = "reject_unknown_dash"
        private const val K_AUTO_REPLY = "auto_reply"
        private const val K_PARKED = "parked_spot"
        private const val K_RECAP_DISMISSED = "recap_dismissed"
        private const val K_AUTO_REPLY_TEXT = "auto_reply_text"
        private const val K_AUTO_REPLY_CONTACTS = "auto_reply_contacts_only"
        private const val K_AUTO_REPLY_EVERY = "auto_reply_every_min"
        const val DEFAULT_AUTO_REPLY = "Riding right now, I'll get back to you once I stop."
        /** Choices for [autoReplyEveryMin]; 0 is once per ride. */
        val AUTO_REPLY_EVERY = listOf(15, 30, 0)
        private const val K_SHARE_LINE = "share_line"
        const val ROUTE_SPEED = 0
        const val ROUTE_ENGINE = 1
        const val ROUTE_PLAIN = 2
        private const val K_MAINT_EARLY = "maint_early_km"
        private const val K_ALERT_WARM_SECS = "alert_warmed_up_seconds"
        private const val K_ALERT_HOT_SECS = "alert_overheat_seconds"
        private const val K_FUEL_RESERVE = "fuel_reserve"
        private const val K_FUEL_ASK = "fuel_ask_at_refill"
        private const val K_FUEL_LOW = "fuel_low_range_km"
        private const val K_UPDATE_LAST = "update_last_check"
        private const val K_MSGS = "message_alerts"
    }
}
