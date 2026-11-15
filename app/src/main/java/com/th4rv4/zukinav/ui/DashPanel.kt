package com.th4rv4.zukinav.ui

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.fuel.Range
import com.th4rv4.zukinav.nav.NavSnapshot
import com.th4rv4.zukinav.protocol.BikeData
import com.th4rv4.zukinav.protocol.EngineState
import java.util.Locale

/**
 * The live card at the top of the Ride tab: the next turn with its distance
 * ring, the speedometer, fuel and ETA. The trip meters sit below it as three
 * small cards ([chips]).
 */
class DashPanel(private val host: MainActivity) {

    private val ui = host.ui
    private val prefs = host.prefs

    private val turn = TurnRingView(ui.ctx)
    /** Shown in the turn ring's place when there is a parked spot and no route. */
    private val parkIcon = ui.iconTile(R.drawable.ic_parking, 64, 28, Palette.ACCENT_SOFT, Palette.ACCENT_BG, 32).apply {
        visibility = View.GONE
    }
    private lateinit var topRow: LinearLayout
    private val distance = numberText(26f, Palette.TEXT)
    private val instruction = ui.text("", 13f, Palette.MUTED).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val speed = SpeedGaugeView(ui.ctx)

    private val fuelIcon = ImageView(ui.ctx)
    private val fuel = FuelCellsView(ui.ctx)
    private val rangeText = numberText(17f, Palette.TEXT)
    private val rangeUnit = label("KM")
    private val fuelRow: LinearLayout

    private val engineIcon = ImageView(ui.ctx).apply { setImageResource(R.drawable.ic_engine_temp) }
    private val engineBar = EngineHeatView(ui.ctx)
    private val engineText = numberText(17f, Palette.TEXT)
    private val engineState = ui.text("", 10f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.08f }
    private val engineBlock: LinearLayout

    private val eta = numberText(17f, Palette.TEXT)
    private val remaining = numberText(17f, Palette.TEXT)
    private val etaRow: LinearLayout

    private val odo = numberText(19f, Palette.TEXT)
    private val tripA = numberText(19f, Palette.TEXT)
    private val tripB = numberText(19f, Palette.TEXT)
    private val sync = ui.text("", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER }
    /** When the numbers were last read, in the card's top right corner. */
    private val syncedAt = ui.text("", 11f, Palette.MUTED).apply {
        gravity = Gravity.END
        setLineSpacing(0f, 1.1f)
    }

    val view: View

    /** ODO, Trip A and Trip B, plus a setup hint under them before the first reading. */
    val chips: View

    init {
        val content = ui.vstack(12)
        content.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))

        topRow = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.FrameLayout(ui.ctx).apply {
                addView(turn, android.widget.FrameLayout.LayoutParams(ui.dp(64), ui.dp(64)))
                addView(parkIcon, android.widget.FrameLayout.LayoutParams(ui.dp(64), ui.dp(64)))
            }, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { marginEnd = ui.dp(12) })
            addView(ui.vstack(2, distance, instruction), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(syncedAt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
                marginStart = ui.dp(8)
            })
        }
        content.addView(topRow)
        content.addView(View(ui.ctx).apply { setBackgroundColor(Palette.BORDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)))
        content.addView(speed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(190)))

        fuelRow = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(fuelIcon, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(10) })
            addView(fuel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(rangeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(12) })
            addView(rangeUnit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(4) })
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Palette.RIPPLE), null, ui.rounded(android.graphics.Color.WHITE, 10),
            )
            setOnClickListener { host.showFuel() }
        }
        content.addView(fuelRow)

        val engineRow = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(engineIcon, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(10) })
            addView(engineBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(engineText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(12) })
            addView(label("°C"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(4) })
        }
        engineBlock = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(engineRow)
            addView(engineState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = ui.dp(6)
            })
        }
        content.addView(engineBlock)

        etaRow = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("ETA"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
            addView(eta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(remaining, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(6) })
            addView(label("LEFT"))
        }
        content.addView(etaRow)

        content.background = ui.rounded(Palette.SURFACE, 22, Palette.BORDER)
        view = content

        chips = ui.vstack(
            10,
            ui.hstack(8, chip("ODO", odo), chip("TRIP A", tripA), chip("TRIP B", tripB), weighted = true),
            sync,
        )
    }

    /**
     * [live] is true while readings come from the dash; [data] may be the
     * last saved reading. [syncNote] is a setup hint under the trip meters;
     * [syncedAt] is the short "when" shown in the top right corner.
     */
    fun render(
        nav: NavSnapshot?, data: BikeData?, live: Boolean, etaText: String?, syncNote: String?, syncedAt: String?, range: Range?,
        parked: com.th4rv4.zukinav.trips.Parked? = null,
    ) {
        this.syncedAt.text = syncedAt.orEmpty()
        this.syncedAt.visibility = if (syncedAt.isNullOrBlank()) View.GONE else View.VISIBLE
        this.syncedAt.contentDescription = syncedAt?.let { "Last synced " + it.replace('\n', ' ') }

        val art = nav?.let { TurnArt.of(it.icon) }
        turn.set(art, nav?.metersToTurn)
        turn.contentDescription = nav?.icon?.label ?: "No route"
        distance.text = nav?.metersToTurn?.let { formatDistance(it) } ?: "--"
        distance.setTextColor(if (nav == null) Palette.MUTED else Palette.TEXT)
        val text = when {
            nav == null -> "Start a route in Google Maps"
            !nav.instruction.isNullOrBlank() -> nav.instruction
            else -> nav.icon?.label ?: "Follow the route"
        }
        instruction.text = text
        instruction.setTextColor(Palette.MUTED)
        renderParked(if (nav == null) parked else null)

        speed.setSpeed(if (live) data?.speedKmh else null)

        if (data == null) {
            fuelRow.visibility = View.GONE
        } else {
            fuelRow.visibility = View.VISIBLE
            val battery = data.batteryPercent
            val filled = if (battery != null) (battery * BikeData.MAX_FUEL_BARS + 50) / 100 else data.fuelBars ?: 0
            fuel.set(filled, BikeData.MAX_FUEL_BARS)
            fuelIcon.setImageResource(if (battery != null) R.drawable.ic_battery else R.drawable.ic_fuel)
            fuelIcon.setColorFilter(fuel.levelColor)
            fuelRow.contentDescription = if (battery != null) "Battery $battery%" else
                "Fuel $filled of ${BikeData.MAX_FUEL_BARS} bars" + (range?.let { ", about ${it.km} km left. Opens Fuel." } ?: "")
            val showRange = battery == null && range != null
            rangeText.visibility = if (showRange) View.VISIBLE else View.GONE
            rangeUnit.visibility = rangeText.visibility
            rangeText.text = range?.let { "≈ ${it.km}" } ?: ""
            fuelRow.isClickable = battery == null
        }

        renderEngine(data?.engineTempC, live)

        etaRow.visibility = if (nav == null) View.GONE else View.VISIBLE
        eta.text = etaText ?: "--"
        remaining.text = nav?.remainingMeters?.let { formatDistance(it) } ?: "--"

        odo.text = data?.let { String.format(Locale.US, "%,d", it.odoKm) } ?: "--"
        tripA.text = data?.let { String.format(Locale.US, "%.1f", it.tripAKm) } ?: "--"
        tripB.text = data?.let { String.format(Locale.US, "%.1f", it.tripBKm) } ?: "--"
        sync.text = syncNote.orEmpty()
        sync.visibility = if (syncNote.isNullOrBlank()) View.GONE else View.VISIBLE
        sync.setTextColor(if (live) Palette.OK else Palette.MUTED)
    }

    /** Parked Spot takes over the idle top row: no extra room on the page. */
    private fun renderParked(p: com.th4rv4.zukinav.trips.Parked?) {
        turn.visibility = if (p == null) View.VISIBLE else View.INVISIBLE
        parkIcon.visibility = if (p == null) View.GONE else View.VISIBLE
        distance.typeface = if (p == null) DashFonts.numbers else android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        distance.textSize = if (p == null) 26f else 17f
        distance.maxLines = 1
        distance.ellipsize = TextUtils.TruncateAt.END
        if (p == null) {
            topRow.isClickable = false
            topRow.background = null
            topRow.contentDescription = null
            return
        }
        distance.text = "Parked" + (p.place?.let { " · $it" } ?: "")
        distance.setTextColor(Palette.TEXT)
        instruction.text = "Open In Maps"
        instruction.setTextColor(Palette.ACCENT_SOFT)
        topRow.isClickable = true
        topRow.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Palette.RIPPLE), null, ui.rounded(android.graphics.Color.WHITE, 12),
        )
        topRow.contentDescription = "Parked" + (p.place?.let { " near $it" } ?: "") + ". Opens walking directions in Google Maps."
        topRow.setOnClickListener { openParked(p) }
    }

    private fun openParked(p: com.th4rv4.zukinav.trips.Parked) {
        val walk = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("google.navigation:q=${p.lat},${p.lng}&mode=w"))
            .setPackage("com.google.android.apps.maps")
        val any = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}(Parked bike)"))
        try {
            host.startActivity(walk)
        } catch (_: Exception) {
            try { host.startActivity(any) } catch (_: Exception) { host.toast("No maps app found") }
        }
    }

    /**
     * Engine temperature row; hidden on bikes whose dash sends none. Engine
     * heat changes quickly, so without a live link it says Bike Not Connected
     * instead of showing an old reading. Labels follow the rider's limits.
     */
    private fun renderEngine(reading: Int?, live: Boolean) {
        engineBlock.visibility = if (reading == null) View.GONE else View.VISIBLE
        if (reading == null) return
        if (!live) {
            engineBar.set(null)
            engineText.text = "--"
            engineText.setTextColor(Palette.MUTED)
            engineIcon.setColorFilter(Palette.MUTED)
            engineState.visibility = View.VISIBLE
            engineState.text = "BIKE NOT CONNECTED"
            engineState.setTextColor(Palette.MUTED)
            engineState.background = ui.rounded(Accents.withAlpha(Palette.MUTED, 0x26), 8)
            engineState.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
            engineBlock.contentDescription = "Engine temperature: bike not connected"
            return
        }
        val c = reading
        val state = EngineState.of(c, prefs.engineWarmC, prefs.engineHotC)
        engineBar.set(c)
        engineText.text = c.toString()
        val color = when (state) {
            EngineState.WARMING_UP -> EngineHeatView.COLD
            EngineState.NORMAL -> Palette.OK
            EngineState.HOT -> Palette.DANGER
            null -> Palette.TEXT
        }
        engineText.setTextColor(color)
        engineIcon.setColorFilter(if (state == null) Palette.MUTED else color)
        engineState.visibility = if (state == null) View.GONE else View.VISIBLE
        engineState.text = state?.label?.uppercase(Locale.US).orEmpty()
        engineState.setTextColor(color)
        engineState.background = ui.rounded(Accents.withAlpha(color, 0x24), 8)
        engineState.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        engineBlock.contentDescription = "Engine $c degrees Celsius" + (state?.let { ", ${it.label}" } ?: "")
    }

    private fun numberText(sizeSp: Float, color: Int) = TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = sizeSp
        setTextColor(color)
    }

    private fun label(s: String) = ui.text(s, 10f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.14f }

    /** A centred label and number on a small card. */
    private fun chip(title: String, value: TextView): LinearLayout {
        value.gravity = Gravity.CENTER
        return ui.vstack(2, label(title).apply { gravity = Gravity.CENTER }, value).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = ui.rounded(Palette.SURFACE, 16, Palette.BORDER)
            setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
        }
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(Locale.US, "%.1f km", m / 1000)
}
