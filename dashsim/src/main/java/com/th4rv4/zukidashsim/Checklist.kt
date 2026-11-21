package com.th4rv4.zukidashsim

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.TimeUnit

/**
 * Setup Checklist for this phone: what it needs to pass for a dash. Shrinks
 * to one line once everything is in place.
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class Checklist(
    private val host: Activity,
    private val dp: (Int) -> Int,
    /** Bluetooth goes off with the ignition, so the Bluetooth checks are judged when it was last on. */
    private val ignitionOn: () -> Boolean,
) {

    private class Item(
        val title: String,
        val detail: String,
        val ok: Boolean,
        val needed: Boolean,
        val fixLabel: String?,
        val fix: () -> Unit,
    )

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? get() = host.getSystemService(BluetoothManager::class.java)?.adapter

    /** null until checked; checked in the background since "su" can take a moment. */
    @Volatile private var root: Boolean? = null
    private var expanded = false
    private var lastName: String? = null
    private var lastAdvertise: Boolean? = null
    private var lastPaired: List<String> = emptyList()

    val view: LinearLayout = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }

    init {
        checkRoot()
    }

    private fun items(): List<Item> {
        val a = adapter
        val on = a?.isEnabled == true
        if (on) {
            lastName = a?.name
            lastAdvertise = a?.isMultipleAdvertisementSupported == true && a?.bluetoothLeAdvertiser != null
        }
        // Off because the ignition is off, as planned: judge by when it was last on.
        val offWithIgnition = !on && !ignitionOn()
        val known = on || (offWithIgnition && lastAdvertise != null)
        val named = known && lastName == DashPeripheral.NAME
        val canAdvertise = known && lastAdvertise == true
        // Paired, the other phone can tell this one apart under its changing
        // private address, so the dash keeps one address like a real one.
        val pairedPhones = if (on) {
            try {
                a?.bondedDevices.orEmpty()
                    .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
                    .map { it.name ?: it.address }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            lastPaired
        }
        lastPaired = pairedPhones
        val located = host.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return listOf(
            Item(
                "Bluetooth",
                when {
                    on -> "On"
                    offWithIgnition -> "Off with the ignition, like the real dash; Ignition turns it on"
                    else -> "Off, so Zuki Sim can't find the dash"
                },
                on || offWithIgnition, true, if (offWithIgnition) null else "Turn On",
            ) {
                host.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
            Item(
                "Dash Name",
                when {
                    !known -> "Turn Bluetooth on first"
                    named -> "Bluetooth name is ${DashPeripheral.NAME}"
                    else -> "Bluetooth name is \"$lastName\", Zuki Sim looks for ${DashPeripheral.NAME}"
                },
                named, true, if (on) "Set" else null,
            ) {
                adapter?.setName(DashPeripheral.NAME)
                main.postDelayed({ refresh() }, 800)
            },
            Item(
                "Can Act As A Dash",
                when {
                    !known -> "Turn Bluetooth on to check"
                    canAdvertise -> "This phone can advertise over Bluetooth"
                    else -> "This phone can't advertise over Bluetooth, so it can't play the dash"
                },
                canAdvertise, true, null,
            ) {},
            Item(
                "Paired With The Phone",
                if (pairedPhones.isNotEmpty()) "Paired with ${pairedPhones.joinToString()}: the dash keeps one address"
                else "Not paired: this phone's address changes every few minutes, so Zuki Sim loses it. Pair from the other phone.",
                pairedPhones.isNotEmpty(), true, "Open",
            ) {
                host.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            },
            Item(
                "Location",
                if (located) "Allowed: rides start where this phone is" else "Not allowed: rides start in Delhi",
                located, false, "Allow",
            ) {
                host.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 2)
            },
            Item(
                "Root",
                when (root) {
                    null -> "Checking…"
                    true -> "Allowed, for keeping one Bluetooth address later"
                    false -> "Not allowed: only needed if reconnecting after a while fails"
                },
                root == true, false, if (root == false) "Ask" else null,
            ) { checkRoot() },
        )
    }

    fun refresh() {
        val all = items()
        val toFix = all.count { it.needed && !it.ok }
        val allDone = all.all { it.ok }
        view.removeAllViews()

        val header = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(6))
            addView(small("SETUP CHECKLIST", MUTED), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val (countText, countColor) = when {
                toFix > 0 -> "$toFix TO FIX" to WARN
                allDone -> "ALL DONE" to OK
                else -> "${all.count { !it.ok }} OPTIONAL LEFT" to ACCENT
            }
            addView(small(countText, countColor))
        }
        view.addView(header)

        // Everything in place: one line, tap to see the list.
        if (allDone && !expanded) {
            val line = TextView(host).apply {
                text = "Setup ✓ All done"
                setTextColor(OK)
                textSize = 14f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = card()
                setOnClickListener {
                    expanded = true
                    refresh()
                }
            }
            view.addView(line)
            return
        }

        val list = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
        }
        all.forEachIndexed { i, item ->
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val words = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            words.addView(TextView(host).apply {
                text = item.title
                textSize = 14f
            })
            words.addView(TextView(host).apply {
                text = item.detail
                textSize = 12f
                setTextColor(MUTED)
            })
            row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            when {
                item.ok -> row.addView(TextView(host).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(OK)
                    typeface = Typeface.DEFAULT_BOLD
                })
                item.fixLabel != null -> row.addView(Button(host).apply {
                    text = item.fixLabel
                    isAllCaps = false
                    setOnClickListener {
                        item.fix()
                        refresh()
                    }
                })
                else -> row.addView(TextView(host).apply {
                    text = "✕"
                    textSize = 16f
                    setTextColor(WARN)
                })
            }
            list.addView(row)
            if (i < all.size - 1) {
                list.addView(View(host).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            }
        }
        if (allDone) {
            list.setOnClickListener {
                expanded = false
                refresh()
            }
        }
        view.addView(list)
    }

    /** Runs "su -c id"; the first time, Magisk/APatch asks whether to allow it. */
    private fun checkRoot() {
        root = null
        Thread {
            val ok = try {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(15, TimeUnit.SECONDS) && out.contains("uid=0")
            } catch (_: Exception) {
                false
            }
            root = ok
            main.post { refresh() }
        }.start()
    }

    private fun small(s: String, color: Int) = TextView(host).apply {
        text = s
        textSize = 11f
        letterSpacing = 0.1f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
    }

    private fun card() = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(CARD)
    }

    private companion object {
        val OK = Color.parseColor("#4CC38A")
        val WARN = Color.parseColor("#E8A33D")
        val ACCENT = Color.parseColor("#7FA8FF")
        val MUTED = Color.parseColor("#8A93A8")
        val CARD = Color.parseColor("#1A2236")
        val LINE = Color.parseColor("#2A3350")
    }
}
