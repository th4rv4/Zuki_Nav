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
import com.th4rv4.zukinav.trips.TripStore
import com.th4rv4.zukinav.maint.MaintStore
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

    private val avatar = AvatarView(host.ui.ctx)
    private val linkButton: Button = ui.button("Connect") { toggleLink() }

    private val navListener: (NavSnapshot?) -> Unit = { refresh() }
    private val stateListener: () -> Unit = { view.post { refresh() } }
    private val fuelListener: () -> Unit = { refresh() }
    private val monthCard = MonthCard(host)
    private val monthListener: () -> Unit = { reloadMonth() }
    private val parkedListener: () -> Unit = { refresh() }

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

    private val bondedCard = ui.card(
        ui.text("Bike Paired In Android Settings", 14f, Palette.WARN, Ui.Weight.BOLD),
        ui.text(
            "The dash won't let Zuki Nav connect while the bike is paired in your phone's Bluetooth settings. " +
                "Tap here, open the bike there and tap Forget (or Unpair).",
            12f, Palette.TEXT_SOFT,
        ),
        gapDp = 2,
        padDp = 14,
    ).apply {
        background = ui.pressable(Palette.WARN_BG, 20, Palette.WARN_BORDER)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            try {
                host.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
            }
        }
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


        view = host.page(header, bondedCard, engineHotCard, lowRangeCard, panel.view, panel.chips, rideCard, monthCard.view, linkButton)
        // The month card only fills spare room: re-checked whenever the page is laid out.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitMonthCard() }
    }

    override fun onShow() {
        FuelStore.addListener(fuelListener)
        NavRepository.addListener(navListener)
        DashService.addStateListener(stateListener)
        TripStore.addListener(monthListener)
        com.th4rv4.zukinav.trips.Parking.addListener(parkedListener)
        MaintStore.addListener(monthListener)
        reloadMonth()
        refresh()
        view.postDelayed(clockTick, 30_000)
    }

    override fun onHide() {
        NavRepository.removeListener(navListener)
        FuelStore.removeListener(fuelListener)
        DashService.removeStateListener(stateListener)
        TripStore.removeListener(monthListener)
        com.th4rv4.zukinav.trips.Parking.removeListener(parkedListener)
        MaintStore.removeListener(monthListener)
        view.removeCallbacks(clockTick)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            reloadMonth()
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
        bondedCard.visibility = if (DashService.isBikeBonded(host)) View.VISIBLE else View.GONE
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
                live != null || data != null -> null
                name != null -> "Turn the ignition on once to see fuel, ODO and trip meters here."
                else -> "Pair your bike in Settings to get started."
            },
            // Hidden while live, and during a route so turn instructions get the full width.
            syncedAt = if (live == null && data != null && nav == null) formatWhen(prefs.lastBikeAt) else null,
            parked = if (live == null && nav == null) com.th4rv4.zukinav.trips.Parking.get(host) else null,
        )

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

    private fun reloadMonth() {
        monthCard.reload()
        fitMonthCard()
    }

    /**
     * Shows as much of the month card as fits above the tab bar without making
     * the page scroll: the reminder line goes first, then the whole card.
     */
    private fun fitMonthCard() {
        val page = view as? ViewGroup ?: return
        val column = page.getChildAt(0) as? LinearLayout ?: return
        if (page.height == 0 || linkButton.bottom == 0) return
        val card = monthCard.view
        val row = monthCard.reminderRow
        val gap = ui.dp(14)
        // Page height without the card in it.
        val shown = card.visibility == View.VISIBLE
        val without: Int = linkButton.bottom + column.paddingBottom - (if (shown) card.height + gap else 0)
        val room: Int = page.height - without - gap
        // Measuring alone never asks for a new layout, so this can't loop.
        val inner = column.width - column.paddingLeft - column.paddingRight
        val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        card.measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY), any)
        row.measure(View.MeasureSpec.makeMeasureSpec(inner - card.paddingLeft - card.paddingRight, View.MeasureSpec.EXACTLY), any)
        val lineH = row.measuredHeight + ui.dp(10)
        val withoutLine: Int = card.measuredHeight - (if (row.visibility == View.VISIBLE) lineH else 0)
        fun heightWith(line: Boolean): Int = withoutLine + (if (line) lineH else 0)
        val wantLine = monthCard.hasReminder && heightWith(true) <= room
        val wantCard = wantLine || heightWith(false) <= room
        val lineVis = if (wantLine) View.VISIBLE else View.GONE
        val cardVis = if (wantCard) View.VISIBLE else View.GONE
        // Only touch what changed, so this settles after one more layout.
        if (row.visibility == lineVis && card.visibility == cardVis) return
        // Changed after this layout pass, not during it: a change made inside
        // the pass can be dropped, leaving a blank gap where the card was.
        view.post {
            row.visibility = lineVis
            card.visibility = cardVis
            view.requestLayout()
        }
    }

    /** Two short lines, the day then the time: "Today" / "3:22 PM". */
    private fun formatWhen(ms: Long): String? {
        if (ms <= 0) return null
        val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val time = formatTime(t.toLocalTime())
        val day = when (t.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> t.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
        }
        return "$day\n$time"
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
