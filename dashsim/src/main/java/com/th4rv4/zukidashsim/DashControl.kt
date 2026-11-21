package com.th4rv4.zukidashsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets the test script drive Zuki Dash Sim over adb, e.g.
 *   adb shell am broadcast -n com.th4rv4.zukidashsim/.DashControl --es cmd speed --es value 60
 * Commands: ignition on|off, engine on|off, speed <km/h>, timex 1|10|60,
 * skip (2 h), drop, silent on|off, fuel <litres>, mark <text>.
 * The app has to be open (adb shell am start -n com.th4rv4.zukidashsim/.MainActivity).
 * Only the shell can send these (the receiver needs the DUMP permission).
 */
class DashControl : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val value = intent.getStringExtra("value")
        val screen = MainActivity.instance
        resultData = screen?.command(cmd, value) ?: "Zuki Dash Sim isn't open"
    }
}
