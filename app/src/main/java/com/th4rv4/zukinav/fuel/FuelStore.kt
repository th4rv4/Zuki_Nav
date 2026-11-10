package com.th4rv4.zukinav.fuel

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps the fuel tracker's state in a JSON file in the app's private storage. */
object FuelStore {

    private val lock = Any()
    private var cache: FuelState? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun state(ctx: Context): FuelState = synchronized(lock) { load(ctx) }

    /** Newest first. */
    fun refills(ctx: Context): List<Refill> = state(ctx).refills

    /** Model defaults with the rider's Tank Size and Reserve applied; null on electric models. */
    fun spec(ctx: Context): FuelSpec? {
        val prefs = Prefs(ctx)
        val base = FuelBook.specFor(ClusterModels.fromBleName(prefs.clusterName).displayName) ?: return null
        return base.copy(
            tankLitres = prefs.fuelTank.takeIf { it > 0f }?.toDouble() ?: base.tankLitres,
            reserveLitres = prefs.fuelReserve.takeIf { it > 0f }?.toDouble() ?: base.reserveLitres,
        )
    }

    /** The model's own tank and reserve, ignoring the rider's changes. */
    fun modelSpec(ctx: Context): FuelSpec? =
        FuelBook.specFor(ClusterModels.fromBleName(Prefs(ctx).clusterName).displayName)

    fun mileage(ctx: Context): Mileage = FuelBook.mileage(refills(ctx))

    fun range(ctx: Context, odoKm: Int): Range? {
        val spec = spec(ctx) ?: return null
        val s = state(ctx)
        return FuelBook.range(s, odoKm, spec, FuelBook.mileage(s.refills))
    }

    /** One reading from the dash. Returns a refill when the bars went up enough to be one. */
    fun onReading(ctx: Context, odoKm: Int, bars: Int): Refill? {
        val before: FuelState
        val after: FuelState
        val found: Refill?
        synchronized(lock) {
            before = load(ctx)
            val (next, r) = FuelBook.onReading(before, odoKm, bars, System.currentTimeMillis())
            after = next
            found = r
            cache = next
            // Written only when something worth keeping changed, not every 5 s.
            if (next.copy(pendingBars = null, pendingCount = 0) != before.copy(pendingBars = null, pendingCount = 0)) write(ctx, next)
        }
        if (after.gaugeBars != before.gaugeBars) {
            AppLog.d("Fuel", "Gauge ${before.gaugeBars} -> ${after.gaugeBars} bars at $odoKm km")
            main.post { listeners.forEach { it() } }
        }
        found?.let { AppLog.d("Fuel", "Refill at ${it.odoKm} km, ${it.barsBefore} -> ${it.barsAfter} bars") }
        return found
    }

    /** Saves what the rider typed for a refill (or a new refill added by hand). */
    fun save(ctx: Context, refill: Refill) = change(ctx) { s ->
        val list = s.refills.filter { it.id != refill.id } + refill
        s.copy(refills = list.sortedByDescending { it.at })
    }

    fun delete(ctx: Context, id: Long) = change(ctx) { s -> s.copy(refills = s.refills.filter { it.id != id }) }

    /** Forgets the learned km per bar; refills stay. */
    fun resetLearning(ctx: Context) = change(ctx) { s ->
        s.copy(bars = FuelState().bars, segBar = s.gaugeBars, segStartKm = s.lastOdoKm ?: 0, segClean = false)
    }

    /** Forgets everything: refills, mileage and learning. */
    fun deleteAll(ctx: Context) = change(ctx) { FuelState() }

    private fun change(ctx: Context, block: (FuelState) -> FuelState) {
        synchronized(lock) {
            val next = block(load(ctx))
            cache = next
            write(ctx, next)
        }
        main.post { listeners.forEach { it() } }
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "fuel.json")

    private fun load(ctx: Context): FuelState {
        cache?.let { return it }
        var s = FuelState()
        try {
            val f = file(ctx)
            if (f.exists()) s = fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            AppLog.d("Fuel", "Could not read fuel data: $e")
        }
        cache = s
        return s
    }

    private fun write(ctx: Context, s: FuelState) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(toJson(s).toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            AppLog.d("Fuel", "Could not save fuel data: $e")
        }
    }

    private fun toJson(s: FuelState) = JSONObject().apply {
        put("refills", JSONArray().apply {
            s.refills.forEach { r ->
                put(JSONObject().apply {
                    put("at", r.at)
                    put("odo", r.odoKm)
                    put("b0", r.barsBefore)
                    put("b1", r.barsAfter)
                    r.litres?.let { put("litres", it) }
                    put("est", r.litresEstimated)
                    r.amount?.let { put("amount", it) }
                    put("full", r.full)
                    put("answered", r.answered)
                })
            }
        })
        put("bars", JSONArray().apply { s.bars.forEach { put(JSONArray().put(it.km).put(it.samples)) } })
        s.gaugeBars?.let { put("gauge", it) }
        s.lastOdoKm?.let { put("odo", it) }
        s.segBar?.let { put("segBar", it) }
        put("segStart", s.segStartKm)
        put("segClean", s.segClean)
    }

    private fun fromJson(o: JSONObject): FuelState {
        fun optInt(k: String) = if (o.has(k)) o.getInt(k) else null
        val refills = ArrayList<Refill>()
        o.optJSONArray("refills")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                refills += Refill(
                    at = r.getLong("at"),
                    odoKm = r.getInt("odo"),
                    barsBefore = r.optInt("b0", -1),
                    barsAfter = r.optInt("b1", -1),
                    litres = if (r.has("litres")) r.getDouble("litres") else null,
                    litresEstimated = r.optBoolean("est"),
                    amount = if (r.has("amount")) r.getDouble("amount") else null,
                    full = r.optBoolean("full"),
                    answered = r.optBoolean("answered"),
                )
            }
        }
        val bars = MutableList(FuelBook.MAX_BARS + 1) { BarStat() }
        o.optJSONArray("bars")?.let { arr ->
            for (i in 0 until minOf(arr.length(), bars.size)) {
                val b = arr.getJSONArray(i)
                bars[i] = BarStat(b.getDouble(0), b.getInt(1))
            }
        }
        return FuelState(
            refills = refills.sortedByDescending { it.at },
            bars = bars,
            gaugeBars = optInt("gauge"),
            lastOdoKm = optInt("odo"),
            segBar = optInt("segBar"),
            segStartKm = o.optInt("segStart", 0),
            segClean = o.optBoolean("segClean"),
        )
    }
}
