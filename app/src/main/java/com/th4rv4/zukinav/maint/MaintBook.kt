package com.th4rv4.zukinav.maint

import java.util.Calendar
import java.util.Locale

/**
 * One maintenance job, due every [everyKm] (by the dash's ODO) and/or every
 * [everyMonths], counted from when it was last done.
 */
data class Reminder(
    val id: Long,
    val name: String,
    val everyKm: Int = 0,
    val everyMonths: Int = 0,
    /** ODO when it was last done; null until the first ODO reading. */
    val lastKm: Int? = null,
    /** When it was last done (or when counting started), ms. */
    val lastAt: Long? = null,
    /** Notifications already sent for this round: 0 none, 1 "due soon", 2 "due". */
    val stage: Int = 0,
    /** Snoozed until the ODO reaches this. */
    val snoozeUntilKm: Int? = null,
    /** Off: not tracked at all. Turning it on starts counting from that moment. */
    val enabled: Boolean = true,
) {
    /** Has an interval set, so it can come due. */
    val active: Boolean get() = everyKm > 0 || everyMonths > 0
}

data class MaintState(
    val reminders: List<Reminder> = MaintBook.defaults(),
    /** Last ODO read from the dash. */
    val odoKm: Int? = null,
)

/** Where a reminder stands now. Negative [leftKm] / [leftDays] mean overdue. */
data class Status(
    val leftKm: Int?,
    val leftDays: Int?,
    val due: Boolean,
    val soon: Boolean,
    /** Due soon because of km (rather than the date). */
    val soonByKm: Boolean,
    val fraction: Float,
)

enum class Kind { SOON, DUE }

data class Alert(val reminder: Reminder, val kind: Kind)

object MaintBook {

    const val CHAIN_ID = 1L
    const val SERVICE_ID = 2L
    const val SNOOZE_KM = 100
    /** A time-based reminder counts as "due soon" this many days before. */
    const val SOON_DAYS = 7
    private const val DAY = 86_400_000L

    fun defaults(): List<Reminder> = listOf(
        // Off until the rider turns them on, so they start counting from the
        // right moment rather than from the day the app was installed.
        Reminder(CHAIN_ID, "Chain Maintenance", everyKm = 500, enabled = false),
        Reminder(SERVICE_ID, "General Service", enabled = false),
    )

    fun dueAt(r: Reminder): Long? {
        val from = r.lastAt ?: return null
        if (r.everyMonths <= 0) return null
        return Calendar.getInstance().apply {
            timeInMillis = from
            add(Calendar.MONTH, r.everyMonths)
        }.timeInMillis
    }

    fun status(r: Reminder, odoKm: Int?, now: Long, earlyKm: Int): Status {
        if (!r.enabled) return Status(null, null, due = false, soon = false, soonByKm = false, fraction = 0f)
        val leftKm = if (r.everyKm > 0 && r.lastKm != null && odoKm != null) r.lastKm + r.everyKm - odoKm else null
        val dueAt = dueAt(r)
        val leftDays = dueAt?.let { Math.floorDiv(it - now, DAY).toInt() }
        val due = (leftKm != null && leftKm <= 0) || (dueAt != null && now >= dueAt)
        val soonByKm = !due && leftKm != null && leftKm <= earlyKm
        val soon = soonByKm || (!due && leftDays != null && leftDays < SOON_DAYS)
        val kmFrac = leftKm?.let { 1f - it.toFloat() / r.everyKm } ?: 0f
        val timeFrac = if (dueAt != null && r.lastAt != null && dueAt > r.lastAt) {
            (now - r.lastAt).toFloat() / (dueAt - r.lastAt)
        } else 0f
        return Status(leftKm, leftDays, due, soon, soonByKm, maxOf(kmFrac, timeFrac).coerceIn(0f, 1f))
    }

    /** A new ODO reading: reminders that are on but have no start yet begin counting from here. */
    fun onOdo(s: MaintState, odoKm: Int, now: Long): MaintState = s.copy(
        odoKm = odoKm,
        reminders = s.reminders.map { r ->
            if (r.enabled && r.lastKm == null) r.copy(lastKm = odoKm, lastAt = r.lastAt ?: now) else r
        },
    )

    /** Works out which notifications to send now, and remembers that they were sent. */
    fun check(s: MaintState, now: Long, earlyKm: Int): Pair<MaintState, List<Alert>> {
        val alerts = ArrayList<Alert>()
        val next = s.reminders.map { start ->
            var r = start
            if (!r.active || !r.enabled) return@map r
            r.snoozeUntilKm?.let { until ->
                if (s.odoKm == null || s.odoKm < until) return@map r
                // Snooze over: remind again that it is due.
                r = r.copy(snoozeUntilKm = null, stage = minOf(r.stage, 1))
            }
            val st = status(r, s.odoKm, now, earlyKm)
            when {
                st.due && r.stage < 2 -> { alerts += Alert(r, Kind.DUE); r = r.copy(stage = 2) }
                st.soon && r.stage < 1 -> { alerts += Alert(r, Kind.SOON); r = r.copy(stage = 1) }
            }
            r
        }
        return s.copy(reminders = next) to alerts
    }

    /**
     * On: counting starts now, from [odoKm] (or the next ODO reading), unless
     * a Last Done At was entered while it was off. Off: forgets the count.
     */
    fun setEnabled(r: Reminder, on: Boolean, odoKm: Int?, now: Long): Reminder =
        if (on) r.copy(enabled = true, lastKm = r.lastKm ?: odoKm, lastAt = r.lastAt ?: now, stage = 0, snoozeUntilKm = null)
        else r.copy(enabled = false, lastKm = null, lastAt = null, stage = 0, snoozeUntilKm = null)

    /** Done at [odoKm] (or the last known ODO): counting starts again. */
    fun done(r: Reminder, odoKm: Int?, now: Long): Reminder =
        r.copy(lastKm = odoKm ?: r.lastKm, lastAt = now, stage = 0, snoozeUntilKm = null)

    /** Quiet for the next [SNOOZE_KM] km. Null if the ODO isn't known yet. */
    fun snooze(r: Reminder, odoKm: Int?): Reminder? =
        odoKm?.let { r.copy(snoozeUntilKm = it + SNOOZE_KM) }

    fun km(v: Int): String = String.format(Locale.US, "%,d", v)

    /** Short state for the chip: "DUE IN 54 KM", "486 KM OVER", "DUE IN 5 DAYS", "2,514 KM". */
    fun chip(r: Reminder, st: Status): String = when {
        !r.active -> "SET INTERVAL"
        !r.enabled -> "OFF"
        st.leftKm != null && st.leftKm <= 0 -> if (st.leftKm == 0) "DUE NOW" else "${km(-st.leftKm)} KM OVER"
        st.due -> "OVERDUE"
        st.soonByKm -> "DUE IN ${km(st.leftKm!!)} KM"
        st.soon -> "DUE IN ${days(st.leftDays!!)}"
        st.leftKm != null -> "${km(st.leftKm)} KM"
        st.leftDays != null -> "IN ${days(st.leftDays)}"
        else -> "WAITING FOR ODO"
    }

    private fun days(d: Int): String = if (d == 1) "1 DAY" else "$d DAYS"

    /** Notification title. */
    fun headline(a: Alert, st: Status): String {
        val name = a.reminder.name
        return when {
            a.kind == Kind.DUE && st.leftKm != null && st.leftKm < 0 -> "$name overdue"
            a.kind == Kind.DUE -> "$name is due"
            st.soonByKm -> "$name due in ${km(st.leftKm!!)} km"
            st.leftDays != null -> "$name due in ${days(st.leftDays).lowercase(Locale.US)}"
            else -> "$name due soon"
        }
    }

    /** Notification body. */
    fun detail(r: Reminder, st: Status): String = when {
        st.leftKm != null && st.leftKm < 0 -> "${km(-st.leftKm)} km past due. Last done at ${km(r.lastKm ?: 0)} km."
        r.lastKm != null && r.everyKm > 0 -> "Last done at ${km(r.lastKm)} km."
        else -> "It has been ${r.everyMonths} months since it was last done."
    }
}
