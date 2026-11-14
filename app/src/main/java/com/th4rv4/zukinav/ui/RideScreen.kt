package com.th4rv4.zukinav.ui

import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.ble.ClusterLink
import com.th4rv4.zukinav.fuel.FuelStore
import com.th4rv4.zukinav.protocol.EngineState
import com.th4rv4.zukinav.nav.NavRepository
import com.th4rv4.zukinav.nav.NavSnapshot
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripLog
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home tab: the bike, live guidance and what is being sent to the dash. */
class RideScreen(host: MainActivity) : Screen(host) {

    private val panel = DashPanel(host)
    private val statusSlot = FrameLayout(host.ui.ctx)
    private val dateLine = ui.text("", 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.1f }

    private val rideValue = ui.text("", 16f, Palette.TEXT, Ui.Weight.MEDIUM)
    private val rideTop = ui.text("", 13f, Palette.MUTED)
    private val rideCard: LinearLayout

    private val sendingValue = ui.text("", 13f, Palette.MUTED)
    private val sourceValue = ui.text("", 13f, Palette.TEXT_SOFT)
    private val avatar = AvatarView(host.ui.ctx)
    private val linkButton: Button = ui.button("Connect") { toggleLink() }

    private val navListener: (NavSnapshot?) -> Unit = { refresh() }
    private val stateListener: () -> Unit = { view.post { refresh() } }
    private val fuelListener: () -> Unit = { refresh() }

    private val engineHotTitle = ui.text("", 14f, Palette.DANGER, Ui.Weight.BOLD)
    private val engineHotCard = ui.card(
        engineHotTitle,
        ui.text("At or above your Engine Heat Warning. Let it cool if you can.", 12f, Palette.TEXT_SOFT),
        gapDp = 2,
        padDp = 14,
    ).apply { background = ui.rounded(Palette.DANGER_BG, 20, Palette.DANGER_BORDER) }

    private val lowRangeTitle = ui.text("", 14f, Palette.WARN, Ui.Weight.BOLD)
    private val lowRangeCard = ui.card(
        lowRangeTitle,
        ui.text("Below your Low Range Warning. Tap for Fuel.", 12f, Palette.TEXT_SOFT),
        gapDp = 2,
        padDp = 14,
    ).apply {
        background = ui.pressable(Palette.WARN_BG, 20, Palette.WARN_BORDER)
        isClickable = true
        isFocusable = true
        setOnClickListener { host.showFuel() }
    }

    override val view: View

    init {
        avatar.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { AvatarSheet(host, onPhoto = { host.pickAvatarPhoto() }, onSaved = { refresh() }).show() }
        }
        val titleRow = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.title("Ride"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(10) })
            addView(statusSlot)
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), 0, 0, 0)
            addView(ui.vstack(2, dateLine, titleRow), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(avatar, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
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
        )

        view = host.page(header, engineHotCard, lowRangeCard, panel.view, panel.chips, rideCard, statusCard, linkButton)
    }

    override fun onShow() {
        FuelStore.addListener(fuelListener)
        NavRepository.addListener(navListener)
        DashService.addStateListener(stateListener)
        refresh()
        view.postDelayed(clockTick, 30_000)
    }

    override fun onHide() {
        NavRepository.removeListener(navListener)
        FuelStore.removeListener(fuelListener)
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
        val state = DashService.linkState
        val running = DashService.instance != null

        statusSlot.removeAllViews()
        statusSlot.addView(
            when (state) {
                ClusterLink.State.READY -> ui.pill("Connected", Palette.OK, Palette.OK_BG)
                ClusterLink.State.CONNECTING -> ui.pill("Waiting", Palette.WARN, Palette.WARN_BG)
                ClusterLink.State.DISCONNECTED -> when {
                    running && DashService.bluetoothOff -> ui.pill("Bluetooth Off", Palette.WARN, Palette.WARN_BG)
                    else -> ui.pill(if (running) "Searching" else "Stopped", Palette.MUTED, Palette.INSET)
                }
            },
        )

        avatar.show(prefs.avatar, prefs.riderName)
        val nav = NavRepository.current
        dateLine.text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE", Locale.US)).uppercase(Locale.US) +
            " · " + formatTime(LocalTime.now())
        val live = DashService.liveBike
        val data = live ?: prefs.lastBike
        val range = data?.takeIf { it.fuelBars != null }?.let { FuelStore.range(host, it.odoKm) }
        // Only from a live reading: an old one from a parked bike is no reason to warn.
        val engine = live?.engineTempC
        val hot = engine != null && EngineState.of(engine, prefs.engineWarmC, prefs.engineHotC) == EngineState.HOT
        engineHotCard.visibility = if (hot) View.VISIBLE else View.GONE
        engineHotTitle.text = "Engine Hot · ${engine ?: 0} °C"
        val low = prefs.lowRangeKm
        lowRangeCard.visibility = if (range != null && low > 0 && range.km < low) View.VISIBLE else View.GONE
        lowRangeTitle.text = "Low Range · ≈ ${range?.km ?: 0} km"
        panel.render(
            nav = nav,
            data = data,
            live = live != null,
            etaText = nav?.eta?.let { formatTime(it) },
            range = range,
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

        val ride = if (prefs.recordTrips) TripLog.active(host) else null
        rideCard.visibility = if (ride == null) View.GONE else View.VISIBLE
        if (ride != null) {
            rideValue.text = TripFormat.distance(ride.distanceKm) + " · " +
                TripFormat.duration((ride.lastAt - ride.start) / 1000)
            rideTop.text = "Top ${ride.topSpeed} km/h"
        }

        // Follows the actual link: while the bike is away the app keeps looking
        // for it in the background, and the button stops that instead.
        when {
            state == ClusterLink.State.READY -> {
                linkButton.text = "Disconnect"
                linkButton.background = ui.accentPressable(16)
                linkButton.setTextColor(Palette.ON_ACCENT)
            }
            running -> {
                linkButton.text = "Stop Searching For Bike"
                linkButton.background = ui.pressable(Palette.SURFACE, 16, Palette.BORDER)
                linkButton.setTextColor(Palette.TEXT)
            }
            else -> {
                linkButton.text = "Connect"
                linkButton.background = ui.accentPressable(16)
                linkButton.setTextColor(Palette.ON_ACCENT)
            }
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
