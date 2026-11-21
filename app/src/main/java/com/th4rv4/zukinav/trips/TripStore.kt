package com.th4rv4.zukinav.trips

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Saved trips, kept in a JSON file in the app's private storage. */
object TripStore {

    /** Unsaved (not starred) trips beyond this many are dropped, oldest first. */
    private const val MAX_RECENT = 300

    private val lock = Any()
    private var cache: MutableList<Trip>? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Newest first. */
    fun all(ctx: Context): List<Trip> = synchronized(lock) { load(ctx).toList() }

    fun add(ctx: Context, trip: Trip) = change(ctx) { list ->
        list.removeAll { it.id == trip.id }
        list.add(0, trip)
        list.sortByDescending { it.start }
        var recent = 0
        list.removeAll { !it.starred && ++recent > MAX_RECENT }
        RouteStore.prune(ctx, list.map { it.id }.toSet(), RouteRecorder.recordingId)
    }

    fun update(ctx: Context, trip: Trip) = change(ctx) { list ->
        val i = list.indexOfFirst { it.id == trip.id }
        if (i >= 0) list[i] = trip
    }

    /** Joins the ride [firstId] with the one after it, routes included. Returns the merged trip. */
    fun mergeWithNext(ctx: Context, firstId: Long): Trip? {
        var merged: Trip? = null
        change(ctx) { list ->
            val a = list.firstOrNull { it.id == firstId } ?: return@change
            val b = TripMerge.next(a, list) ?: return@change
            val m = TripMerge.merge(a, b)
            list.removeAll { it.id == a.id || it.id == b.id }
            list.add(m)
            list.sortByDescending { it.start }
            RouteStore.join(ctx, a.id, b.id)
            merged = m
        }
        return merged
    }

    fun delete(ctx: Context, id: Long) = change(ctx) { list ->
        list.removeAll { it.id == id }
        RouteStore.delete(ctx, id)
    }

    private fun change(ctx: Context, block: (MutableList<Trip>) -> Unit) {
        synchronized(lock) {
            val list = load(ctx)
            block(list)
            save(ctx, list)
        }
        main.post { listeners.forEach { it() } }
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "trips.json")

    private fun load(ctx: Context): MutableList<Trip> {
        cache?.let { return it }
        val list = ArrayList<Trip>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                // Tidied as they load, so rides saved earlier read the same way.
                for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i))?.let { list += it.tidy() }
            }
        } catch (e: Exception) {
            AppLog.d("Trips", "Could not read saved trips: $e")
        }
        list.sortByDescending { it.start }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Trip>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            AppLog.d("Trips", "Could not save trips: $e")
        }
    }

    private fun toJson(t: Trip) = JSONObject().apply {
        put("start", t.start)
        put("end", t.end)
        put("km", t.distanceKm)
        put("moving", t.movingSeconds)
        put("top", t.topSpeedKmh)
        put("odo0", t.startOdo)
        put("odo1", t.endOdo)
        put("starred", t.starred)
        t.name?.let { put("name", it) }
        t.startLat?.let { put("lat0", it) }
        t.startLng?.let { put("lng0", it) }
        t.endLat?.let { put("lat1", it) }
        t.endLng?.let { put("lng1", it) }
        t.startPlace?.let { put("place0", it) }
        t.endPlace?.let { put("place1", it) }
        t.maxEngineC?.let { put("engMax", it) }
        t.movedAt?.let { put("moved", it) }
        if (t.warmUp) put("warmUp", true)
        t.warmFromC?.let { put("warm0", it) }
        t.warmToC?.let { put("warm1", it) }
        t.avgEngineC?.let { put("engAvg", it) }
        if (t.stops > 0) {
            put("stops", t.stops)
            put("stopSecs", t.stopSeconds)
        }
        if (t.legs.isNotEmpty()) {
            put("legs", org.json.JSONArray().apply {
                t.legs.forEach { l ->
                    put(JSONObject().put("s", l.start).put("e", l.end).put("km", l.distanceKm).put("mv", l.movingSeconds).put("top", l.topSpeedKmh))
                }
            })
            put("stopList", org.json.JSONArray().apply {
                t.stopList.forEach { st ->
                    put(JSONObject().apply {
                        put("a", st.arrive)
                        put("d", st.depart)
                        st.lat?.let { put("lat", it) }
                        st.lng?.let { put("lng", it) }
                        st.place?.let { put("place", it) }
                        if (st.warmUpSeconds > 0) put("warm", st.warmUpSeconds)
                        st.label?.let { put("label", it) }
                    })
                }
            })
        }
    }

    private fun fromJson(o: JSONObject): Trip? = try {
        fun d(k: String) = if (o.has(k)) o.getDouble(k) else null
        fun s(k: String) = if (o.has(k)) o.getString(k) else null
        Trip(
            start = o.getLong("start"),
            end = o.getLong("end"),
            distanceKm = o.getDouble("km"),
            movingSeconds = o.getLong("moving"),
            topSpeedKmh = o.getInt("top"),
            startOdo = o.getInt("odo0"),
            endOdo = o.getInt("odo1"),
            name = s("name"),
            starred = o.optBoolean("starred"),
            startLat = d("lat0"),
            startLng = d("lng0"),
            endLat = d("lat1"),
            endLng = d("lng1"),
            startPlace = s("place0"),
            endPlace = s("place1"),
            maxEngineC = if (o.has("engMax")) o.getInt("engMax") else null,
            avgEngineC = if (o.has("engAvg")) o.getInt("engAvg") else null,
            movedAt = if (o.has("moved")) o.getLong("moved") else null,
            warmUp = o.optBoolean("warmUp"),
            warmFromC = if (o.has("warm0")) o.getInt("warm0") else null,
            warmToC = if (o.has("warm1")) o.getInt("warm1") else null,
            stops = o.optInt("stops", 0),
            stopSeconds = o.optLong("stopSecs", 0L),
            legs = o.optJSONArray("legs")?.let { a ->
                (0 until a.length()).map { i ->
                    val l = a.getJSONObject(i)
                    Leg(l.getLong("s"), l.getLong("e"), l.getDouble("km"), l.getLong("mv"), l.getInt("top"))
                }
            } ?: emptyList(),
            stopList = o.optJSONArray("stopList")?.let { a ->
                (0 until a.length()).map { i ->
                    val st = a.getJSONObject(i)
                    Stop(
                        arrive = st.getLong("a"),
                        depart = st.getLong("d"),
                        lat = if (st.has("lat")) st.getDouble("lat") else null,
                        lng = if (st.has("lng")) st.getDouble("lng") else null,
                        place = if (st.has("place")) st.getString("place") else null,
                        label = if (st.has("label")) st.getString("label") else null,
                        warmUpSeconds = st.optLong("warm", 0L),
                    )
                }
            } ?: emptyList(),
        )
    } catch (e: Exception) {
        null
    }
}
