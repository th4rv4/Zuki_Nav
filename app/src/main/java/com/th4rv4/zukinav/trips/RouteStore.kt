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

    private const val FLUSH_EVERY = 15

    private var tripId: Long? = null
    private var appContext: Context? = null
    private var last: RoutePoint? = null
    private val pending = ArrayList<RoutePoint>()
    private val plan = GpsPlan()
    private var listening: GpsPlan.Mode = GpsPlan.Mode.OFF

    private val listener = LocationListener { loc: Location -> onLocation(loc) }

    val recordingId: Long? get() = tripId

    /** The newest point kept for the ride being recorded, if any. */
    val lastPoint: RoutePoint? get() = last

    /**
     * Records the route of ride [id], given the latest dash [speedKmh] (null
     * when there's no new reading) and whether the key is on and a Maps route
     * is open. Picks how to get fixes, see [GpsPlan]. Main thread.
     */
    fun update(ctx: Context, id: Long, speedKmh: Int?, keyOn: Boolean, routeOpen: Boolean) {
        if (tripId != id) {
            stop()
            tripId = id
            last = null
            appContext = ctx.applicationContext
            AppLog.d("Route", "Recording the route of ride $id")
        }
        speedKmh?.let { plan.onReading(it) }
        if (plan.update(System.currentTimeMillis(), keyOn, routeOpen)) listen(ctx, plan.mode)
    }

    /** Stops recording and saves what is left. Main thread. */
    fun stop() {
        val ctx = appContext ?: return
        val id = tripId
        listen(ctx, GpsPlan.Mode.OFF)
        plan.update(System.currentTimeMillis(), keyOn = false, routeOpen = false)
        if (id != null) flush(ctx, id)
        tripId = null
        last = null
    }

    @SuppressLint("MissingPermission")
    private fun listen(ctx: Context, mode: GpsPlan.Mode) {
        if (mode == listening) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
        listening = GpsPlan.Mode.OFF
        if (mode == GpsPlan.Mode.OFF) {
            tripId?.let { flush(ctx, it) }
            AppLog.d("Route", "GPS off")
            return
        }
        if (!Places.hasForeground(ctx)) return
        try {
            if (mode == GpsPlan.Mode.SHARED) {
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1_000L, Route.MIN_STEP_M.toFloat(), listener, Looper.getMainLooper())
                AppLog.d("Route", "Using the fixes Maps gets")
            } else {
                if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    AppLog.d("Route", "GPS is off, no route for now")
                    return
                }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, mode.intervalMs, Route.MIN_STEP_M.toFloat(), listener, Looper.getMainLooper())
                AppLog.d("Route", "GPS every ${mode.intervalMs / 1000} s")
            }
            listening = mode
        } catch (e: SecurityException) {
            AppLog.d("Route", "No location permission for the route")
        } catch (e: Exception) {
            AppLog.d("Route", "Could not start the route: $e")
        }
    }

    private fun onLocation(loc: Location) {
        val ctx = appContext ?: return
        val id = tripId ?: return
        if (loc.hasAccuracy() && loc.accuracy > 40f) return
        val now = System.currentTimeMillis()
        plan.onFix(now, if (loc.hasSpeed()) loc.speed * 3.6f else null)
        val bike = DashService.liveBike
        val p = RoutePoint(
            lat = loc.latitude,
            lng = loc.longitude,
            at = now,
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
