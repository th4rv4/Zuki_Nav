package com.th4rv4.gixxernav.util

import android.content.Context
import android.content.SharedPreferences

/** All user settings, in one SharedPreferences file. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("gixxer_nav", Context.MODE_PRIVATE)

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

    /** Clusters this phone has already said hello to (decides 'F' vs 'R'). */
    fun isFirstPairing(clusterName: String): Boolean =
        !(sp.getStringSet(K_SEEN, emptySet()) ?: emptySet()).contains(clusterName)

    fun markPaired(clusterName: String) {
        val set = HashSet(sp.getStringSet(K_SEEN, emptySet()) ?: emptySet())
        if (set.add(clusterName)) sp.edit().putStringSet(K_SEEN, set).apply()
    }

    private companion object {
        const val K_ADDR = "cluster_address"
        const val K_NAME = "cluster_name"
        const val K_RIDER = "rider_name"
        const val K_LHT = "left_hand_traffic"
        const val K_SPEED = "speed_alert_kmh"
        const val K_AUTO = "auto_connect"
        const val K_SEEN = "seen_clusters"
    }
}
