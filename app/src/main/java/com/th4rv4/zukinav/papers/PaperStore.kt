package com.th4rv4.zukinav.papers

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A bike paper with an expiry date (PUC, insurance, licence...), and
 * optionally photos of it. [pages] are file names in [PaperStore.dir].
 */
data class Paper(
    val id: Long,
    val name: String,
    /** Expiry as LocalDate.toEpochDay(), or null when not set. */
    val expires: Long? = null,
    /** Months a renewal usually lasts, to suggest the next date; 0 for none. */
    val renewMonths: Int = 0,
    val pages: List<String> = emptyList(),
    /** 0 nothing sent, 1 "expires soon" sent, 2 "expired" sent, for this date. */
    val notified: Int = 0,
) {
    fun daysLeft(today: LocalDate = LocalDate.now()): Long? =
        expires?.let { ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(it)) }
}

object PaperStore {

    const val MAX_PAGES = 4
    /** Days before expiry for the first notification. */
    const val SOON_DAYS = 15

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun defaults(): List<Paper> = listOf(
        Paper(1, "PUC", renewMonths = 6),
        Paper(2, "Insurance", renewMonths = 12),
        Paper(3, "Driving Licence"),
    )

    fun all(ctx: Context): List<Paper> = synchronized(lock) { load(ctx) }

    fun get(ctx: Context, id: Long): Paper? = all(ctx).firstOrNull { it.id == id }

    fun newId(ctx: Context): Long = maxOf(100L, (all(ctx).maxOfOrNull { it.id } ?: 0L) + 1)

    /** Saves [p]; a changed date sends the reminders again for the new date. */
    fun save(ctx: Context, p: Paper) = change(ctx) { list ->
        val old = list.firstOrNull { it.id == p.id }
        val fixed = if (old != null && old.expires != p.expires) p.copy(notified = 0) else p
        if (old != null) list.map { if (it.id == p.id) fixed else it } else list + fixed
    }

    fun delete(ctx: Context, id: Long) {
        get(ctx, id)?.pages?.forEach { File(dir(ctx), it).delete() }
        change(ctx) { list -> list.filter { it.id != id } }
    }

    fun setNotified(ctx: Context, id: Long, stage: Int) = change(ctx, quiet = true) { list ->
        list.map { if (it.id == id) it.copy(notified = stage) else it }
    }

    /** Where page images are kept: app-private, and part of Backup & Restore. */
    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "papers").apply { mkdirs() }

    private fun change(ctx: Context, quiet: Boolean = false, block: (List<Paper>) -> List<Paper>) {
        synchronized(lock) { write(ctx, block(load(ctx))) }
        if (!quiet) main.post { listeners.forEach { it() } }
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "papers.json")

    private fun load(ctx: Context): List<Paper> {
        val f = file(ctx)
        if (!f.exists()) return defaults()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("papers") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pages = o.optJSONArray("pages") ?: JSONArray()
                Paper(
                    id = o.getLong("id"),
                    name = o.optString("name"),
                    expires = if (o.has("expires")) o.getLong("expires") else null,
                    renewMonths = o.optInt("renew", 0),
                    pages = (0 until pages.length()).map { pages.getString(it) },
                    notified = o.optInt("notified", 0),
                )
            }
        } catch (_: Exception) {
            defaults()
        }
    }

    private fun write(ctx: Context, list: List<Paper>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                p.expires?.let { put("expires", it) }
                put("renew", p.renewMonths)
                put("pages", JSONArray(p.pages))
                put("notified", p.notified)
            })
        }
        val f = file(ctx)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONObject().put("papers", arr).toString())
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }
}
