package com.th4rv4.zukinav.ui

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.nav.NavSnapshot
import com.th4rv4.zukinav.protocol.BikeData
import java.util.Locale

/**
 * The live panel at the top of the Ride tab: the clock, the next turn with
 * its distance ring, the speedometer, fuel, ETA and the trip meters.
 */
class DashPanel(private val host: MainActivity) {

    private val ui = host.ui

    private val clock = numberText(15f, Palette.MUTED)

    private val turn = TurnRingView(ui.ctx)
    private val distance = numberText(24f, Palette.TEXT)
    private val instruction = ui.text("", 13f, Palette.TEXT_SOFT).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }
    private val speed = SpeedGaugeView(ui.ctx)

    private val fuelIcon = ImageView(ui.ctx)
    private val fuel = FuelCellsView(ui.ctx)
    private val fuelRow: LinearLayout

    private val eta = numberText(16f, Palette.TEXT)
    private val remaining = numberText(16f, Palette.TEXT)
    private val etaRow: LinearLayout

    private val odo = numberText(19f, Palette.TEXT)
    private val tripA = numberText(19f, Palette.TEXT)
    private val tripB = numberText(19f, Palette.TEXT)
    private val sync = ui.text("", 12f, Palette.MUTED)

    /** Soft colour glow in the corner; its colour follows the speed. */
    private val aura = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        shape = GradientDrawable.OVAL
    }

    val view: View

    init {
        val content = ui.vstack(10)
        content.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))

        content.addView(clock.apply { gravity = Gravity.END })

        val turnColumn = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(turn, LinearLayout.LayoutParams(ui.dp(112), ui.dp(112)))
            addView(distance)
        }
        content.addView(LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(turnColumn, LinearLayout.LayoutParams(ui.dp(124), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(speed, LinearLayout.LayoutParams(0, ui.dp(172), 1f))
        })
        content.addView(instruction)

        fuelRow = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4))
            addView(fuelIcon, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(10) })
            addView(fuel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(fuelRow)

        etaRow = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            addView(label("ETA"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
            addView(eta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(remaining, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(6) })
            addView(label("LEFT"))
        }
        content.addView(etaRow)

        content.addView(ui.hstack(6, chip("ODO", odo), chip("TRIP A", tripA), chip("TRIP B", tripB), weighted = true))
        content.addView(sync)

        val frame = FrameLayout(ui.ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Palette.PANEL_TOP, Palette.PANEL_BOTTOM),
            ).apply {
                cornerRadius = ui.dp(22).toFloat()
                setStroke(ui.dp(1), Palette.GLASS_BORDER)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val auraSize = ui.dp(260)
        aura.gradientRadius = auraSize / 2f
        frame.addView(View(ui.ctx).apply { background = aura }, FrameLayout.LayoutParams(auraSize, auraSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = -ui.dp(60)
            marginEnd = -ui.dp(70)
        })
        frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view = frame
    }

    /**
     * [live] is true while readings come from the dash; [data] may be the
     * last saved reading. [syncNote] explains where the numbers come from.
     */
    fun render(nav: NavSnapshot?, data: BikeData?, live: Boolean, clockText: String, etaText: String?, syncNote: String?) {
        clock.text = clockText

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

        speed.setSpeed(if (live) data?.speedKmh else null)
        val kmh = if (live) data?.speedKmh ?: 0 else 0
        val glowColor = when {
            kmh > 90 -> Accents.withAlpha(Palette.ACCENT_END, 0x61)
            kmh > 45 -> Accents.withAlpha(Palette.ACCENT_MID, 0x59)
            else -> Accents.withAlpha(Palette.ACCENT, 0x59)
        }
        aura.colors = intArrayOf(glowColor, 0x00000000)

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
                "Fuel $filled of ${BikeData.MAX_FUEL_BARS} bars"
        }

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

    private fun numberText(sizeSp: Float, color: Int) = TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = sizeSp
        setTextColor(color)
    }

    private fun label(s: String) = ui.text(s, 10f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.14f }

    private fun glass() = ui.rounded(Palette.GLASS, 14, Palette.GLASS_BORDER)

    private fun chip(title: String, value: TextView): LinearLayout =
        ui.vstack(2, label(title), value).apply {
            background = glass()
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
        }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(Locale.US, "%.1f km", m / 1000)
}
