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

    /** The ride in progress, see TripRecorder.Active. */
    var activeTrip: String?
        get() = sp.getString(K_TRIP_ACTIVE, null)
        set(v) = sp.edit().putString(K_TRIP_ACTIVE, v).apply()

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
        private const val K_BIKE_AT = "last_bike_at"
        private const val K_UPDATE_AUTO = "update_check_on_launch"
        private const val K_THEME = "theme"
        private const val K_SPLASH = "startup_animation"
        private const val K_SKIP_BIZ_SMS = "skip_business_sms"
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
