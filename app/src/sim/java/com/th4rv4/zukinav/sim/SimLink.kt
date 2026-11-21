package com.th4rv4.zukinav.sim

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.th4rv4.zukinav.protocol.SimFrame
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Clock

/**
 * Zuki Sim's side of Zuki Dash Sim: takes the simulated dash's 'Z' frames,
 * runs the ride clock at its speed, and feeds its made-up position in as GPS.
 *
 * The GPS keeps going when the simulated dash goes quiet, like a phone in
 * the rider's pocket: still where the bike stopped if the bike was switched
 * off, still moving at the last speed if the link dropped mid-ride. After
 * [GIVE_BACK_MS] of quiet the phone's real GPS comes back.
 */
object SimLink {

    /** A fix every 2 s of ride time, as the phone's GPS would give (faster in real time when fast-forwarded). */
    private const val GPS_EVERY_MS = 2_000L
    private const val GIVE_BACK_MS = 10 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var lastSpeedUp = 0.0
    private var mockReady: Boolean? = null
    private var mockWarned = false

    // The last made-up fix and when (ride time) it was taken.
    private var last: SimFrame? = null
    private var lastAt = 0L
    private var lastFrameReal = 0L

    fun onFrame(ctx: Context, frame: ByteArray) {
        val f = SimFrame.parse(frame) ?: return
        appCtx = ctx.applicationContext
        if (f.speedUp != lastSpeedUp) {
            AppLog.d("Sim", "Ride time running ${f.speedUp.toInt()}× (from Zuki Dash Sim)")
            lastSpeedUp = f.speedUp
        }
        Clock.sync(f.rideTime, f.speedUp)
        lastFrameReal = SystemClock.elapsedRealtime()
        if (f.lat != null && f.lng != null) {
            last = f
            lastAt = f.rideTime
            mock(f.lat, f.lng, f.speedKmh, f.headingDeg)
        }
        main.removeCallbacks(keepUp)
        main.postDelayed(keepUp, Clock.scaled(GPS_EVERY_MS))
    }

    /** No frame for a while: carry on from the last one. */
    private val keepUp: Runnable = object : Runnable {
        override fun run() {
            val f = last ?: return
            val quiet = SystemClock.elapsedRealtime() - lastFrameReal
            if (quiet >= GIVE_BACK_MS) {
                giveBack()
                return
            }
            // Riding when the link went: keep moving. Switched off or stopped: stay put.
            val moving = f.ignitionOn && f.engineOn && f.speedKmh > 0
            val lat = f.lat ?: return
            val lng = f.lng ?: return
            if (moving) {
                val km = f.speedKmh * (Clock.now() - lastAt).coerceAtLeast(0L) / 3_600_000.0
                val (la, ln) = SimFrame.move(lat, lng, f.headingDeg.toDouble(), km)
                mock(la, ln, f.speedKmh, f.headingDeg)
            } else {
                mock(lat, lng, 0, f.headingDeg)
            }
            main.postDelayed(this, Clock.scaled(GPS_EVERY_MS))
        }
    }

    private fun giveBack() {
        // Keep the ride clock where it got to (never back in time), just at real speed.
        Clock.sync(Clock.now(), 1.0)
        lastSpeedUp = 1.0
        last = null
        val ctx = appCtx ?: return
        if (mockReady != true) return
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeTestProvider(LocationManager.GPS_PROVIDER)
            AppLog.d("Sim", "Zuki Dash Sim quiet for 10 min: the phone's real GPS is back")
        } catch (_: Exception) {
        }
        mockReady = null
    }

    /** The made-up position, as the phone's GPS. Needs Zuki Sim picked as the mock location app. */
    private fun mock(lat: Double, lng: Double, kmh: Int, heading: Int) {
        val lm = appCtx?.getSystemService(LocationManager::class.java) ?: return
        try {
            if (mockReady != true) {
                try {
                    lm.removeTestProvider(LocationManager.GPS_PROVIDER)
                } catch (_: Exception) {
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    lm.addTestProvider(
                        LocationManager.GPS_PROVIDER,
                        ProviderProperties.Builder()
                            .setHasSpeedSupport(true)
                            .setHasBearingSupport(true)
                            .setHasAltitudeSupport(true)
                            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                            .setAccuracy(ProviderProperties.ACCURACY_FINE)
                            .build(),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 1, 1)
                }
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                mockReady = true
                AppLog.d("Sim", "Using Zuki Dash Sim's position as GPS")
            }
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = lat
                longitude = lng
                accuracy = 5f
                speed = kmh / 3.6f
                bearing = heading.toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (e: SecurityException) {
            mockReady = false
            if (!mockWarned) {
                mockWarned = true
                AppLog.d("Sim", "No made-up GPS: pick Zuki Sim as the mock location app in Developer options")
            }
        } catch (e: Exception) {
            mockReady = false
            AppLog.d("Sim", "Made-up GPS failed: ${e.javaClass.simpleName}")
        }
    }
}
