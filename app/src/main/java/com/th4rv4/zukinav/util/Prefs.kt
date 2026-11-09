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

    /** App colours: [THEME_SYSTEM], [THEME_LIGHT] or [THEME_DARK]. */
    var theme: Int
        get() = sp.getInt(K_THEME, THEME_SYSTEM)
        set(v) = sp.edit().putInt(K_THEME, v).apply()

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

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        private const val K_UPDATE_LAST = "update_last_check"
        private const val K_MSGS = "message_alerts"
    }
}
