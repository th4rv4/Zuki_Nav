package com.th4rv4.zukinav.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.protocol.EngineState
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.thermal.Thermals
import com.th4rv4.zukinav.trips.Trip
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripStore
import java.util.Locale

/**
 * Thermals: the live engine temperature, how it has moved over the last 30
 * minutes, the hottest point of recent trips, and the rider's engine limits
 * and dash alerts.
 */
class ThermalsScreen(host: MainActivity) : Screen(host) {

    private val gauge = ThermalGaugeView(ui.ctx)
    private val stateChip = ui.text("", 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.08f }
    private val warmUpNote = ui.text("", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER }
    private val graph = TempGraphView(ui.ctx)
    private val maxText = number()
    private val avgText = number()
    private val hotText = number()
    private val tripBars = TripHeatBarsView(ui.ctx)
    private val tripsCard: LinearLayout
    private var barTrips: List<Trip> = emptyList()
    private val noReading = ui.text(
        "Your bike's dash doesn't send an engine temperature, or none has come in yet. Turn the ignition on with the app connected.",
        13f, Palette.MUTED,
    )

    private val changed: () -> Unit = { render() }

    override val view: View

    init {
        val chipRow = LinearLayout(ui.ctx).apply {
            gravity = Gravity.CENTER
            addView(stateChip)
        }
        val gaugeCard = ui.card(gauge, chipRow, warmUpNote, gapDp = 6, padDp = 14)
        gauge.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(200))

        val monitor = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("Temp Monitor", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("Last 30 min", 11f, Palette.MUTED))
            },
            graph,
            ui.hstack(0, stat(maxText, "Max °C"), stat(avgText, "Avg °C"), stat(hotText, "Above Hot"), weighted = true),
            gapDp = 10,
        )
        graph.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(110))

        tripsCard = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("Recent Trips", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("Max engine temp", 11f, Palette.MUTED))
            },
            tripBars,
            ui.text("Tap a bar to open that trip", 11f, Palette.MUTED),
            gapDp = 10,
        )
        tripBars.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(80))
        tripBars.onPick = { i -> barTrips.getOrNull(i)?.let { host.showTrip(it.id) } }

        view = host.page(
            ui.vstack(2, ui.title("Thermals"), ui.text("Engine temperature from the dash", 13f, Palette.MUTED))
                .apply { setPadding(ui.dp(4), 0, 0, 0) },
            noReading,
            gaugeCard,
            monitor,
            tripsCard,
            ui.sectionHeader(R.drawable.ic_engine_temp, "Thermal Settings"),
            settingsCard(),
            ui.text(
                "Dash alerts appear on the dash's call screen for the time you pick, then close by themselves. " +
                    "Warmed Up is skipped during a route; Engine Overheat still shows, for 5 s at most so the arrows aren't hidden for long.",
                12f, Palette.MUTED,
            ).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) },
        )
    }

    override fun onShow() {
        Thermals.addListener(changed)
        render()
    }

    override fun onHide() {
        Thermals.removeListener(changed)
    }

    private fun render() {
        val s = Thermals.session
        val live = DashService.liveBike?.engineTempC
        val known = live ?: prefs.lastBike?.engineTempC
        noReading.visibility = if (known == null && s.lastTempC == null) View.VISIBLE else View.GONE

        val state = live?.let { EngineState.of(it, prefs.engineWarmC, prefs.engineHotC) }
        val color = colorFor(state)
        gauge.set(live, color)
        when {
            live == null -> chip("Bike Not Connected", Palette.MUTED)
            state != null -> chip(state.label, color)
            else -> stateChip.visibility = View.GONE
        }
        warmUpNote.text = s.warmUpMs?.let { "Warmed up in ${TripFormat.shortDuration(it / 1000)}" }.orEmpty()
        warmUpNote.visibility = if (live != null && s.warmUpMs != null) View.VISIBLE else View.GONE

        graph.set(s.samples, prefs.engineWarmC, prefs.engineHotC)
        maxText.text = s.maxC?.toString() ?: "--"
        avgText.text = s.averageC?.toString() ?: "--"
        hotText.text = if (prefs.engineHotC > 0) TripFormat.shortDuration(s.aboveHotMs / 1000) else "--"

        barTrips = TripStore.all(host).filter { it.maxEngineC != null }.take(8).reversed()
        tripsCard.visibility = if (barTrips.isEmpty()) View.GONE else View.VISIBLE
        tripBars.set(barTrips.map { it.maxEngineC!! }) { colorFor(EngineState.of(it, prefs.engineWarmC, prefs.engineHotC)) }
    }

    private fun chip(text: String, color: Int) {
        stateChip.visibility = View.VISIBLE
        stateChip.text = text.uppercase(Locale.US)
        stateChip.setTextColor(color)
        stateChip.background = ui.rounded(Accents.withAlpha(color, 0x26), 9)
        stateChip.setPadding(ui.dp(10), ui.dp(3), ui.dp(10), ui.dp(3))
    }

    private fun colorFor(state: EngineState?): Int = when (state) {
        EngineState.HOT -> Palette.DANGER
        EngineState.NORMAL -> Palette.OK
        EngineState.WARMING_UP -> EngineHeatView.COLD
        null -> Palette.TEXT
    }

    private fun settingsCard(): LinearLayout {
        fun limitInput(value: Int, save: (Int) -> Unit) =
            ui.input(value.takeIf { it > 0 }?.toString() ?: "", "Off", numeric = true) {
                save(it.toIntOrNull()?.coerceIn(0, 254) ?: 0)
                render()
            }
        val warmTime = ui.durationRow(prefs.warmedUpAlertSeconds) { prefs.warmedUpAlertSeconds = it }
            .apply { visibility = shownIf(prefs.warmedUpDashAlert) }
        val hotTime = ui.durationRow(prefs.overheatAlertSeconds) { prefs.overheatAlertSeconds = it }
            .apply { visibility = shownIf(prefs.overheatDashAlert) }
        val testRow = ui.hstack(
            8,
            ui.button("Test Warmed Up", Ui.Btn.SECONDARY, 44) { test(overheat = false) },
            ui.button("Test Overheat", Ui.Btn.SECONDARY, 44) { test(overheat = true) },
            weighted = true,
        )
        return ui.listCard(
            ui.row("Engine Warm-Up Temp", "°C. Below this: Warming Up. Empty to turn off",
                limitInput(prefs.engineWarmC) { prefs.engineWarmC = it }),
            ui.row("Engine Heat Warning", "°C. At or above this: Hot, with a warning on the Ride tab. Empty to turn off",
                limitInput(prefs.engineHotC) { prefs.engineHotC = it }),
            ui.switchRow("Warmed Up Alert On Dash", "Shows WARMED UP on the dash once per ride when the engine reaches your warm-up temp",
                prefs.warmedUpDashAlert) { prefs.warmedUpDashAlert = it; warmTime.visibility = shownIf(it) },
            warmTime,
            ui.switchRow("Overheat Alert On Dash", "Shows ENGINE OVERHEAT on the dash when the engine reaches your heat warning, then every 5 min while hot",
                prefs.overheatDashAlert) { prefs.overheatDashAlert = it; hotTime.visibility = shownIf(it) },
            hotTime,
            testRow.apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) },
        )
    }

    private fun shownIf(on: Boolean) = if (on) View.VISIBLE else View.GONE

    private fun test(overheat: Boolean) {
        val sent = DashService.instance?.testThermalAlert(overheat) ?: false
        if (!sent) host.toast("Connect the bike first: turn the ignition on with the app connected")
    }

    private fun number() = TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = 19f
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
    }

    private fun stat(value: TextView, label: String): LinearLayout =
        ui.vstack(
            0, value,
            ui.text(label.uppercase(Locale.US), 10f, Palette.MUTED, Ui.Weight.BOLD).apply {
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
            },
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
}
