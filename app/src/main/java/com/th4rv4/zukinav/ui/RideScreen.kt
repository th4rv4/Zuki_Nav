package com.th4rv4.zukinav.ui

import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.ble.ClusterLink
import com.th4rv4.zukinav.nav.NavRepository
import com.th4rv4.zukinav.nav.NavSnapshot
import com.th4rv4.zukinav.protocol.BikeNames
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripLog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home tab: the bike, live guidance and what is being sent to the dash. */
class RideScreen(host: MainActivity) : Screen(host) {

    private val panel = DashPanel(host)
    private val statusSlot = FrameLayout(host.ui.ctx)
    private val iconValue = ui.text("", 13f, Palette.TEXT_SOFT)

    private val rideValue = ui.text("", 16f, Palette.TEXT, Ui.Weight.MEDIUM)
    private val rideTop = ui.text("", 13f, Palette.MUTED)
    private val rideCard: LinearLayout

    private val sendingValue = ui.text("", 13f, Palette.MUTED)
    private val sourceValue = ui.text("", 13f, Palette.TEXT_SOFT)
    private val modelValue = ui.text("", 13f, Palette.TEXT_SOFT)
    private val linkButton: Button = ui.button("Connect") { toggleLink() }

    private val navListener: (NavSnapshot?) -> Unit = { refresh() }
    private val stateListener: () -> Unit = { view.post { refresh() } }

    override val view: View

    init {
        val settingsButton = LinearLayout(ui.ctx).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Open Settings"
            background = ui.pressable(Palette.SURFACE, 22, Palette.BORDER)
            addView(ui.icon(R.drawable.ic_tab_settings, 20, Palette.TEXT_SOFT), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
            setOnClickListener { host.showTab(MainActivity.Tab.SETTINGS) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.title("Zuki Nav"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(10) })
            addView(settingsButton, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        }

        rideCard = ui.card(
            ui.text("CURRENT RIDE", 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.08f },
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(rideValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(rideTop)
            },
            gapDp = 6,
        ).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { host.showTab(MainActivity.Tab.TRIPS) }
        }

        val statusCard = ui.listCard(
            ui.row("Sending To Dash", trailing = sendingValue),
            ui.row("Arrow Source", trailing = sourceValue),
            ui.row("Bike Model", trailing = modelValue),
            ui.row("Dash Icon", trailing = iconValue),
        )

        view = host.page(header, panel.view, rideCard, statusCard, linkButton)
    }

    override fun onShow() {
        NavRepository.addListener(navListener)
        DashService.addStateListener(stateListener)
        refresh()
        view.postDelayed(clockTick, 30_000)
    }

    override fun onHide() {
        NavRepository.removeListener(navListener)
        DashService.removeStateListener(stateListener)
        view.removeCallbacks(clockTick)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            refresh()
            view.postDelayed(this, 30_000)
        }
    }

    private fun refresh() {
        val name = prefs.clusterName
        val model = ClusterModels.fromBleName(name)
        val state = DashService.linkState
        val running = DashService.instance != null

        statusSlot.removeAllViews()
        statusSlot.addView(
            when (state) {
                ClusterLink.State.READY -> ui.pill("Connected", Palette.OK, Palette.OK_BG)
                ClusterLink.State.CONNECTING -> ui.pill("Waiting", Palette.WARN, Palette.WARN_BG)
                ClusterLink.State.DISCONNECTED -> when {
                    running && DashService.bluetoothOff -> ui.pill("Bluetooth Off", Palette.WARN, Palette.WARN_BG)
                    else -> ui.pill(if (running) "Not Connected" else "Stopped", Palette.MUTED, Palette.INSET)
                }
            },
        )

        val nav = NavRepository.current
        iconValue.text = nav?.icon?.let { "#${model.adjustIcon(it.id)}" } ?: "-"
        val live = DashService.liveBike
        val data = live ?: prefs.lastBike
        panel.render(
            name = if (name == null) null else BikeNames.name(model.displayName, prefs.bikeVariant),
            nav = nav,
            data = data,
            live = live != null,
            clockText = formatTime(LocalTime.now()),
            etaText = nav?.eta?.let { formatTime(it) },
            syncNote = when {
                live != null -> null
                data != null -> "Last synced ${formatWhen(prefs.lastBikeAt)}"
                name != null -> "Turn the ignition on once to see fuel, ODO and trip meters here."
                else -> "Pair your bike in Settings to get started."
            },
        )

        sendingValue.text = when {
            state != ClusterLink.State.READY -> "Not Connected"
            nav != null -> "Every 200 ms"
            else -> "Clock Only"
        }
        sendingValue.setTextColor(if (state == ClusterLink.State.READY) Palette.OK else Palette.MUTED)
        sourceValue.text = when (nav?.iconSource) {
            "maps-icon" -> "Maps Arrow"
            "text" -> "Instruction Text"
            "previous" -> "Previous Turn"
            null -> "-"
            else -> "None"
        }
        modelValue.text = if (name == null) "-" else model.displayName

        val ride = if (prefs.recordTrips) TripLog.active(host) else null
        rideCard.visibility = if (ride == null) View.GONE else View.VISIBLE
        if (ride != null) {
            rideValue.text = TripFormat.distance(ride.distanceKm) + " · " +
                TripFormat.duration((ride.lastAt - ride.start) / 1000)
            rideTop.text = "Top ${ride.topSpeed} km/h"
        }

        if (running) {
            linkButton.text = "Disconnect"
            linkButton.background = ui.pressable(Palette.DANGER_BG, 16, Palette.DANGER_BORDER)
            linkButton.setTextColor(Palette.DANGER)
        } else {
            linkButton.text = "Connect"
            linkButton.background = ui.pressable(Palette.ACCENT, 16)
            linkButton.setTextColor(android.graphics.Color.WHITE)
        }
    }

    private fun formatWhen(ms: Long): String {
        if (ms <= 0) return "-"
        val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val time = formatTime(t.toLocalTime())
        return when (t.toLocalDate()) {
            today -> "today, $time"
            today.minusDays(1) -> "yesterday, $time"
            else -> t.format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) + ", $time"
        }
    }

    private fun toggleLink() {
        if (DashService.instance != null) DashService.stop(host) else host.startLink()
        view.postDelayed({ refresh() }, 400)
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(Locale.US, "%.1f km", m / 1000)

    private fun formatTime(t: LocalTime): String =
        t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(host)) "HH:mm" else "h:mm a", Locale.US))
}
