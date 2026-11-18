package com.th4rv4.zukinav.service

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs

/**
 * Wake On Ignition: the bike is registered with Android as a companion
 * device, so the phone's own Bluetooth watches for it and wakes Zuki Nav
 * when the dash comes on. While the bike is parked, DashService can then
 * stop completely instead of retrying in the background. Android 12 and newer.
 */
object Wake {

    fun supported(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun cdm(ctx: Context): CompanionDeviceManager? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(CompanionDeviceManager::class.java) else null

    /** True when the saved bike is registered and Android is watching for it. */
    @Suppress("DEPRECATION")
    fun isSetUp(ctx: Context): Boolean {
        if (!supported(ctx)) return false
        val addr = Prefs(ctx).clusterAddress ?: return false
        val m = cdm(ctx) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                m.myAssociations.any { it.deviceMacAddress?.toString().equals(addr, ignoreCase = true) }
            } else {
                m.associations.any { it.equals(addr, ignoreCase = true) }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shows Android's "allow Zuki Nav to access your bike" prompt. The dash
     * has to be on, so Android can find it. [onDone] gets true when it worked.
     */
    @Suppress("DEPRECATION")
    fun setUp(activity: Activity, requestCode: Int, onDone: (Boolean) -> Unit) {
        val prefs = Prefs(activity)
        val addr = prefs.clusterAddress
        val m = cdm(activity)
        if (addr == null || m == null || Build.VERSION.SDK_INT < 31) { onDone(false); return }
        // By address, and by the dash's name in case it advertises a different address.
        val builder = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setScanFilter(ScanFilter.Builder().setDeviceAddress(addr.uppercase()).build())
                    .build(),
            )
        prefs.clusterName?.takeIf { it.isNotBlank() }?.let { name ->
            builder.addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name)))
                    .build(),
            )
        }
        val request = builder.build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(sender: IntentSender) = show(sender)

            @Deprecated("Android 12 calls this instead of onAssociationPending")
            override fun onDeviceFound(sender: IntentSender) = show(sender)

            private fun show(sender: IntentSender) {
                try {
                    activity.startIntentSenderForResult(sender, requestCode, null, 0, 0, 0)
                } catch (e: Exception) {
                    AppLog.d("Wake", "Could not show the prompt: $e")
                    onDone(false)
                }
            }

            override fun onAssociationCreated(info: AssociationInfo) = Unit

            override fun onFailure(error: CharSequence?) {
                AppLog.d("Wake", "Setup failed: $error")
                onDone(false)
            }
        }
        try {
            m.associate(request, callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            AppLog.d("Wake", "Setup failed: $e")
            onDone(false)
        }
    }

    /** After the rider allowed it: ask Android to watch for the bike. */
    @Suppress("DEPRECATION")
    fun startWatching(ctx: Context): Boolean {
        val addr = Prefs(ctx).clusterAddress ?: return false
        val m = cdm(ctx) ?: return false
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            m.startObservingDevicePresence(addr.uppercase())
            AppLog.d("Wake", "Android is watching for the bike")
            true
        } catch (e: Exception) {
            AppLog.d("Wake", "Could not watch for the bike: $e")
            false
        }
    }

    /**
     * Asks Android again to watch for the bike, if Wake On Ignition is set up.
     * Android can stop watching when the app is updated or reinstalled, which
     * left the app asleep with nothing to wake it when the key went on.
     */
    fun rewatch(ctx: Context) {
        if (!isSetUp(ctx)) return
        startWatching(ctx)
    }

    /** Turns Wake On Ignition off: Android forgets the bike as a companion. */
    @Suppress("DEPRECATION")
    fun turnOff(ctx: Context) {
        val addr = Prefs(ctx).clusterAddress ?: return
        val m = cdm(ctx) ?: return
        if (Build.VERSION.SDK_INT < 31) return
        try { m.stopObservingDevicePresence(addr.uppercase()) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                m.myAssociations.filter { it.deviceMacAddress?.toString().equals(addr, ignoreCase = true) }
                    .forEach { m.disassociate(it.id) }
            } else {
                m.disassociate(addr.uppercase())
            }
            AppLog.d("Wake", "Wake On Ignition turned off")
        } catch (e: Exception) {
            AppLog.d("Wake", "Could not turn it off: $e")
        }
    }
}

/** Android calls this when it sees the bike (ignition on): start the dash link. */
class BikePresenceService : CompanionDeviceService() {

    @Deprecated("Android 12 calls this one")
    override fun onDeviceAppeared(address: String) = bikeAppeared()

    override fun onDeviceAppeared(associationInfo: AssociationInfo) = bikeAppeared()

    private fun bikeAppeared() {
        val prefs = Prefs(this)
        if (!prefs.autoConnect || DashService.instance != null) return
        AppLog.d("Wake", "Bike seen, waking the dash link")
        try {
            DashService.start(this)
        } catch (e: Exception) {
            AppLog.d("Wake", "Could not start the dash link: $e")
        }
    }
}
