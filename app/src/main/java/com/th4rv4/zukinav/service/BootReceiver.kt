package com.th4rv4.zukinav.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.th4rv4.zukinav.util.Prefs

/**
 * Restarts the dash link after a reboot, or after the app updates itself,
 * if a bike is paired and Start On Boot is on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (prefs.startOnBoot && prefs.clusterAddress != null && DashService.hasConnectPermission(context)) {
            DashService.start(context)
        }
    }
}
