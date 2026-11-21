package com.th4rv4.zukinav.sim

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.util.AppLog

/**
 * Finds Zuki Dash Sim by name. A phone playing the dash advertises from a
 * new private Bluetooth address every few minutes and after every Ignition
 * on (a real dash keeps one), so Zuki Sim looks it up before connecting.
 */
@SuppressLint("MissingPermission")
object SimFinder {

    private const val SEARCH_MS = 8_000L
    private val main = Handler(Looper.getMainLooper())

    fun find(ctx: Context, done: (String?) -> Unit) {
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            done(null)
            return
        }
        var finished = false
        lateinit var callback: ScanCallback
        fun finish(addr: String?) {
            if (finished) return
            finished = true
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
            done(addr)
        }
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post { finish(result.device.address) }
            }

            override fun onScanFailed(errorCode: Int) {
                AppLog.d("Sim", "Couldn't look for Zuki Dash Sim (scan error $errorCode)")
                main.post { finish(null) }
            }
        }
        val filter = ScanFilter.Builder().setDeviceName(SimChecks.DASH_NAME).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: Exception) {
            AppLog.d("Sim", "Couldn't look for Zuki Dash Sim: ${e.javaClass.simpleName}")
            done(null)
            return
        }
        main.postDelayed({ finish(null) }, SEARCH_MS)
    }
}
