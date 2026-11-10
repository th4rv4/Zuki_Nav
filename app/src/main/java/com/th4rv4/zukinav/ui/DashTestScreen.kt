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

    private val idText = ui.text("", 52f, Palette.ACCENT_SOFT, Ui.Weight.BOLD)
    private val labelText = ui.text("", 16f, Palette.TEXT, Ui.Weight.MEDIUM)
    private val markText = ui.text("", 13f, Palette.MUTED)
    private val countsText = ui.text("", 12f, Palette.MUTED)
    private val cells = ArrayList<TextView>()
    private val cycleButton: Button = ui.button("Cycle All", Ui.Btn.SECONDARY) { toggleCycle() }

    private val textTurn = ui.input("HI", "4 chars") {}.apply { filters = arrayOf(android.text.InputFilter.LengthFilter(4)) }
    private val textEta = ui.input("RIDER", "6 chars") {}.apply { filters = arrayOf(android.text.InputFilter.LengthFilter(6)) }
    private val textLeft = ui.input("ABC1", "4 chars") {}.apply { filters = arrayOf(android.text.InputFilter.LengthFilter(4)) }

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
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.sectionLabel("Selected Icon").apply { setPadding(0, 0, 0, 0) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("Shown for 8 s", 12f, Palette.MUTED))
            },
            ui.hstack(16, idText, ui.vstack(3, labelText, markText)),
            ui.hstack(
                8,
                ui.button("Prev", Ui.Btn.SECONDARY, 48) { select(current - 1, send = false) },
                ui.button("Send", Ui.Btn.PRIMARY, 48) { send() },
                ui.button("Next", Ui.Btn.SECONDARY, 48) { select(current + 1, send = false) },
                weighted = true,
            ),
            ui.hstack(
                8,
                ui.button("Looks Right", Ui.Btn.OK, 48) { mark(IconMarks.Mark.RIGHT) },
                ui.button("Looks Wrong", Ui.Btn.WARN, 48) { mark(IconMarks.Mark.WRONG) },
                weighted = true,
            ),
            gapDp = 14,
            radiusDp = 24,
        )

        val grid = GridLayout(ui.ctx).apply {
            columnCount = 8
            useDefaultMargins = false
        }
        for (id in 1..LAST_ICON) {
            val cell = ui.text(id.toString(), 11f, Palette.MUTED, Ui.Weight.BOLD).apply {
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
                height = ui.dp(34)
                setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            }
            grid.addView(cell, lp)
            cells += cell
        }
        val legend = ui.hstack(
            14,
            legendItem("Right", Palette.OK_BORDER, null),
            legendItem("Wrong", Palette.MARK_WRONG_BG, null),
            legendItem("Untested", Palette.INSET, Palette.UNTESTED_BORDER),
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
        )

        val textCard = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.sectionLabel("Text Test").apply { setPadding(0, 0, 0, 0) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("Shown for 15 s", 12f, Palette.MUTED))
            },
            ui.text("Sends your text into the three number spots under the arrow. Numbers are sent the way navigation sends them (50 as 0050); unused places in text show as *.", 13f, Palette.MUTED),
            ui.listCard(
                ui.row("Distance Spot", "Up to 4 characters", textTurn),
                ui.row("ETA Spot", "Up to 6 characters", textEta),
                ui.row("Remaining Spot", "Up to 4 characters", textLeft),
            ),
            ui.button("Send Text", Ui.Btn.PRIMARY, 48) { sendText() },
            gapDp = 12,
            radiusDp = 24,
        )

        view = host.page(
            ui.vstack(2, ui.title("Dash Test"), ui.text("Check each turn icon on your dash", 13f, Palette.MUTED)),
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

    private fun legendItem(label: String, fill: Int, stroke: Int?): LinearLayout = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(View(ui.ctx).apply { background = ui.rounded(fill, 3, stroke) },
            LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { marginEnd = ui.dp(6) })
        addView(ui.text(label, 11f, Palette.MUTED))
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
        idText.text = "#$current"
        labelText.text = icon?.label ?: "Not In The Icon Table"
        val marks = prefs.iconMarks
        markText.text = when (marks[current]) {
            IconMarks.Mark.RIGHT -> "Marked: Looks Right"
            IconMarks.Mark.WRONG -> "Marked: Looks Wrong"
            null -> "Not Tested Yet"
        }
        val right = marks.count(IconMarks.Mark.RIGHT)
        val wrong = marks.count(IconMarks.Mark.WRONG)
        countsText.text = "$right Right · $wrong Wrong · ${LAST_ICON - right - wrong} Untested"
        cells.forEachIndexed { i, cell ->
            val id = i + 1
            val (fill, stroke, fg) = when {
                id == current -> Triple(Palette.ACCENT, null, Palette.ON_ACCENT)
                marks[id] == IconMarks.Mark.RIGHT -> Triple(Palette.OK_BORDER, null, Palette.MARK_RIGHT_FG)
                marks[id] == IconMarks.Mark.WRONG -> Triple(Palette.MARK_WRONG_BG, null, Palette.MARK_WRONG_FG)
                else -> Triple(Palette.INSET, Palette.UNTESTED_BORDER, Palette.MUTED)
            }
            cell.background = ui.pressable(fill, 8, stroke)
            cell.setTextColor(fg)
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
