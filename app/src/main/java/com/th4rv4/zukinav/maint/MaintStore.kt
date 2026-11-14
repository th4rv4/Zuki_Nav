package com.th4rv4.zukinav.maint

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps the maintenance reminders in a JSON file in the app's private storage. */
object MaintStore {

    private val lock = Any()
    private var cache: MaintState? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun state(ctx: Context): MaintState = synchronized(lock) { load(ctx) }

    /** One ODO reading from the dash. Returns the notifications to send. */
    fun onOdo(ctx: Context, odoKm: Int): List<Alert> {
        val alerts: List<Alert>
        val changed: Boolean
        synchronized(lock) {
            val before = load(ctx)
            if (before.odoKm == odoKm && before.reminders.none { it.lastKm == null }) return emptyList()
            val now = System.currentTimeMillis()
            val (next, a) = MaintBook.check(MaintBook.onOdo(before, odoKm, now), now, Prefs(ctx).maintEarlyKm)
            alerts = a
            changed = next != before
            if (changed) { cache = next; write(ctx, next) }
        }
        if (changed) notifyListeners()
        return alerts
    }

    /** Checks the date-based reminders without a new ODO reading. */
    fun checkNow(ctx: Context): List<Alert> {
        val alerts: List<Alert>
        synchronized(lock) {
            val before = load(ctx)
            val (next, a) = MaintBook.check(before, System.currentTimeMillis(), Prefs(ctx).maintEarlyKm)
            alerts = a
            if (next != before) { cache = next; write(ctx, next) }
        }
        if (alerts.isNotEmpty()) notifyListeners()
        return alerts
    }

    /** Adds or replaces a reminder. Changing it starts its notifications afresh. */
    fun save(ctx: Context, r: Reminder) = change(ctx) { s ->
        val fixed = r.copy(stage = 0, snoozeUntilKm = null, lastAt = r.lastAt ?: System.currentTimeMillis())
        val list = if (s.reminders.any { it.id == r.id }) s.reminders.map { if (it.id == r.id) fixed else it } else s.reminders + fixed
        s.copy(reminders = list)
    }

    fun delete(ctx: Context, id: Long) = change(ctx) { s -> s.copy(reminders = s.reminders.filter { it.id != id }) }

    fun markDone(ctx: Context, id: Long) = change(ctx) { s ->
        s.copy(reminders = s.reminders.map { if (it.id == id) MaintBook.done(it, s.odoKm, System.currentTimeMillis()) else it })
    }

    /** False if the ODO isn't known yet. */
    fun snooze(ctx: Context, id: Long): Boolean {
        var ok = false
        change(ctx) { s ->
            s.copy(reminders = s.reminders.map { r ->
                if (r.id != id) r else MaintBook.snooze(r, s.odoKm)?.also { ok = true } ?: r
            })
        }
        return ok
    }

    fun newId(ctx: Context): Long = maxOf(100L, (state(ctx).reminders.maxOfOrNull { it.id } ?: 0L) + 1)

    private fun change(ctx: Context, block: (MaintState) -> MaintState) {
        synchronized(lock) {
            val next = block(load(ctx))
            cache = next
            write(ctx, next)
        }
        notifyListeners()
    }

    private fun notifyListeners() = main.post { listeners.forEach { it() } }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "maintenance.json")

    private fun load(ctx: Context): MaintState {
        cache?.let { return it }
        var s = MaintState()
        try {
            val f = file(ctx)
            if (f.exists()) s = fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            AppLog.d("Maintenance", "Could not read maintenance data: $e")
        }
        cache = s
        return s
    }

    private fun write(ctx: Context, s: MaintState) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(toJson(s).toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            AppLog.d("Maintenance", "Could not save maintenance data: $e")
        }
    }

    private fun toJson(s: MaintState) = JSONObject().apply {
        s.odoKm?.let { put("odo", it) }
        put("reminders", JSONArray().apply {
            s.reminders.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("km", r.everyKm)
                    put("months", r.everyMonths)
                    r.lastKm?.let { put("lastKm", it) }
                    r.lastAt?.let { put("lastAt", it) }
                    put("stage", r.stage)
                    r.snoozeUntilKm?.let { put("snooze", it) }
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): MaintState {
        val arr = o.optJSONArray("reminders") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Reminder(
                id = r.getLong("id"),
                name = r.optString("name", "Reminder"),
                everyKm = r.optInt("km", 0),
                everyMonths = r.optInt("months", 0),
                lastKm = if (r.has("lastKm")) r.getInt("lastKm") else null,
                lastAt = if (r.has("lastAt")) r.getLong("lastAt") else null,
                stage = r.optInt("stage", 0),
                snoozeUntilKm = if (r.has("snooze")) r.getInt("snooze") else null,
            )
        }
        return MaintState(list, if (o.has("odo")) o.getInt("odo") else null)
    }
}
