package com.th4rv4.zukinav.trips

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.util.Clock
import com.th4rv4.zukinav.util.Places

/**
 * Watches the phone's GPS for up to a minute after the dash drops, so
 * [DropJudge] can tell the bike being switched off from the dash dropping
 * out mid-ride. Main thread.
 */
object DropWatch {

    private val main = Handler(Looper.getMainLooper())
    private var judge: DropJudge? = null
    private var onDone: ((DropJudge) -> Unit)? = null
    private var appContext: Context? = null
    private var hasGps = false

    private val listener = LocationListener { loc: Location -> onLocation(loc) }
    private val timer = object : Runnable {
        override fun run() {
            val j = judge ?: return
            if (j.onTime(Clock.now(), hasGps) != DropJudge.Verdict.WAITING) finish()
            else main.postDelayed(this, CHECK_MS)
        }
    }

    /** True while it is still making up its mind. */
    val watching: Boolean get() = judge != null

    /** Starts judging a drop that just happened; [onVerdict] gets the answer once. */
    fun start(ctx: Context, lastDashKmh: Int?, onVerdict: (DropJudge) -> Unit) {
        cancel()
        val now = Clock.now()
        val j = DropJudge(now, lastDashKmh)
        RouteRecorder.lastPoint?.takeIf { now - it.at < 60_000L }?.let { j.setDropPoint(it.lat, it.lng) }
        appContext = ctx.applicationContext
        judge = j
        onDone = onVerdict
        if (j.verdict != DropJudge.Verdict.WAITING) {
            finish()
            return
        }
        hasGps = listen(ctx.applicationContext)
        main.postDelayed(timer, CHECK_MS)
    }

    /** The dash came back: nothing to decide. */
    fun cancel() {
        stopListening()
        main.removeCallbacks(timer)
        judge = null
        onDone = null
    }

    @SuppressLint("MissingPermission")
    private fun listen(ctx: Context): Boolean {
        if (!Places.hasForeground(ctx)) return false
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopListening() {
        val ctx = appContext ?: return
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        } catch (_: Exception) {
        }
    }

    private fun onLocation(loc: Location) {
        val j = judge ?: return
        if (loc.hasAccuracy() && loc.accuracy > 50f) return
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else null
        if (j.onFix(Clock.now(), loc.latitude, loc.longitude, kmh) != DropJudge.Verdict.WAITING) finish()
    }

    private fun finish() {
        val j = judge ?: return
        val cb = onDone
        cancel()
        cb?.invoke(j)
    }

    private const val CHECK_MS = 2_000L
}
