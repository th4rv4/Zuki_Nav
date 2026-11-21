package com.th4rv4.zukinav.sim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.th4rv4.zukinav.alerts.ParkedAlerts
import com.th4rv4.zukinav.nav.MapsNavListener
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.service.Wake
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs

/**
 * Lets the test script drive Zuki Sim over adb, e.g.
 *   adb shell am broadcast -n com.th4rv4.zukinav.sim/com.th4rv4.zukinav.sim.SimControl \
 *       --es cmd call --es caller saved --es end missed --ei secs 15
 * Commands: call (caller saved|unknown|cnap|whatsapp, end missed|answered|rejected, secs),
 * hangup, message (from saved|unknown|group|own, app whatsapp|sms), clear,
 * mark (text: a line in the log, to find each test step later),
 * stopsearch (as tapping Stop Searching For Bike), connect (as tapping Connect),
 * parked (value never|30|60|always, for calls|messages, both if left out:
 * Missed and Unread While Parked),
 * status (answers wake=on|off notifications=on|off calls=<choice> messages=<choice>).
 * Only the shell can send these (the receiver needs the DUMP permission).
 */
class SimControl : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val why: String? = when (val cmd = intent.getStringExtra("cmd")) {
            "call" -> FakeAlerts.ring(
                app,
                pick(intent.getStringExtra("caller"), FakeAlerts.Caller.SAVED),
                pick(intent.getStringExtra("end"), FakeAlerts.CallEnd.MISSED),
                intent.getIntExtra("secs", 15),
            )
            "hangup" -> FakeAlerts.hangUp().let { null }
            "message" -> FakeAlerts.message(
                app,
                pick(intent.getStringExtra("from"), FakeAlerts.From.SAVED),
                intent.getStringExtra("app") != "sms",
            )
            "clear" -> FakeAlerts.clearAll().let { null }
            "mark" -> AppLog.d("Test", "==== ${intent.getStringExtra("text").orEmpty()} ====").let { null }
            "stopsearch" -> {
                AppLog.d("Test", "Stop Searching For Bike (from the test)")
                DashService.stop(app)
                null
            }
            "connect" -> {
                AppLog.d("Test", "Connect (from the test)")
                DashService.start(app)
                null
            }
            "parked" -> when (val v = intent.getStringExtra("value")) {
                "never" -> 0; "30" -> 1; "60" -> 2; "always" -> 3
                else -> -1
            }.let { choice ->
                if (choice < 0) return@let "Unknown parked choice"
                val p = Prefs(app)
                val kind = intent.getStringExtra("for")
                if (kind != "messages") p.parkedCalls = choice
                if (kind != "calls") p.parkedMessages = choice
                AppLog.d("Test", "While parked, ${kind ?: "calls and messages"}: ${ParkedAlerts.LABELS[choice]}")
                null
            }
            "status" -> {
                resultData = "wake=${if (Wake.isSetUp(app)) "on" else "off"} " +
                    "notifications=${if (MapsNavListener.instance != null) "on" else "off"} " +
                    "calls=${ParkedAlerts.LABELS[Prefs(app).parkedCalls].replace(" ", "")} " +
                    "messages=${ParkedAlerts.LABELS[Prefs(app).parkedMessages].replace(" ", "")}"
                return
            }
            else -> "Unknown command: $cmd"
        }
        resultData = why ?: "ok"
    }

    private inline fun <reified E : Enum<E>> pick(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default
}
