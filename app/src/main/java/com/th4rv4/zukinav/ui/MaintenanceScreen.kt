package com.th4rv4.zukinav.ui

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.maint.MaintBook
import com.th4rv4.zukinav.maint.MaintStore
import com.th4rv4.zukinav.maint.Reminder
import com.th4rv4.zukinav.service.DashService
import java.util.Locale

/**
 * Maintenance: chain, service and the rider's own reminders, counted
 * from the dash's ODO and/or by months, with a phone notification when one
 * is close or due.
 */
class MaintenanceScreen(host: MainActivity) : Screen(host) {

    private val odoLine = ui.text("", 13f, Palette.MUTED)
    private val list = ui.vstack(10)
    private val changed: () -> Unit = { render() }

    override val view: View

    init {
        val add = ui.button("+ Add Reminder", Ui.Btn.SECONDARY, 48) { ReminderSheet(host, null).show() }
        view = host.page(
            ui.vstack(2, ui.title("Maintenance"), odoLine).apply { setPadding(ui.dp(4), 0, 0, 0) },
            list,
            add,
            ui.sectionHeader(R.drawable.ic_sec_wrench, "Maintenance Settings"),
            ui.listCard(
                ui.row("Remind Me Early", "km before a reminder is due",
                    ui.input(prefs.maintEarlyKm.toString(), "50", numeric = true) {
                        prefs.maintEarlyKm = it.toIntOrNull()?.coerceIn(0, 5000) ?: 50
                        render()
                    }),
            ),
            ui.text(
                "Reminders count from the ODO on your dash, so km ridden without the app still count: they're " +
                    "caught up the next time the bike connects. Month-based reminders arrive even when it isn't connected.",
                12f, Palette.MUTED,
            ).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) },
        )
    }

    override fun onShow() {
        MaintStore.addListener(changed)
        // Catch up straight away if the dash has sent a newer ODO.
        DashService.liveBike?.odoKm?.takeIf { it > 0 }?.let {
            com.th4rv4.zukinav.maint.MaintNotifier.show(host, MaintStore.onOdo(host, it))
        }
        render()
    }

    override fun onHide() {
        MaintStore.removeListener(changed)
    }

    private fun render() {
        val s = MaintStore.state(host)
        val odo = s.odoKm
        odoLine.text = if (odo != null) "ODO ${MaintBook.km(odo)} km, read from the dash"
        else "Connect the bike once to read the ODO"
        list.removeAllViews()
        val now = System.currentTimeMillis()
        s.reminders.forEach { r ->
            list.addView(card(r, MaintBook.status(r, odo, now, prefs.maintEarlyKm)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun card(r: Reminder, st: com.th4rv4.zukinav.maint.Status): View {
        val (fg, bg) = when {
            !r.active || !r.enabled -> Palette.MUTED to Palette.INSET
            st.due -> Palette.DANGER to Palette.DANGER_BG
            st.soon -> Palette.WARN to Palette.WARN_BG
            else -> Palette.OK to Palette.OK_BG
        }
        val chip = ui.text(MaintBook.chip(r, st), 10f, fg, Ui.Weight.BOLD).apply {
            letterSpacing = 0.08f
            background = ui.rounded(bg, 8)
            setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
        }
        val top = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.vstack(2, ui.text(r.name, 15f, Palette.TEXT, Ui.Weight.MEDIUM), ui.text(describe(r), 12f, Palette.MUTED)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(8) })
            addView(chip)
            addView(onSwitch(r), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = ui.dp(6) })
        }
        val views = arrayListOf<View>(top)
        val edit = ui.button(if (r.active) "Edit" else "Set Interval", Ui.Btn.SECONDARY, 40) { ReminderSheet(host, r).show() }
        if (r.enabled) {
            if (r.active) views += bar(st.fraction, if (st.due || st.soon) fg else Palette.ACCENT)
            views += ui.hstack(8, ui.button("Mark Done", Ui.Btn.SECONDARY, 40) { confirmDone(r) }, edit, weighted = true)
        } else {
            views += edit
        }
        return ui.card(*views.toTypedArray(), gapDp = 10, padDp = 14)
    }

    private fun onSwitch(r: Reminder): View = android.widget.Switch(ui.ctx).apply {
        isChecked = r.enabled
        contentDescription = "Track ${r.name}"
        thumbTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(android.graphics.Color.WHITE, Palette.SWITCH_THUMB_OFF),
        )
        trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Palette.ACCENT, Palette.INPUT_BORDER),
        )
        setOnCheckedChangeListener { _, on ->
            MaintStore.setEnabled(host, r.id, on)
            if (!on) {
                host.getSystemService(android.app.NotificationManager::class.java)
                    .cancel(com.th4rv4.zukinav.maint.MaintNotifier.notifyId(r.id))
            } else {
                val odo = MaintStore.state(host).odoKm
                val from = MaintStore.state(host).reminders.firstOrNull { it.id == r.id }?.lastKm
                host.toast(
                    when {
                        from == null -> "${r.name} starts counting at the next ODO reading from the dash"
                        from == odo -> "${r.name} counts from ${MaintBook.km(from)} km"
                        else -> "${r.name} counts from your Last Done At, ${MaintBook.km(from)} km"
                    },
                )
                // A Last Done At entered while off can already be close or due.
                com.th4rv4.zukinav.maint.MaintNotifier.show(host, MaintStore.checkNow(host))
            }
        }
    }

    private fun describe(r: Reminder): String {
        val every = listOfNotNull(
            r.everyKm.takeIf { it > 0 }?.let { "${MaintBook.km(it)} km" },
            r.everyMonths.takeIf { it > 0 }?.let { if (it == 1) "1 month" else "$it months" },
        )
        if (every.isEmpty()) return "No interval set yet"
        if (!r.enabled) return "Every ${every.joinToString(" or ")}\nOff. Turn on to start counting from now"
        val last = r.lastKm?.let { " · last at ${MaintBook.km(it)}" }.orEmpty()
        return "Every ${every.joinToString(" or ")}$last"
    }

    private fun bar(fraction: Float, color: Int): View = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = ui.rounded(Palette.TRACK, 3)
        val f = fraction.coerceIn(0.02f, 1f)
        addView(View(ui.ctx).apply { background = ui.rounded(color, 3) },
            LinearLayout.LayoutParams(0, ui.dp(6), f))
        if (f < 1f) addView(View(ui.ctx), LinearLayout.LayoutParams(0, ui.dp(6), 1f - f))
    }

    private fun confirmDone(r: Reminder) {
        val odo = MaintStore.state(host).odoKm
        if (odo == null) {
            host.toast("Connect the bike once so the ODO is known, or use Edit to type Last Done At")
            return
        }
        MaintStore.markDone(host, r.id)
        host.getSystemService(android.app.NotificationManager::class.java)
            .cancel(com.th4rv4.zukinav.maint.MaintNotifier.notifyId(r.id))
        host.toast("${r.name} marked done at ${MaintBook.km(odo)} km")
    }
}

/** Bottom sheet to add or edit a reminder. */
class ReminderSheet(private val host: MainActivity, private val reminder: Reminder?) {
    private val ui = host.ui

    fun show() {
        val r = reminder
        val name = field(r?.name ?: "", "Air Filter", numeric = false)
        val km = field(r?.everyKm?.takeIf { it > 0 }?.toString() ?: "", "Off", numeric = true)
        val months = field(r?.everyMonths?.takeIf { it > 0 }?.toString() ?: "", "Off", numeric = true)
        val last = field(r?.lastKm?.toString() ?: "", if (r != null && !r.enabled) "When turned on" else "Current ODO", numeric = true)

        val views = ArrayList<View>()
        views += ui.vstack(
            2,
            ui.text(if (r == null) "Add Reminder" else "Edit Reminder", 18f, Palette.TEXT, Ui.Weight.BOLD),
            ui.text("Set km, months or both; whichever comes first.", 13f, Palette.MUTED),
        )
        views += labelled("Name", name)
        views += ui.hstack(10, labelled("Every km", km), labelled("Or Every Months", months), weighted = true)
        views += labelled("Last Done At (ODO km)", last)

        lateinit var dialog: Dialog
        val save = ui.button("Save", Ui.Btn.PRIMARY, 48) { if (save(name, km, months, last)) dialog.dismiss() }
        val second = if (r == null) {
            ui.button("Cancel", Ui.Btn.SECONDARY, 48) { dialog.dismiss() }
        } else {
            ui.button("Delete", Ui.Btn.SECONDARY, 48) {
                dialog.dismiss()
                android.app.AlertDialog.Builder(host)
                    .setTitle("Delete ${r.name}?")
                    .setPositiveButton("Delete") { _, _ -> MaintStore.delete(host, r.id) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }.apply { setTextColor(Palette.DANGER) }
        }
        views += ui.hstack(10, second, save, weighted = true)
        dialog = ui.sheet(*views.toTypedArray())
    }

    private fun save(name: EditText, km: EditText, months: EditText, last: EditText): Boolean {
        val n = name.text.toString().trim()
        if (n.isEmpty()) { host.toast("Give the reminder a name"); return false }
        val everyKm = km.text.toString().trim().toIntOrNull()?.coerceIn(0, 100_000) ?: 0
        val everyMonths = months.text.toString().trim().toIntOrNull()?.coerceIn(0, 120) ?: 0
        val odo = MaintStore.state(host).odoKm
        val typedLast = last.text.toString().trim().toIntOrNull()
        if (typedLast != null && odo != null && typedLast > odo) {
            host.toast("Last Done At can't be more than the ODO (${MaintBook.km(odo)} km)")
            return false
        }
        val r = reminder
        // An off reminder only gets a start if one is typed; otherwise it starts when turned on.
        val lastKm = if (r != null && !r.enabled) typedLast else typedLast ?: r?.lastKm ?: odo
        // A new start point (or a new reminder) restarts the month count too.
        val restart = r == null || typedLast != r.lastKm
        MaintStore.save(
            host,
            Reminder(
                id = r?.id ?: MaintStore.newId(host),
                name = n,
                everyKm = everyKm,
                everyMonths = everyMonths,
                lastKm = lastKm,
                lastAt = if (restart) System.currentTimeMillis() else r?.lastAt,
            ),
        )
        return true
    }

    private fun field(value: String, hint: String, numeric: Boolean): EditText =
        ui.input(value, hint, numeric = numeric) {}.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }

    private fun labelled(label: String, v: View): View =
        ui.vstack(6, ui.text(label.uppercase(Locale.US), 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.1f }, v)
}
