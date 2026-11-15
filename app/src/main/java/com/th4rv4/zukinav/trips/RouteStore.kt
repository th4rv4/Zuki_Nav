package com.th4rv4.zukinav.trips

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Places
import java.io.File

/** Each trip's route as a small text file, routes/<trip id>.txt, one point per line. */
object RouteStore {

    private val thumbs = HashMap<Long, List<RoutePoint>>()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "routes").apply { mkdirs() }
    private fun file(ctx: Context, id: Long) = File(dir(ctx), "$id.txt")

    fun has(ctx: Context, id: Long): Boolean = file(ctx, id).let { it.exists() && it.length() > 0 }

    /** The trip's route, trimmed to the ride itself. Reads a file: fine for one trip at a time. */
    fun points(ctx: Context, trip: Trip): List<RoutePoint> {
        val f = file(ctx, trip.id)
        if (!f.exists()) return emptyList()
        return try {
            Route.trim(f.readLines().mapNotNull { Route.decode(it) }, trip.start, trip.end)
        } catch (e: Exception) {
            AppLog.d("Route", "Could not read route ${trip.id}: $e")
            emptyList()
        }
    }

    /** A few dozen points for the Trips list, kept in memory once read. */
    fun thumb(ctx: Context, trip: Trip): List<RoutePoint> = synchronized(thumbs) {
        thumbs.getOrPut(trip.id) { Route.thin(points(ctx, trip), 60) }
    }

    fun append(ctx: Context, id: Long, points: List<RoutePoint>) {
        if (points.isEmpty()) return
        try {
            file(ctx, id).appendText(points.joinToString("") { Route.encode(it) + "\n" })
            synchronized(thumbs) { thumbs.remove(id) }
        } catch (e: Exception) {
            AppLog.d("Route", "Could not save route points: $e")
        }
    }

    /** Adds ride [secondId]'s points to the end of ride [firstId]'s, for a merge. */
    fun join(ctx: Context, firstId: Long, secondId: Long) {
        val second = file(ctx, secondId)
        if (!second.exists()) return
        try {
            file(ctx, firstId).appendText(second.readText())
            second.delete()
        } catch (e: Exception) {
            AppLog.d("Route", "Could not join routes: $e")
        }
        synchronized(thumbs) { thumbs.remove(firstId); thumbs.remove(secondId) }
    }

    fun delete(ctx: Context, id: Long) {
        file(ctx, id).delete()
        synchronized(thumbs) { thumbs.remove(id) }
    }

    /** Removes route files whose trip no longer exists. */
    fun prune(ctx: Context, keep: Set<Long>, recording: Long?) {
        for (f in dir(ctx).listFiles() ?: return) {
            val id = f.nameWithoutExtension.toLongOrNull() ?: continue
            if (id !in keep && id != recording) f.delete()
        }
    }
}

/**
 * Records GPS points while a trip is running, a point every few seconds,
 * with the dash's speed and engine temperature at that moment.
 */
object RouteRecorder {

    private const val INTERVAL_MS = 2_000L
    private const val FLUSH_EVERY = 15

    private var tripId: Long? = null
    private var appContext: Context? = null
    private var last: RoutePoint? = null
    private val pending = ArrayList<RoutePoint>()

    private val listener = LocationListener { loc: Location -> onLocation(loc) }

    val recordingId: Long? get() = tripId

    /** Starts (or carries on) recording for [id]. Main thread. */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, id: Long) {
        if (tripId == id) return
        stop()
        if (!Places.hasForeground(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                AppLog.d("Route", "GPS is off, no route for this ride")
                return
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, Route.MIN_STEP_M.toFloat(), listener, Looper.getMainLooper())
            appContext = ctx.applicationContext
            tripId = id
            last = null
            AppLog.d("Route", "Recording the route of ride $id")
        } catch (e: SecurityException) {
            AppLog.d("Route", "No location permission for the route")
        } catch (e: Exception) {
            AppLog.d("Route", "Could not start the route: $e")
        }
    }

    /** Stops recording and saves what is left. Main thread. */
    fun stop() {
        val ctx = appContext ?: return
        val id = tripId
        try {
            ctx.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        } catch (_: Exception) {
        }
        if (id != null) flush(ctx, id)
        tripId = null
        last = null
    }

    private fun onLocation(loc: Location) {
        val ctx = appContext ?: return
        val id = tripId ?: return
        if (loc.hasAccuracy() && loc.accuracy > 40f) return
        val bike = DashService.liveBike
        val p = RoutePoint(
            lat = loc.latitude,
            lng = loc.longitude,
            at = System.currentTimeMillis(),
            speedKmh = bike?.speedKmh ?: (loc.speed * 3.6f).toInt(),
            engineC = bike?.engineTempC,
        )
        if (!Route.worthKeeping(last, p)) return
        last = p
        pending += p
        if (pending.size >= FLUSH_EVERY) flush(ctx, id)
    }

    private fun flush(ctx: Context, id: Long) {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        RouteStore.append(ctx, id, batch)
    }
}
