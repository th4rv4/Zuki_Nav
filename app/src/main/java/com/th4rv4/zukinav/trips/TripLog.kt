package com.th4rv4.zukinav.trips

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.nav.RouteWatch
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

    /** True between a reading and the dash going quiet. */
    private var keyOn = false

    /** The dash has stayed quiet long enough to be the key off, not a dropout. */
    var bikeOff = false
        private set

    /** The dash dropped out while riding; the ride goes on with GPS until it's back. */
    var dashLost = false
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = listeners.forEach { it() }

    /** While the key is off: checks every minute whether the ride has ended. */
    private val idleCheck = Runnable { appContext?.let { finishIfIdle(it) } }

    /** The dash went quiet and it was the key off (see DropJudge): GPS can stop. */
    fun confirmBikeOff(ctx: Context) {
        restore(ctx)
        if (keyOn) return
        bikeOff = true
        dashLost = false
        changed()
        val id = recorder.active?.start ?: return
        AppLog.d("Trips", "Bike off")
        RouteRecorder.update(ctx, id, null, keyOn = false, routeOpen = RouteWatch.isOpen(ctx))
    }

    /** The dash dropped out while riding: keep the ride and its route going on GPS. */
    fun onDashLost(ctx: Context) {
        restore(ctx)
        if (keyOn) return
        dashLost = true
        bikeOff = false
        val id = recorder.active?.start
        if (id != null && Prefs(ctx).recordRoute) RouteRecorder.update(ctx, id, null, keyOn = true, routeOpen = RouteWatch.isOpen(ctx))
        changed()
    }

    /** The ride in progress, if any. */
    fun active(ctx: Context): TripRecorder.Active? {
        restore(ctx)
        return recorder.active
    }

    /** When the ride will end by itself if the key stays off; null while riding or with no ride. */
    fun endsAt(ctx: Context): Long? {
        val a = active(ctx) ?: return null
        if (!bikeOff) return null
        return a.lastAt + TripRecorder.offLimit(RouteWatch.isOpen(ctx))
    }

    fun onReading(ctx: Context, d: BikeData) {
        val prefs = Prefs(ctx)
        restore(ctx)
        main.removeCallbacks(idleCheck)
        val wasOff = bikeOff || dashLost
        val gapWasRiding = dashLost
        keyOn = true
        bikeOff = false
        dashLost = false
        if (!prefs.recordTrips) return
        val now = System.currentTimeMillis()
        val routeOpen = RouteWatch.isOpen(ctx, now)
        val here = RouteRecorder.lastPoint?.takeIf { now - it.at < 60_000L }?.let { it.lat to it.lng }
        val stopsBefore = recorder.active?.stops?.size ?: 0
        recorder.onReading(d, now, routeOpen, here, gapWasRiding)?.let { save(ctx, it) }
        if (recorder.justStarted) {
            AppLog.d("Trips", "Ride started at ODO ${d.odoKm}")
            if (prefs.tripPlaces) Places.locate(ctx) { lat, lng -> main.post { recorder.setStartPlace(lat, lng); persist(ctx) } }
        } else if ((recorder.active?.stops?.size ?: 0) > stopsBefore) {
            val s = recorder.active!!.stops.last()
            AppLog.d("Trips", "Stop of ${s.seconds / 60} min added to the ride")
        }
        // Also picks the route back up after the app was restarted mid-ride.
        val id = recorder.active?.start
        if (prefs.recordRoute && id != null) RouteRecorder.update(ctx, id, d.speedKmh, keyOn = true, routeOpen = routeOpen)
        else if (!prefs.recordRoute && RouteRecorder.recordingId != null) RouteRecorder.stop()
        persist(ctx)
        if (wasOff || recorder.justStarted) changed()
    }

    /**
     * The dash went quiet: note where, and end the ride if it stays off.
     * Whether it was the key off or a dropout mid-ride is decided after,
     * see [confirmBikeOff] and [onDashLost].
     */
    fun onDisconnected(ctx: Context) {
        restore(ctx)
        keyOn = false
        if (recorder.active == null) return
        if (Prefs(ctx).tripPlaces) Places.locate(ctx) { lat, lng -> main.post { recorder.setEndPlace(lat, lng); persist(ctx) } }
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 60_000L)
    }

    /** A Maps route opened or closed: that changes when a parked ride ends. */
    fun onRouteChanged(ctx: Context) {
        restore(ctx)
        val a = recorder.active ?: return
        if (keyOn) {
            if (Prefs(ctx).recordRoute) RouteRecorder.update(ctx, a.start, null, keyOn = true, routeOpen = RouteWatch.isOpen(ctx))
        } else {
            main.removeCallbacks(idleCheck)
            // Just after the route counts as closed, or within the minute.
            val closes = RouteWatch.closesAt(ctx)
            val wait = closes?.let { (it - System.currentTimeMillis() + 1_000L).coerceIn(1_000L, 60_000L) } ?: 1_000L
            main.postDelayed(idleCheck, wait)
        }
        changed()
    }

    /** Recording was turned off: keep what was ridden so far, then stop. */
    fun stopRecording(ctx: Context) {
        restore(ctx)
        main.removeCallbacks(idleCheck)
        recorder.finish()?.let { save(ctx, it) }
        RouteRecorder.stop()
        persist(ctx)
        changed()
    }

    /** What End Ride saved, so Undo can put the ride back. */
    class Ended internal constructor(val trip: Trip, internal val active: TripRecorder.Active)

    /** End Ride: saves the open ride now. Null if there was none, or too short to keep. */
    fun endRide(ctx: Context): Ended? {
        restore(ctx)
        val a = recorder.active ?: return null
        main.removeCallbacks(idleCheck)
        val trip = recorder.finish() ?: return null
        AppLog.d("Trips", "Ride ended by the rider")
        val kept = save(ctx, trip)
        persist(ctx)
        changed()
        return if (kept) Ended(trip, a) else null
    }

    /** Undo for End Ride: the ride goes on as if it had not ended. */
    fun undoEnd(ctx: Context, e: Ended) {
        restore(ctx)
        if (recorder.active != null) return
        TripStore.delete(ctx.applicationContext, e.trip.id)
        recorder.active = e.active
        AppLog.d("Trips", "End Ride undone")
        persist(ctx)
        main.removeCallbacks(idleCheck)
        if (!keyOn) main.postDelayed(idleCheck, 60_000L)
        changed()
    }

    /** Ends a ride left open, e.g. when the app was closed before the timer ran. */
    fun finishIfIdle(ctx: Context) {
        restore(ctx)
        if (keyOn) return
        val had = recorder.active != null
        recorder.finishIfIdle(System.currentTimeMillis(), RouteWatch.isOpen(ctx))?.let {
            AppLog.d("Trips", "Bike off long enough, ride ended")
            save(ctx, it)
        }
        persist(ctx)
        if (had && recorder.active == null) changed()
        // Check again in a minute while the key stays off.
        main.removeCallbacks(idleCheck)
        if (recorder.active != null) main.postDelayed(idleCheck, 60_000L)
    }

    private fun restore(ctx: Context) {
        appContext = ctx.applicationContext
        if (restored) return
        restored = true
        recorder.active = TripRecorder.Active.decode(Prefs(ctx).activeTrip)
        // Started with a ride open and no word from the dash for a while: the bike is off.
        bikeOff = !keyOn && recorder.active?.let { System.currentTimeMillis() - it.lastAt > 45_000L } == true
    }

    private fun persist(ctx: Context) {
        Prefs(ctx).activeTrip = recorder.active?.encode()
    }

    /** Saves a finished ride; false if it was too short to keep. */
    private fun save(ctx: Context, trip: Trip): Boolean {
        val prefs = Prefs(ctx)
        if (RouteRecorder.recordingId == trip.id) RouteRecorder.stop()
        val minKm = if (prefs.skipShortTrips) SHORT_RIDE_KM else 0.1
        if (trip.distanceKm < minKm) {
            AppLog.d("Trips", "Ride of ${trip.distanceKm} km not saved (too short)")
            RouteStore.delete(ctx, trip.id)
            return false
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
        return true
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
