package com.th4rv4.suzukinav.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.th4rv4.suzukinav.util.Prefs

/** Restarts the dash link after a reboot if a cluster is paired and auto-connect is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.autoConnect && prefs.clusterAddress != null && DashService.hasConnectPermission(context)) {
            DashService.start(context)
        }
    }
}
