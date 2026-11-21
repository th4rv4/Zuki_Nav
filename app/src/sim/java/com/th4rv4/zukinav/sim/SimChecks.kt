package com.th4rv4.zukinav.sim

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.ui.MainActivity
import com.th4rv4.zukinav.ui.SetupChecklist
import com.th4rv4.zukinav.util.Prefs

/** Zuki Sim's own Setup Checklist items: the Test Build section. */
object SimChecks {

    /** What Zuki Dash Sim calls itself. */
    const val DASH_NAME = "SBM21SIMDASH"

    fun items(host: MainActivity): List<SetupChecklist.Item> {
        val mock = mockLocationAllowed(host)
        val paired = Prefs(host).clusterName == DASH_NAME
        val bonded = paired && DashService.isBikeBonded(host)
        return listOf(
            SetupChecklist.Item(
                "Mock Location App",
                if (mock) "Zuki Sim is set, so the ride's GPS comes from Zuki Dash Sim"
                else "Pick Zuki Sim in Developer options, for the ride's GPS",
                mock, true, "Open", test = true,
                fix = {
                    try {
                        host.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (_: Exception) {
                        host.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                },
            ),
            SetupChecklist.Item(
                "Paired With Dash Sim",
                if (paired) DASH_NAME else "Scan and pick $DASH_NAME",
                paired, true, "Set Up", test = true,
                fix = { host.showSetup() },
            ),
            // Paired, the phones share the key that ties the Realme's changing
            // private address to its fixed one, so the dash keeps one address
            // like a real one: reconnects and Wake On Ignition then work.
            SetupChecklist.Item(
                "Phones Paired",
                if (bonded) "Zuki Dash Sim keeps one address, like a real dash"
                else "Pair with SBM21SIMDASH in Bluetooth settings, then pick it again in Change Bike",
                bonded, true, "Open", test = true,
                fix = { host.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            ),
        )
    }

    private fun mockLocationAllowed(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}
