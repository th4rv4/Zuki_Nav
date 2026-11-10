package com.th4rv4.zukinav.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.th4rv4.zukinav.fuel.FuelBook
import com.th4rv4.zukinav.fuel.FuelStore
import com.th4rv4.zukinav.fuel.Range
import com.th4rv4.zukinav.fuel.Refill
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.trips.TripFormat
import java.util.Locale

/** Number formats shared by the fuel screens. */
object FuelFormat {
    fun kmpl(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
    fun litres(v: Double) = String.format(Locale.US, if (v >= 10) "%.1f" else "%.2f", v).trimEnd('0').trimEnd('.')
    fun rupees(v: Double) = "₹" + String.format(Locale.US, "%,.0f", v)
    fun range(r: Range?) = r?.let { "≈ ${it.km} km" } ?: "--"
}

/**
 * Mileage and range: what is left in the tank, km/l per tank, km per bar as
 * learned so far, and every refill.
 */
class FuelScreen(host: MainActivity) : Screen(host) {

    private val subtitle = ui.text("", 13f, Palette.MUTED)
    private val rangeText = TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = 46f
        gravity = Gravity.CENTER
        setTextColor(Palette.TEXT)
    }
    private val rangeNote = ui.text("", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER }
    private val average = number(20f)
    private val lastTank = number(20f)
    private val cost = number(20f)
    private val chart = KmplBarsView(ui.ctx)
    private val chartCard: LinearLayout
    private val barCells = List(FuelBook.MAX_BARS) { number(16f) }
    private val refillList = ui.vstack(0)
    private val refillCard: LinearLayout
    private val empty = ui.text(
        "No refills yet. They show up when the fuel bars go up while the app is connected, or add one below.",
        13f, Palette.MUTED,
    ).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
    private val petrolOnly = ui.text("Fuel tracking is for petrol models. This bike reports battery charge instead.", 14f, Palette.MUTED)

    private val changed: () -> Unit = { render() }

    /** Hidden on electric models. */
    private val petrolViews = ArrayList<View>()

    override val view: View

    init {
        val back = ui.text("‹", 30f, Palette.TEXT).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setPadding(ui.dp(4), 0, ui.dp(14), ui.dp(4))
            setOnClickListener { host.closeFuel() }
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(ui.vstack(2, ui.text("Fuel", 22f, Palette.TEXT, Ui.Weight.BOLD), subtitle),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val hero = ui.card(cap("Range Left").apply { gravity = Gravity.CENTER }, rangeText, rangeNote, gapDp = 4, padDp = 18)
        val stats = ui.hstack(
            8,
            stat("Average", average, "km/l"),
            stat("Last Tank", lastTank, "km/l"),
            stat("Cost", cost, "per km"),
            weighted = true,
        )
        chartCard = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("km/l Per Tank", 14f, Palette.TEXT, Ui.Weight.MEDIUM),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("Full tank to full tank", 11f, Palette.MUTED))
            },
            chart,
            gapDp = 10,
        )
        // The card gives its children wrap-content height; the chart needs a fixed one.
        chart.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(80))

        val barsRow = LinearLayout(ui.ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until FuelBook.MAX_BARS) {
            val bar = FuelBook.MAX_BARS - i
            barsRow.addView(
                ui.vstack(2, barCells[i].apply { gravity = Gravity.CENTER }, cap("Bar $bar").apply { gravity = Gravity.CENTER })
                    .apply { gravity = Gravity.CENTER_HORIZONTAL },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        val barsCard = ui.card(
            ui.text("km Per Bar", 14f, Palette.TEXT, Ui.Weight.MEDIUM),
            barsRow,
            ui.text("Learned while the app is connected. \"--\" is still the estimate from the tank size; Bar 1 is the reserve.", 11f, Palette.MUTED),
            gapDp = 10,
        )
        val note = ui.card(
            ui.text("How Accurate Is This?", 14f, Palette.WARN, Ui.Weight.MEDIUM),
            ui.text(ACCURACY_NOTE, 12f, Palette.TEXT_SOFT),
            gapDp = 6,
            padDp = 14,
        ).apply { background = ui.rounded(Palette.WARN_BG, 20, Palette.WARN_BORDER) }

        refillCard = ui.listCard(refillList)
        view = host.page(
            header,
            petrolOnly,
            hero,
            stats,
            chartCard,
            barsCard,
            note,
            cap("Refills").apply { setPadding(ui.dp(4), ui.dp(6), 0, 0) },
            refillCard,
            empty,
            ui.button("Add Refill", Ui.Btn.SECONDARY, 48) { RefillSheet(host, null).show() },
        )
        listOf(hero, stats, chartCard, barsCard).forEach { petrolViews += it }
    }

    override fun onShow() {
        FuelStore.addListener(changed)
        render()
    }

    override fun onHide() {
        FuelStore.removeListener(changed)
    }

    private fun render() {
        val spec = FuelStore.spec(host)
        val model = ClusterModels.fromBleName(prefs.clusterName).displayName
        subtitle.text = if (spec == null) model else "$model · ${FuelFormat.litres(spec.tankLitres)} L tank"
        petrolOnly.visibility = if (spec == null) View.VISIBLE else View.GONE
        petrolViews.forEach { it.visibility = if (spec == null) View.GONE else View.VISIBLE }

        val state = FuelStore.state(host)
        val mileage = FuelBook.mileage(state.refills)
        val odo = DashService.liveBike?.odoKm ?: prefs.lastBike?.odoKm ?: state.lastOdoKm ?: 0
        val range = FuelStore.range(host, odo)
        rangeText.text = FuelFormat.range(range)
        rangeText.post { gradientText(rangeText) }
        val bars = state.gaugeBars
        val learnedBars = (2..FuelBook.MAX_BARS).count { state.bars[it].samples > 0 }
        rangeNote.text = when {
            bars == null -> "Turn the ignition on with the app connected to read the fuel gauge"
            range?.estimate == true -> "$bars of ${FuelBook.MAX_BARS} bars · Estimate, still learning"
            else -> "$bars of ${FuelBook.MAX_BARS} bars · learned from $learnedBars bars"
        }

        average.text = FuelFormat.kmpl(mileage.average)
        lastTank.text = FuelFormat.kmpl(mileage.last)
        cost.text = mileage.costPerKm?.let { String.format(Locale.US, "₹%.1f", it) } ?: "--"
        chart.set(mileage.perTank.takeLast(8))
        chartCard.visibility = if (spec != null && mileage.perTank.isNotEmpty()) View.VISIBLE else View.GONE

        for (i in 0 until FuelBook.MAX_BARS) {
            val bar = FuelBook.MAX_BARS - i
            val avg = if (bar >= 2) state.bars[bar].average else null
            barCells[i].text = avg?.let { it.toInt().toString() } ?: "--"
            barCells[i].setTextColor(if (avg == null) Palette.MUTED else Palette.TEXT)
        }

        refillList.removeAllViews()
        state.refills.forEach { r -> refillList.addView(refillRow(r)) }
        refillCard.visibility = if (state.refills.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (state.refills.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refillRow(r: Refill): View {
        val detail = buildList {
            r.litres?.let { add((if (r.litresEstimated) "≈ " else "") + FuelFormat.litres(it) + " L") }
            r.amount?.let { add(FuelFormat.rupees(it)) }
            if (r.full) add("Full")
            if (r.litres == null && r.amount == null) add("Not filled in")
            if (!r.manual) add("${r.barsBefore} → ${r.barsAfter} bars") else add("Added by hand")
        }.joinToString(" · ")
        return ui.navRow(TripFormat.day(r.at) + " · " + String.format(Locale.US, "%,d km", r.odoKm)) {
            RefillSheet(host, r).show()
        }.apply {
            val texts = getChildAt(0) as LinearLayout
            texts.addView(ui.text(detail, 12f, if (r.answered) Palette.MUTED else Palette.WARN))
        }
    }

    private fun gradientText(t: TextView) {
        val w = t.paint.measureText(t.text.toString())
        if (w <= 0f) return
        val start = (t.width - w) / 2
        t.paint.shader = LinearGradient(start, 0f, start + w, 0f,
            intArrayOf(Palette.ACCENT_MID, Palette.ACCENT_END), null, Shader.TileMode.CLAMP)
        t.invalidate()
    }

    private fun number(sizeSp: Float) = TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = sizeSp
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
    }

    private fun cap(s: String) = ui.text(s.uppercase(Locale.US), 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f }

    private fun stat(label: String, value: TextView, unit: String): LinearLayout =
        ui.vstack(2, cap(label).apply { gravity = Gravity.CENTER }, value, cap(unit).apply { gravity = Gravity.CENTER }).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = ui.rounded(Palette.SURFACE, 16, Palette.BORDER)
            setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
        }

    companion object {
        const val ACCURACY_NOTE =
            "Zuki Nav works out mileage and range from the fuel bars and the ODO, not from a fuel sensor, so the " +
                "numbers are rough at first: the first tank is an estimate from the tank size.\n\n" +
                "Range is learned from the moment each bar drops, which the app only sees while it is connected. " +
                "Rides without the app still count towards km/l (the ODO keeps counting) but not towards range, and " +
                "refills made with the app off are missed unless you add them by hand.\n\n" +
                "For the best numbers, keep the phone connected for whole tanks, such as on long rides and tours, " +
                "and fill to the top when you can."
    }
}

/** Bottom sheet to fill in (or add by hand) a refill: litres, amount paid, full tank. */
class RefillSheet(private val host: MainActivity, private val refill: Refill?) {
    private val ui = host.ui

    fun show() {
        val r = refill
        val odoInput = field(
            r?.odoKm?.toString() ?: (DashService.liveBike?.odoKm ?: host.prefs.lastBike?.odoKm)?.toString() ?: "",
            "ODO km", decimal = false,
        )
        val litresInput = field(r?.takeIf { !it.litresEstimated }?.litres?.let { FuelFormat.litres(it) } ?: "", "Litres", decimal = true)
        val amountInput = field(r?.amount?.let { String.format(Locale.US, "%.0f", it) } ?: "", "Amount Paid ₹", decimal = true)
        val full = Switch(ui.ctx).apply { isChecked = r?.full ?: true }

        val title = when {
            r == null -> "Add Refill"
            r.answered -> "Edit Refill"
            else -> "Refuelled?"
        }
        val sub = when {
            r == null -> "For a refill the app missed. Use the ODO reading at the pump."
            r.manual -> "Added by hand at ${String.format(Locale.US, "%,d", r.odoKm)} km"
            else -> "Fuel went from ${r.barsBefore} to ${r.barsAfter} bars at ${String.format(Locale.US, "%,d", r.odoKm)} km"
        }
        val views = ArrayList<View>()
        views += ui.vstack(2, ui.text(title, 18f, Palette.TEXT, Ui.Weight.BOLD), ui.text(sub, 13f, Palette.MUTED))
        if (r == null) views += labelled("ODO", odoInput)
        views += ui.hstack(10, labelled("Litres", litresInput), labelled("Amount Paid", amountInput), weighted = true)
        views += ui.text("Either is fine. With only the amount, litres are worked out from your last refill's price.", 12f, Palette.MUTED)
        views += ui.row("Filled To The Top", "Needed for exact km/l", full).apply { setOnClickListener { full.toggle() } }

        lateinit var dialog: android.app.Dialog
        val save = ui.button("Save Refill", Ui.Btn.PRIMARY, 48) {
            if (saveFrom(odoInput, litresInput, amountInput, full.isChecked)) dialog.dismiss()
        }
        val second = when {
            r == null -> ui.button("Cancel", Ui.Btn.SECONDARY, 48) { dialog.dismiss() }
            !r.answered -> ui.button("Skip", Ui.Btn.SECONDARY, 48) {
                FuelStore.save(host, r.copy(answered = true))
                dialog.dismiss()
            }
            else -> ui.button("Delete", Ui.Btn.SECONDARY, 48) {
                dialog.dismiss()
                confirmDelete(r)
            }.apply { setTextColor(Palette.DANGER) }
        }
        views += ui.hstack(10, second, save, weighted = true)
        if (r != null && !r.answered) {
            views += ui.text("Skip still keeps the refill; range keeps learning from km per bar.", 11f, Palette.MUTED)
                .apply { gravity = Gravity.CENTER }
        }
        dialog = ui.sheet(*views.toTypedArray())
    }

    private fun saveFrom(odoInput: EditText, litresInput: EditText, amountInput: EditText, full: Boolean): Boolean {
        val r = refill
        val odo = if (r == null) odoInput.text.toString().trim().toIntOrNull() else r.odoKm
        if (odo == null || odo <= 0) { host.toast("Enter the ODO reading"); return false }
        val typedLitres = litresInput.text.toString().trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
        val amount = amountInput.text.toString().trim().replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
        val spec = FuelStore.spec(host)
        if (typedLitres != null && spec != null && typedLitres > spec.tankLitres * 1.1) {
            host.toast("That's more than the ${FuelFormat.litres(spec.tankLitres)} L tank. Check the litres or the Tank Size in Settings.")
            return false
        }
        val at = r?.at ?: System.currentTimeMillis()
        val estimated = if (typedLitres == null && amount != null) FuelBook.litresFromAmount(FuelStore.refills(host), amount, at) else null
        FuelStore.save(
            host,
            Refill(
                at = at,
                odoKm = odo,
                barsBefore = r?.barsBefore ?: -1,
                barsAfter = r?.barsAfter ?: -1,
                litres = typedLitres ?: estimated,
                litresEstimated = typedLitres == null && estimated != null,
                amount = amount,
                full = full,
                answered = true,
            ),
        )
        return true
    }

    private fun confirmDelete(r: Refill) {
        AlertDialog.Builder(host, host.dialogTheme)
            .setTitle("Delete Refill?")
            .setMessage("The refill at ${String.format(Locale.US, "%,d", r.odoKm)} km will be removed from mileage.")
            .setPositiveButton("Delete") { _, _ -> FuelStore.delete(host, r.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun field(value: String, hint: String, decimal: Boolean) =
        ui.input(value, hint, numeric = !decimal, decimal = decimal) {}.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 16f
            minWidth = 0
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        }

    private fun labelled(label: String, input: EditText): LinearLayout =
        ui.vstack(6, ui.text(label.uppercase(Locale.US), 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f }, input)
}

/** Small bar chart of km/l per tank, oldest on the left. */
class KmplBarsView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = DashFonts.numbers
    }
    private val rect = RectF()
    private var values: List<Double> = emptyList()

    fun set(v: List<Double>) {
        values = v
        contentDescription = "km per litre per tank: " + v.joinToString { FuelFormat.kmpl(it) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val gap = 6 * d
        val top = 14 * d
        val w = (width - gap * (values.size - 1)) / values.size
        val max = values.max()
        val min = (values.min() * 0.8).coerceAtMost(max * 0.8)
        label.color = Palette.MUTED
        label.textSize = 10 * d
        values.forEachIndexed { i, v ->
            val frac = ((v - min) / (max - min).coerceAtLeast(0.001)).toFloat().coerceIn(0.15f, 1f)
            val left = i * (w + gap)
            val barTop = top + (height - top) * (1 - frac)
            rect.set(left, barTop, left + w, height.toFloat())
            paint.shader = LinearGradient(0f, barTop, 0f, height.toFloat(), Palette.ACCENT_MID, Palette.ACCENT, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, 4 * d, 4 * d, paint)
            canvas.drawText(FuelFormat.kmpl(v), left + w / 2, barTop - 3 * d, label)
        }
    }
}
