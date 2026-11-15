package com.th4rv4.zukinav.trips

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Places
import com.th4rv4.zukinav.util.Prefs
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Where the bike was left: the spot where the ignition went off. */
data class Parked(val lat: Double, val lng: Double, val at: Long, val place: String?)

object Parking {

    /** A route point this recent is where the bike stopped. */
    private const val FRESH_MS = 3 * 60_000L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun get(ctx: Context): Parked? = decode(Prefs(ctx).parkedSpot)

    /**
     * The bike went off. Uses the ride's last GPS point when the route was
     * being recorded; otherwise asks the phone once where it is, which is
     * next to the bike at that moment.
     */
    fun onBikeOff(ctx: Context) {
        val app = ctx.applicationContext
        if (!Places.hasForeground(app)) return
        val now = System.currentTimeMillis()
        val p = RouteRecorder.lastPoint?.takeIf { now - it.at < FRESH_MS }
        if (p != null) {
            store(app, p.lat, p.lng, now)
        } else {
            Places.locate(app) { lat, lng -> store(app, lat, lng, now) }
        }
    }

    private fun store(ctx: Context, lat: Double, lng: Double, at: Long) {
        Thread {
            val place = Places.name(ctx, lat, lng)
            Prefs(ctx).parkedSpot = encode(Parked(lat, lng, at, place))
            AppLog.d("Parking", "Parked spot saved" + (place?.let { " near $it" } ?: ""))
            main.post { listeners.forEach { it() } }
        }.start()
    }

    private fun encode(p: Parked): String =
        String.format(Locale.US, "%.6f;%.6f;%d;%s", p.lat, p.lng, p.at, p.place.orEmpty().replace(";", ","))

    private fun decode(s: String?): Parked? {
        val f = s?.split(';') ?: return null
        if (f.size < 3) return null
        val lat = f[0].toDoubleOrNull() ?: return null
        val lng = f[1].toDoubleOrNull() ?: return null
        val at = f[2].toLongOrNull() ?: return null
        return Parked(lat, lng, at, f.getOrNull(3)?.takeIf { it.isNotBlank() })
    }
}
