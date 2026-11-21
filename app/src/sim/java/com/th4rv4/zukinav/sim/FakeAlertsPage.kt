package com.th4rv4.zukinav.sim

import android.view.View
import com.th4rv4.zukinav.ui.MainActivity
import com.th4rv4.zukinav.ui.Palette
import com.th4rv4.zukinav.ui.SettingsPage
import com.th4rv4.zukinav.ui.Ui

/** Settings › Fake Alerts, in Zuki Sim only. */
object FakeAlertsPage {

    private var caller = FakeAlerts.Caller.SAVED
    private var end = FakeAlerts.CallEnd.MISSED
    private var seconds = 15
    private var from = FakeAlerts.From.SAVED
    private var whatsapp = true

    fun build(host: MainActivity): SettingsPage {
        val ui = host.ui
        fun label(s: String) = ui.text(s, 13f, Palette.MUTED, Ui.Weight.MEDIUM)
        fun failed(why: String?) { if (why != null) host.showInfo("Not Sent", why) }

        val callCard = ui.card(
            label("Caller"),
            ui.segmented(FakeAlerts.Caller.entries.map { it.label }, caller.ordinal, padDp = 6, fill = true) { caller = FakeAlerts.Caller.entries[it] },
            label("How It Ends"),
            ui.segmented(FakeAlerts.CallEnd.entries.map { it.label }, end.ordinal, fill = true) { end = FakeAlerts.CallEnd.entries[it] },
            label("Rings For"),
            ui.segmented(FakeAlerts.RING_SECONDS.map { "$it s" }, FakeAlerts.RING_SECONDS.indexOf(seconds), fill = true) {
                seconds = FakeAlerts.RING_SECONDS[it]
            },
            ui.hstack(
                10,
                ui.button("Ring Now", Ui.Btn.PRIMARY, 44) { failed(FakeAlerts.ring(host, caller, end, seconds)) },
                ui.button("Hang Up", Ui.Btn.SECONDARY, 44) { FakeAlerts.hangUp() },
                weighted = true,
            ),
            gapDp = 10,
        )

        val messageCard = ui.card(
            label("From"),
            ui.segmented(FakeAlerts.From.entries.map { it.label }, from.ordinal, padDp = 6, fill = true) { from = FakeAlerts.From.entries[it] },
            label("App"),
            ui.segmented(listOf("WhatsApp", "SMS"), if (whatsapp) 0 else 1, fill = true) { whatsapp = it == 0 },
            ui.hstack(
                10,
                ui.button("Send Now", Ui.Btn.PRIMARY, 44) { failed(FakeAlerts.message(host, from, whatsapp)) },
                ui.button("Clear All", Ui.Btn.SECONDARY, 44) { FakeAlerts.clearAll() },
                weighted = true,
            ),
            gapDp = 10,
        )

        val lastSent = ui.text("", 13f, Palette.MUTED)
        fun showSent() {
            lastSent.text = if (FakeAlerts.sent.isEmpty()) "Nothing yet" else FakeAlerts.sent.joinToString("\n")
        }
        showSent()

        val views = listOf<View>(
            ui.text("Sent to the dash as if they were real. Nobody needs to call.", 13f, Palette.MUTED),
            ui.sectionLabel("Fake Call"),
            callCard,
            ui.sectionLabel("Fake Message"),
            messageCard,
            ui.sectionLabel("Last Sent"),
            ui.card(lastSent),
        )
        return SettingsPage(
            host, "Fake Alerts", views,
            showHook = {
                FakeAlerts.onChange = { host.runOnUiThread { showSent() } }
                showSent()
            },
            hideHook = { FakeAlerts.onChange = null },
        )
    }
}
