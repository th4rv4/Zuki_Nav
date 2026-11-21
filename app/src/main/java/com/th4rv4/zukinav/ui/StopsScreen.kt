package com.th4rv4.zukinav.ui

import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.trips.Leg
import com.th4rv4.zukinav.trips.RouteStore
import com.th4rv4.zukinav.trips.Stop
import com.th4rv4.zukinav.trips.Trip
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripStops
import com.th4rv4.zukinav.trips.TripStore
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Places
import java.util.Locale

/**
 * A merged trip's stops as a timeline: start, each leg (distance, time, top
 * speed), each stop (where, arrive–leave, how long, and the rider's label),
 * and the end. Tap a stop to label it.
 */
class StopsScreen(host: MainActivity, val tripId: Long) : Screen(host) {

    private val subtitle = ui.text("", 13f, Palette.MUTED)
    private val timeline = ui.vstack(0)
    private val changed: () -> Unit = { render() }
    private var rebuilding = false

    override val view: View

    init {
        val back = ui.text("‹", 30f, Palette.TEXT).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Back to the trip"
            setPadding(ui.dp(4), 0, ui.dp(14), ui.dp(4))
            setOnClickListener { host.showTrip(tripId) }
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(ui.vstack(2, ui.text("Stops", 22f, Palette.TEXT, Ui.Weight.BOLD), subtitle),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        view = host.page(header, timeline)
    }

    override fun onShow() {
        TripStore.addListener(changed)
        render()
    }

    override fun onHide() {
        TripStore.removeListener(changed)
    }

    private fun trip(): Trip? = TripStore.all(host).firstOrNull { it.id == tripId }

    private fun render() {
        val t = trip() ?: run { host.showTab(MainActivity.Tab.TRIPS); return }
        subtitle.text = TripFormat.title(t) + (TripFormat.stops(t)?.let { " · $it" } ?: "")
        timeline.removeAllViews()
        // A ride with a warm-up but no stops: one part, start to end.
        val legs = if (t.legs.isEmpty() && t.stops == 0) {
            listOf(Leg(t.movedAt ?: t.rideStart, t.end, t.distanceKm, t.movingSeconds, t.topSpeedKmh))
        } else {
            t.legs
        }
        if (legs.isEmpty()) {
            rebuildFromRoute(t)
            timeline.addView(ui.text(
                if (rebuilding) "Working out the stops from the route…"
                else "This trip was merged without its route recorded, so where it stopped isn't known. " +
                    "${TripFormat.stops(t) ?: ""}.",
                13f, Palette.MUTED,
            ).apply { setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0) })
            return
        }
        lookUpPlaces(t)
        val is24 = DateFormat.is24HourFormat(host)
        addPoint(Palette.OK, t.startPlace ?: "Start", "Start", TripFormat.time(t.rideStart, is24), null)
        if (t.warmUp) addWarmUp(t, is24)
        legs.forEachIndexed { i, leg ->
            addLeg(leg)
            val stop = t.stopList.getOrNull(i)
            if (stop != null) addStop(t, stop, i, is24)
        }
        addPoint(android.graphics.Color.WHITE, t.endPlace ?: "End", "End", TripFormat.time(t.end, is24), null)
    }

    // ---- timeline rows ------------------------------------------------------

    /** Left column: a dot, or a line for legs, so the timeline reads as one track. */
    private fun rail(dotColor: Int?): View = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        if (dotColor != null) {
            addView(View(ui.ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                    setStroke(ui.dp(2), Palette.BG)
                }
            }, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { topMargin = ui.dp(14) })
        } else {
            addView(View(ui.ctx).apply { setBackgroundColor(Palette.BORDER) },
                LinearLayout.LayoutParams(ui.dp(2), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun row(rail: View, body: View): LinearLayout = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(rail, LinearLayout.LayoutParams(ui.dp(22), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = ui.dp(10) })
        addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun addPoint(color: Int, title: String, sub: String, time: String, onClick: (() -> Unit)?) {
        val body = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            addView(ui.vstack(2, ui.text(title, 15f, Palette.TEXT, Ui.Weight.MEDIUM), ui.text(sub, 12f, Palette.MUTED)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text(time, 13f, Palette.TEXT_SOFT))
            if (onClick != null) setOnClickListener { onClick() }
        }
        timeline.addView(row(rail(color), body))
    }

    private fun addLeg(leg: Leg) {
        val km = String.format(Locale.US, if (leg.distanceKm < 10) "%.1f" else "%.0f", leg.distanceKm)
        val text = "$km km · ${TripFormat.shortDuration((leg.end - leg.start) / 1000)} · top ${leg.topSpeedKmh}"
        val body = ui.text(text, 12f, Palette.TEXT_SOFT).apply {
            background = ui.rounded(Palette.INSET, 10)
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        }
        val wrap = LinearLayout(ui.ctx).apply {
            setPadding(0, ui.dp(2), 0, ui.dp(2))
            addView(body)
        }
        timeline.addView(row(rail(null), wrap))
    }

    /** Engine Warm Up: from switch-on to moving off, when the engine heated up. Not a stop. */
    private fun addWarmUp(t: Trip, is24: Boolean) {
        val moved = t.movedAt ?: return
        val time = TripFormat.time(t.start, is24) + " – " + TripFormat.time(moved, is24)
        val temps = if (t.warmFromC != null && t.warmToC != null) " · ${t.warmFromC} → ${t.warmToC} °C" else ""
        val pill = ui.text(TripFormat.shortDuration(t.warmUpSeconds) + temps, 11f, Palette.ACCENT_SOFT, Ui.Weight.MEDIUM).apply {
            background = ui.rounded(Palette.INSET, 8)
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        val card = ui.vstack(
            4,
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.text("Engine Warm Up", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text(time, 13f, Palette.TEXT_SOFT))
            },
            LinearLayout(ui.ctx).apply { addView(pill) },
        ).apply {
            background = ui.rounded(Palette.SURFACE, 14)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        }
        val wrap = LinearLayout(ui.ctx).apply {
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        timeline.addView(row(rail(Palette.ACCENT_SOFT), wrap))
    }

    private fun addStop(t: Trip, s: Stop, index: Int, is24: Boolean) {
        val label = s.label?.takeIf { it.isNotBlank() }
        val time = TripFormat.time(s.arrive, is24) + " – " + TripFormat.time(s.depart, is24)
        val warm = if (s.warmUpSeconds >= 60) " · incl. ${TripFormat.shortDuration(s.warmUpSeconds)} warm-up" else ""
        val pill = ui.text("Stopped ${TripFormat.shortDuration(s.seconds)}$warm", 11f, Palette.WARN, Ui.Weight.MEDIUM).apply {
            background = ui.rounded(Palette.WARN_BG, 8)
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        val lines = ArrayList<View>()
        lines += LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.text(label ?: s.place ?: "Stop ${index + 1}", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text(time, 13f, Palette.TEXT_SOFT))
        }
        if (label != null && s.place != null) lines += ui.text(s.place, 12f, Palette.MUTED)
        lines += LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pill)
            if (label == null) {
                addView(ui.text("+ Add Label", 12f, Palette.ACCENT_SOFT, Ui.Weight.MEDIUM),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = ui.dp(10) })
            }
        }
        val card = ui.vstack(4, *lines.toTypedArray()).apply {
            background = ui.pressable(Palette.SURFACE, 14)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { labelSheet(t, s, index, is24) }
        }
        val wrap = LinearLayout(ui.ctx).apply {
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        timeline.addView(row(rail(Palette.WARN), wrap))
    }

    // ---- labelling -----------------------------------------------------------

    private fun labelSheet(t: Trip, s: Stop, index: Int, is24: Boolean) {
        lateinit var dialog: android.app.Dialog
        val input: EditText = ui.input(s.label ?: "", "Chai Break", numeric = false) {}.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            filters = arrayOf(android.text.InputFilter.LengthFilter(30))
        }
        fun chip(text: String, onTap: () -> Unit): TextView = ui.text(text, 13f, Palette.TEXT).apply {
            background = ui.pressable(Palette.INSET, 16)
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }
        fun pick(label: String) {
            save(t, index, label)
            dialog.dismiss()
        }
        val chips = PRESETS.map { (icon, name) -> chip("$icon $name") { pick(name) } }.toMutableList()
        chips += chip("✎ Other") {
            input.requestFocus()
            input.setSelection(input.text.length)
            host.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        val recent = prefs.recentStopLabels.filter { r -> PRESETS.none { it.second.equals(r, ignoreCase = true) } }
        val views = ArrayList<View>()
        views += ui.vstack(
            2,
            ui.text("Name This Stop", 18f, Palette.TEXT, Ui.Weight.BOLD),
            ui.text(
                listOfNotNull(s.place, TripFormat.time(s.arrive, is24) + " – " + TripFormat.time(s.depart, is24)).joinToString(" · "),
                13f, Palette.MUTED,
            ),
        )
        views += input
        views += ui.text("Quick Labels", 12f, Palette.MUTED, Ui.Weight.MEDIUM)
        views += chipRows(chips)
        if (recent.isNotEmpty()) {
            views += ui.text("Your Labels", 12f, Palette.MUTED, Ui.Weight.MEDIUM)
            views += chipRows(recent.map { r -> chip(r) { pick(r) } })
        }
        views += ui.hstack(
            10,
            ui.button("Clear", Ui.Btn.SECONDARY, 48) {
                save(t, index, null)
                dialog.dismiss()
            }.apply { setTextColor(Palette.DANGER) },
            ui.button("Save", Ui.Btn.PRIMARY, 48) {
                val typed = input.text.toString().trim()
                save(t, index, typed.ifEmpty { null })
                dialog.dismiss()
            },
            weighted = true,
        )
        dialog = ui.sheet(*views.toTypedArray())
    }

    /** Chips three to a row. */
    private fun chipRows(chips: List<TextView>): View = ui.vstack(
        8,
        *chips.chunked(3).map { row ->
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { c ->
                    addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = ui.dp(8) })
                }
            }
        }.toTypedArray(),
    )

    private fun save(t: Trip, index: Int, label: String?) {
        val current = trip() ?: return
        if (index !in current.stopList.indices) return
        val clean = label?.trim()?.takeIf { it.isNotEmpty() }
        val stops = current.stopList.toMutableList()
        stops[index] = stops[index].copy(label = clean)
        TripStore.update(host, current.copy(stopList = stops))
        if (clean != null && PRESETS.none { it.second.equals(clean, ignoreCase = true) }) prefs.rememberStopLabel(clean)
    }

    // ---- filling in details ---------------------------------------------------

    /** Trips merged before stops were kept: find them in the route's time gaps. */
    private fun rebuildFromRoute(t: Trip) {
        if (rebuilding || t.stops == 0) return
        rebuilding = true
        val app = host.applicationContext
        Thread {
            val found = TripStops.fromRoute(RouteStore.points(app, t), expected = t.stops)
            host.runOnUiThread {
                rebuilding = false
                if (found == null) {
                    AppLog.d("Trips", "No stops found in the route of ${t.id}")
                    render()
                    return@runOnUiThread
                }
                val current = trip() ?: return@runOnUiThread
                TripStore.update(host, current.copy(legs = found.first, stopList = found.second))
            }
        }.start()
    }

    private var lookedUp = false

    /** Stops without a place name: look them up once, in the background. */
    private fun lookUpPlaces(t: Trip) {
        if (lookedUp || t.stopList.none { it.place == null && it.lat != null }) return
        lookedUp = true
        val app = host.applicationContext
        Thread {
            val named = t.stopList.map { s ->
                if (s.place != null || s.lat == null || s.lng == null) s else s.copy(place = Places.name(app, s.lat, s.lng))
            }
            if (named == t.stopList) return@Thread
            val current = TripStore.all(app).firstOrNull { it.id == tripId } ?: return@Thread
            val merged = current.stopList.mapIndexed { i, s -> if (s.place == null) s.copy(place = named.getOrNull(i)?.place) else s }
            TripStore.update(app, current.copy(stopList = merged))
        }.start()
    }

    companion object {
        val PRESETS = listOf(
            "⛽" to "Fuel",
            "☕" to "Chai Break",
            "🍽" to "Food",
            "📷" to "Photos",
            "🛠" to "Bike Check",
            "🧍" to "Rest",
        )
    }
}
