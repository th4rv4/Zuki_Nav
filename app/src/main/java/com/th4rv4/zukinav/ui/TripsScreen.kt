package com.th4rv4.zukinav.ui

import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.th4rv4.zukinav.trips.Trip
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripLog
import com.th4rv4.zukinav.trips.TripStore
import java.util.Locale

/** Trips tab: every saved ride, newest first, with a Starred filter. */
class TripsScreen(host: MainActivity) : Screen(host) {

    private var starredOnly = false
    private val month = ui.text("", 15f, Palette.TEXT, Ui.Weight.MEDIUM)
    private val monthCount = ui.text("", 13f, Palette.MUTED)
    private val monthCard: LinearLayout
    private val list = ui.vstack(10)
    private val empty = ui.text("", 14f, Palette.MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(16), ui.dp(32), ui.dp(16), ui.dp(32))
    }
    private val changed: () -> Unit = { render() }

    override val view: View

    init {
        monthCard = ui.card(
            ui.text("THIS MONTH", 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.08f },
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(month, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(monthCount)
            },
            gapDp = 6,
        )
        view = host.page(
            ui.vstack(2, ui.title("Trips"), ui.text("Saved from the dash, ignition on to ignition off", 13f, Palette.MUTED)),
            ui.segmented("All", "Starred", 0) { starredOnly = it == 1; render() },
            monthCard,
            list,
            empty,
        )
    }

    override fun onShow() {
        TripStore.addListener(changed)
        // A ride left open while the app was closed is saved now.
        TripLog.finishIfIdle(host)
        render()
    }

    override fun onHide() {
        TripStore.removeListener(changed)
    }

    private fun render() {
        val all = TripStore.all(host)
        val (km, n) = TripFormat.monthTotals(all)
        month.text = TripFormat.distance(km).let { if (n == 0) "0 km" else it }
        monthCount.text = if (n == 1) "1 ride" else "$n rides"
        monthCard.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE

        val shown = if (starredOnly) all.filter { it.starred } else all
        list.removeAllViews()
        shown.forEach { list.addView(row(it)) }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            starredOnly && all.isNotEmpty() -> "No starred trips. Star a trip to keep it; older trips are removed after 300 rides."
            !prefs.recordTrips -> "Trip recording is off. Turn on Record Trips in Settings."
            else -> "No trips yet. Rides are saved when the dash is connected, from ignition on to ignition off."
        }
    }

    private fun row(t: Trip): View {
        val is24 = DateFormat.is24HourFormat(host)
        val title = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(TripFormat.title(t), 15f, Palette.TEXT, Ui.Weight.MEDIUM).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (t.starred) addView(ui.text("★", 16f, Palette.ACCENT_SOFT))
        }
        val detail = ui.text(
            String.format(
                Locale.US, "%s · %s · %s",
                TripFormat.whenStarted(t.start, is24), TripFormat.distance(t.distanceKm), TripFormat.duration(t.durationSeconds),
            ),
            13f, Palette.MUTED,
        )
        return ui.vstack(3, title, detail).apply {
            background = ui.pressable(Palette.SURFACE, 16, Palette.BORDER)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { host.showTrip(t.id) }
        }
    }
}
