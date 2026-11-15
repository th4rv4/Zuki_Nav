package com.th4rv4.zukinav.trips

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.protocol.BikeData
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Places
import com.th4rv4.zukinav.util.Prefs

/**
 * Records rides from the dash readings and saves them to [TripStore].
 * Runs on the main thread; the ride in progress is kept in [Prefs] so it
 * survives the app being restarted.
 */
object TripLog {

    /** Rides shorter than this are not saved when Skip Short Rides is on. */
    const val SHORT_RIDE_KM = 0.5

    private val main = Handler(Looper.getMainLooper())
    private val recorder = TripRecorder()
    private var restored = false
    private var appContext: Context? = null

    private val idleCheck = Runnable { appContext?.let { finishIfIdle(it) } }

    /** The ride in progress, if any. */
    fun active(ctx: Context): TripRecorder.Active? {
        restore(ctx)
        return recorder.active
    }

    fun onReading(ctx: Context, d: BikeData) {
        val prefs = Prefs(ctx)
        restore(ctx)
        main.removeCallbacks(idleCheck)
        if (!prefs.recordTrips) return
        val now = System.currentTimeMillis()
        recorder.onReading(d, now)?.let { save(ctx, it) }
        if (recorder.justStarted) {
            AppLog.d("Trips", "Ride started at ODO ${d.odoKm}")
            if (prefs.tripPlaces) Places.locate(ctx) { lat, lng -> main.post { recorder.setStartPlace(lat, lng); persist(ctx) } }
        }
        // Also picks the route back up after the app was restarted mid-ride.
        val id = recorder.active?.start
        if (prefs.recordRoute && id != null) RouteRecorder.start(ctx, id)
        else if (!prefs.recordRoute && RouteRecorder.recordingId != null) RouteRecorder.stop()
        persist(ctx)
    }

    /** The dash went quiet (usually the ignition off): note where, and end the ride if it stays off. */
    fun onDisconnected(ctx: Context) {
        restore(ctx)
        if (recorder.active == null) return
        if (Prefs(ctx).tripPlaces) Places.locate(ctx) { lat, lng -> main.post { recorder.setEndPlace(lat, lng); persist(ctx) } }
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, TripRecorder.GAP_MS + 2_000)
    }

    /** Recording was turned off: keep what was ridden so far, then stop. */
    fun stopRecording(ctx: Context) {
        restore(ctx)
        main.removeCallbacks(idleCheck)
        recorder.finish()?.let { save(ctx, it) }
        RouteRecorder.stop()
        persist(ctx)
    }

    /** Ends a ride left open, e.g. when the app was closed before the timer ran. */
    fun finishIfIdle(ctx: Context) {
        restore(ctx)
        recorder.finishIfIdle(System.currentTimeMillis())?.let { save(ctx, it) }
        persist(ctx)
    }

    private fun restore(ctx: Context) {
        appContext = ctx.applicationContext
        if (restored) return
        restored = true
        recorder.active = TripRecorder.Active.decode(Prefs(ctx).activeTrip)
    }

    private fun persist(ctx: Context) {
        Prefs(ctx).activeTrip = recorder.active?.encode()
    }

    private fun save(ctx: Context, trip: Trip) {
        val prefs = Prefs(ctx)
        if (RouteRecorder.recordingId == trip.id) RouteRecorder.stop()
        val minKm = if (prefs.skipShortTrips) SHORT_RIDE_KM else 0.1
        if (trip.distanceKm < minKm) {
            AppLog.d("Trips", "Ride of ${trip.distanceKm} km not saved (too short)")
            RouteStore.delete(ctx, trip.id)
            return
        }
        AppLog.d("Trips", "Ride saved: ${trip.distanceKm} km, top ${trip.topSpeedKmh} km/h")
        val app = ctx.applicationContext
        TripStore.add(app, trip)
        com.th4rv4.zukinav.backup.Backup.afterTrip(app)
        if (trip.startLat != null || trip.endLat != null) {
            Thread {
                val withNames = trip.copy(
                    startPlace = trip.startLat?.let { Places.name(app, it, trip.startLng ?: return@let null) },
                    endPlace = trip.endLat?.let { Places.name(app, it, trip.endLng ?: return@let null) },
                )
                if (withNames.startPlace != null || withNames.endPlace != null) {
                    // Keep any rename or star made in the meantime.
                    val now = TripStore.all(app).firstOrNull { it.id == trip.id } ?: return@Thread
                    TripStore.update(app, now.copy(startPlace = withNames.startPlace, endPlace = withNames.endPlace))
                }
            }.start()
        }
    }

    private var lastPlaceRetry = 0L

    /**
     * Rides whose place names couldn't be looked up (no internet at the time)
     * get another try, a few at a time and at most every 10 minutes.
     */
    fun retryPlaces(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPlaceRetry < 10 * 60_000L) return
        lastPlaceRetry = now
        val app = ctx.applicationContext
        val missing = TripStore.all(app).filter {
            (it.startLat != null && it.startPlace == null) || (it.endLat != null && it.endPlace == null)
        }.take(3)
        if (missing.isEmpty()) return
        Thread {
            for (t in missing) {
                val start = t.startPlace ?: t.startLat?.let { Places.name(app, it, t.startLng ?: return@let null) }
                val end = t.endPlace ?: t.endLat?.let { Places.name(app, it, t.endLng ?: return@let null) }
                if (start == t.startPlace && end == t.endPlace) continue
                val current = TripStore.all(app).firstOrNull { it.id == t.id } ?: continue
                TripStore.update(app, current.copy(startPlace = current.startPlace ?: start, endPlace = current.endPlace ?: end))
                AppLog.d("Places", "Found the place names of an older ride")
            }
        }.start()
    }
}
