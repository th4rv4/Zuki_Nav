package com.th4rv4.zukinav.ui

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.ble.ClusterLink
import com.th4rv4.zukinav.protocol.ClusterIcon
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.util.IconMarks

/**
 * Shows any turn icon on the dash and lets the rider mark whether it looked
 * right. Results are saved and can be shared.
 */
class DashTestScreen(host: MainActivity) : Screen(host) {

    private var current = 1
    private var cycling = false

    private val ring = TurnRingView(host.ui.ctx)
    private val idText = android.widget.TextView(host.ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = 22f
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
    }
    private val markText = ui.text("", 13f, Palette.MUTED).apply { gravity = Gravity.CENTER }
    private val countsText = ui.text("", 12f, Palette.MUTED)
    private val cells = ArrayList<TextView>()
    private val cycleButton: Button = ui.button("Cycle All", Ui.Btn.SECONDARY) { toggleCycle() }

    private val textTurn = textInput("HI", 4)
    private val textEta = textInput("RIDER", 6)
    private val textLeft = textInput("ABC1", 4)

    override val view: View

    private val stepper = object : Runnable {
        override fun run() {
            if (!cycling) return
            if (current >= LAST_ICON) {
                stopCycle()
                return
            }
            select(current + 1, send = true)
            view.postDelayed(this, 2_500)
        }
    }

    init {
        val currentCard = ui.card(
            cap("Selected Icon · Shown For 8 s").apply { gravity = Gravity.CENTER },
            LinearLayout(ui.ctx).apply {
                gravity = Gravity.CENTER
                addView(ring, LinearLayout.LayoutParams(ui.dp(104), ui.dp(104)))
            },
            ui.vstack(2, idText, markText),
            ui.hstack(
                8,
                ui.button("Prev", Ui.Btn.SECONDARY, 48) { select(current - 1, send = false) },
                ui.button("Send", Ui.Btn.PRIMARY, 48) { send() },
                ui.button("Next", Ui.Btn.SECONDARY, 48) { select(current + 1, send = false) },
                weighted = true,
            ),
            ui.hstack(
                8,
                ui.button("Looks Right", Ui.Btn.SECONDARY, 48) { mark(IconMarks.Mark.RIGHT) }.apply { setTextColor(Palette.OK) },
                ui.button("Looks Wrong", Ui.Btn.SECONDARY, 48) { mark(IconMarks.Mark.WRONG) }.apply { setTextColor(Palette.DANGER) },
                weighted = true,
            ),
            gapDp = 14,
            padDp = 18,
            radiusDp = 24,
        )

        val grid = GridLayout(ui.ctx).apply {
            columnCount = 8
            useDefaultMargins = false
        }
        for (id in 1..LAST_ICON) {
            val cell = android.widget.TextView(ui.ctx).apply {
                text = id.toString()
                typeface = DashFonts.numbers
                textSize = 13f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "Icon $id"
                setOnClickListener { select(id, send = true) }
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                height = ui.dp(36)
                setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            }
            grid.addView(cell, lp)
            cells += cell
        }
        val legend = ui.hstack(
            14,
            legendItem("Right", Palette.OK),
            legendItem("Wrong", Palette.DANGER),
            legendItem("Untested", Palette.UNTESTED_BORDER),
        )
        val allCard = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("All Icons", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(countsText)
            },
            grid,
            legend,
            radiusDp = 22,
        )

        val textCard = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("Text Test", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("Shown for 15 s", 12f, Palette.MUTED))
            },
            ui.text("Puts your text in the three number spots under the arrow. Numbers go in the way navigation sends them (50 as 0050); unused places in text show as *.", 12f, Palette.MUTED),
            ui.hstack(
                8,
                ui.vstack(4, cap("Distance"), textTurn),
                ui.vstack(4, cap("ETA"), textEta),
                ui.vstack(4, cap("Remaining"), textLeft),
                weighted = true,
            ),
            ui.button("Send Text", Ui.Btn.PRIMARY, 48) { sendText() },
            gapDp = 12,
            radiusDp = 22,
        )

        val back = ui.text("‹", 30f, Palette.TEXT).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Back to Settings"
            setPadding(ui.dp(4), 0, ui.dp(14), ui.dp(4))
            setOnClickListener { host.showTab(MainActivity.Tab.SETTINGS) }
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(ui.vstack(2, ui.text("Dash Test", 22f, Palette.TEXT, Ui.Weight.BOLD),
                ui.text("Check each turn icon on your dash", 13f, Palette.MUTED)),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        view = host.page(
            header,
            currentCard,
            allCard,
            textCard,
            ui.text(ICON_WARNING, 12f, Palette.WARN),
            ui.hstack(8, cycleButton, ui.button("Share Results", Ui.Btn.SECONDARY) { share() }, weighted = true),
        )
    }

    override fun onShow() {
        select(current, send = false)
    }

    override fun onHide() {
        stopCycle()
    }

    private fun legendItem(label: String, color: Int): LinearLayout = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(View(ui.ctx).apply { background = ui.rounded(color, 4) },
            LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(6) })
        addView(ui.text(label, 11f, Palette.MUTED))
    }

    private fun cap(s: String) = ui.text(s.uppercase(java.util.Locale.US), 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f }

    private fun textInput(value: String, max: Int) = ui.input(value, "$max chars") {}.apply {
        filters = arrayOf(android.text.InputFilter.LengthFilter(max))
        gravity = Gravity.CENTER
        minWidth = 0
    }

    /** Grid cell: rounded tile, a ring when selected, a coloured dot once marked. */
    private fun cellBackground(selected: Boolean, mark: IconMarks.Mark?): android.graphics.drawable.Drawable {
        val base = if (selected) {
            ui.rounded(Palette.ACCENT_BG, 10).apply { setStroke(ui.dp(2), Palette.ACCENT_SOFT) }
        } else {
            ui.rounded(Palette.INSET, 10, Palette.UNTESTED_BORDER)
        }
        val dotColor = when (mark) {
            IconMarks.Mark.RIGHT -> Palette.OK
            IconMarks.Mark.WRONG -> Palette.DANGER
            null -> return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Palette.RIPPLE), base, null)
        }
        val dot = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(dotColor)
        }
        val layers = android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(base, dot)).apply {
            setLayerSize(1, ui.dp(6), ui.dp(6))
            setLayerGravity(1, Gravity.TOP or Gravity.END)
            setLayerInset(1, 0, ui.dp(4), ui.dp(4), 0)
        }
        return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Palette.RIPPLE), layers, null)
    }

    private fun select(id: Int, send: Boolean) {
        current = ((id - 1 + LAST_ICON) % LAST_ICON) + 1
        if (send) send()
        render()
    }

    private fun send() {
        if (DashService.linkState != ClusterLink.State.READY) {
            host.toast("Connect to the bike first")
            stopCycle()
            return
        }
        if (!DashService.testIcon(current)) {
            host.toast(NAVIGATING)
            stopCycle()
        }
    }

    private fun sendText() {
        if (DashService.linkState != ClusterLink.State.READY) {
            host.toast("Connect to the bike first")
            return
        }
        if (!DashService.testText(textTurn.text.toString(), textEta.text.toString(), textLeft.text.toString())) {
            host.toast(NAVIGATING)
        }
    }

    /** Saves the verdict for the selected icon, then moves on and shows the next one. */
    private fun mark(m: IconMarks.Mark) {
        val marks = prefs.iconMarks
        prefs.iconMarks = marks.with(current, if (marks[current] == m) null else m)
        if (current < LAST_ICON && DashService.linkState == ClusterLink.State.READY) {
            select(current + 1, send = true)
        } else {
            render()
        }
    }

    private fun render() {
        val icon = ClusterIcon.fromId(current)
        idText.text = "Icon $current · " + (icon?.label ?: "Not In The Icon Table")
        ring.set(icon?.let { TurnArt.of(it) }, if (icon == null) null else 500.0)
        ring.contentDescription = icon?.label ?: "Icon $current"
        val marks = prefs.iconMarks
        markText.text = when (marks[current]) {
            IconMarks.Mark.RIGHT -> "Marked: Looks Right"
            IconMarks.Mark.WRONG -> "Marked: Looks Wrong"
            null -> "Not Tested Yet"
        }
        markText.setTextColor(when (marks[current]) {
            IconMarks.Mark.RIGHT -> Palette.OK
            IconMarks.Mark.WRONG -> Palette.DANGER
            null -> Palette.MUTED
        })
        val right = marks.count(IconMarks.Mark.RIGHT)
        val wrong = marks.count(IconMarks.Mark.WRONG)
        countsText.text = "$right Right · $wrong Wrong · ${LAST_ICON - right - wrong} Untested"
        cells.forEachIndexed { i, cell ->
            val id = i + 1
            cell.background = cellBackground(id == current, marks[id])
            cell.setTextColor(if (id == current) Palette.TEXT else if (marks[id] == null) Palette.MUTED else Palette.TEXT_SOFT)
        }
    }

    private fun toggleCycle() {
        if (cycling) {
            stopCycle()
            return
        }
        if (DashService.linkState != ClusterLink.State.READY) {
            host.toast("Connect to the bike first")
            return
        }
        cycling = true
        cycleButton.text = "Stop Cycle"
        select(1, send = true)
        view.postDelayed(stepper, 2_500)
    }

    private fun stopCycle() {
        cycling = false
        view.removeCallbacks(stepper)
        cycleButton.text = "Cycle All"
    }

    private fun share() {
        val model = ClusterModels.fromBleName(prefs.clusterName).displayName
        host.shareText("Zuki Nav Dash Test", prefs.iconMarks.summary(model, LAST_ICON))
    }

    companion object {
        const val LAST_ICON = 64
        const val NAVIGATING = "A route is active. Dash Test works when Google Maps isn't navigating."


        const val ICON_WARNING =
            "Some icons may not show on your dash. Icons 47 to 64 differ between left- and right-hand " +
                "traffic and between Suzuki models: on a Gixxer SF 250, 46 is a \"!\" and 47 to 64 are blank."
    }
}
