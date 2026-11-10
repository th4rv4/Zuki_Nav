package com.th4rv4.zukinav.ui

import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.trips.Trip
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripLog
import com.th4rv4.zukinav.trips.TripStore
import com.th4rv4.zukinav.util.Places
import java.util.Locale

/** Trips tab: every saved ride, newest first, with a Starred filter. */
class TripsScreen(host: MainActivity) : Screen(host) {

    private var starredOnly = false
    private val monthKm = stat()
    private val monthRides = stat()
    private val monthTime = stat()
    private val monthCard: LinearLayout
    private val list = ui.vstack(10)
    private val empty = ui.text("", 14f, Palette.MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(16), ui.dp(32), ui.dp(16), ui.dp(32))
    }
    private val changed: () -> Unit = { render() }

    private val placesRow = ui.switchRow(
        "Start And End Places",
        "Names where each ride starts and ends. Needs location, allowed all the time.",
        prefs.tripPlaces && Places.hasPermission(host),
    ) { on -> setTripPlaces(on) }
    private val placesSwitch = placesRow.getChildAt(placesRow.childCount - 1) as Switch
    private var syncingPlaces = false

    override val view: View

    init {
        monthCard = ui.card(
            cap("This Month").apply { gravity = Gravity.CENTER },
            ui.hstack(0, column(monthKm, "km"), column(monthRides, "Rides"), column(monthTime, "Riding"), weighted = true),
            gapDp = 10,
        )
        view = host.page(
            ui.vstack(2, ui.title("Trips"), ui.text("Saved from the dash, ignition on to ignition off", 13f, Palette.MUTED))
                .apply { setPadding(ui.dp(4), 0, 0, 0) },
            monthCard,
            ui.segmented(listOf("All", "Starred"), 0, fill = true) { starredOnly = it == 1; render() },
            list,
            empty,
            ui.sectionHeader(R.drawable.ic_sec_route, "Trip Settings"),
            ui.listCard(
                ui.switchRow("Record Trips", "Save each ride, ignition on to ignition off", prefs.recordTrips) {
                    prefs.recordTrips = it
                    if (!it) TripLog.stopRecording(host)
                    render()
                },
                ui.switchRow("Skip Short Rides", "Don't save rides under ${(TripLog.SHORT_RIDE_KM * 1000).toInt()} m", prefs.skipShortTrips) {
                    prefs.skipShortTrips = it
                },
                placesRow,
            ),
        )
    }

    override fun onShow() {
        TripStore.addListener(changed)
        // A ride left open while the app was closed is saved now.
        TripLog.finishIfIdle(host)
        // Location may have been taken away in Android's settings.
        if (!host.askingLocation) showPlaces(prefs.tripPlaces && Places.hasPermission(host))
        render()
    }

    private fun setTripPlaces(on: Boolean) {
        if (syncingPlaces) return
        if (!on) { prefs.tripPlaces = false; return }
        host.requestTripLocation { granted ->
            prefs.tripPlaces = granted
            showPlaces(granted)
            if (!granted) host.toast("Start And End Places needs location allowed all the time")
        }
    }

    private fun showPlaces(on: Boolean) {
        if (placesSwitch.isChecked == on) return
        syncingPlaces = true
        placesSwitch.isChecked = on
        syncingPlaces = false
    }

    override fun onHide() {
        TripStore.removeListener(changed)
    }

    private fun render() {
        val all = TripStore.all(host)
        val (km, n) = TripFormat.monthTotals(all)
        monthKm.text = if (km < 10) String.format(Locale.US, "%.1f", km) else km.toInt().toString()
        monthRides.text = n.toString()
        monthTime.text = TripFormat.shortDuration(TripFormat.monthSeconds(all))
        monthCard.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE

        val shown = if (starredOnly) all.filter { it.starred } else all
        list.removeAllViews()
        var lastDay: String? = null
        shown.forEach { t ->
            val day = TripFormat.day(t.start)
            if (day != lastDay) {
                list.addView(cap(day).apply { setPadding(ui.dp(4), if (lastDay == null) 0 else ui.dp(6), 0, 0) })
                lastDay = day
            }
            list.addView(row(t))
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            starredOnly && all.isNotEmpty() -> "No starred trips. Star a trip to keep it; older trips are removed after 300 rides."
            !prefs.recordTrips -> "Trip recording is off. Turn on Record Trips in Settings."
            else -> "No trips yet. Rides are saved when the dash is connected, from ignition on to ignition off."
        }
    }

    private fun stat() = android.widget.TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = 24f
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
    }

    private fun cap(s: String) = ui.text(s.uppercase(Locale.US), 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f }

    private fun column(value: android.widget.TextView, label: String): LinearLayout =
        ui.vstack(0, value, cap(label).apply { gravity = Gravity.CENTER }).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private fun row(t: Trip): View {
        val is24 = DateFormat.is24HourFormat(host)
        val title = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.vstack(
                2,
                ui.text(TripFormat.title(t), 15f, Palette.TEXT, Ui.Weight.MEDIUM).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                ui.text(TripFormat.time(t.start, is24) + " – " + TripFormat.time(t.end, is24), 12f, Palette.MUTED),
            ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (t.starred) addView(ui.text("★", 18f, Palette.WARN))
        }
        val distance = if (t.distanceKm < 1.0) String.format(Locale.US, "%.2f", t.distanceKm) else String.format(Locale.US, "%.1f", t.distanceKm)
        val numbers = ui.hstack(
            0,
            column(small(distance), "km"),
            column(small(TripFormat.shortDuration(t.durationSeconds)), "Time"),
            column(small(t.topSpeedKmh.toString()), "Top"),
            weighted = true,
        )
        return ui.vstack(12, title, numbers).apply {
            background = ui.pressable(Palette.SURFACE, 18, Palette.BORDER)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = TripFormat.title(t) + ", " + TripFormat.distance(t.distanceKm)
            setOnClickListener { host.showTrip(t.id) }
        }
    }

    private fun small(s: String) = stat().apply { text = s; textSize = 19f }
}
