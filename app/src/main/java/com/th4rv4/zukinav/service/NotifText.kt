package com.th4rv4.zukinav.service

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the ongoing notification says, worked out from the app's state. Kept
 * free of Android so it can be tested.
 */
object NotifText {

    enum class Link { CONNECTED, WAITING, BIKE_OFF, BLUETOOTH_OFF }

    data class Input(
        val link: Link,
        /** Engine °C, only while connected. */
        val engineC: Int? = null,
        val hotC: Int = 100,
        /** Km to the destination and the arrival time, while a Maps route is open. */
        val routeKmLeft: Double? = null,
        val eta: LocalTime? = null,
        /** Fuel range in km, if known; shown only while connected. */
        val rangeKm: Int? = null,
    )

    data class Shown(val title: String, val text: String, val hot: Boolean)

    fun compose(i: Input): Shown {
        val hot = i.link == Link.CONNECTED && i.engineC != null && i.engineC >= i.hotC
        val title = when {
            i.link == Link.BLUETOOTH_OFF -> "Bluetooth Is Off"
            hot -> "Engine Hot · ${i.engineC}°C"
            i.link == Link.CONNECTED -> "Connected"
            i.link == Link.BIKE_OFF -> "Bike Off"
            else -> "Waiting For Bike"
        }
        val parts = ArrayList<String>()
        if (i.routeKmLeft != null) {
            parts += "${distance(i.routeKmLeft)} to go"
            i.eta?.let { parts += "ETA " + it.format(TIME) }
        } else if (i.rangeKm != null && i.link == Link.CONNECTED) {
            parts += "≈ ${i.rangeKm} km range"
        }
        if (i.link == Link.CONNECTED && i.engineC != null && !hot) parts += "Engine ${i.engineC}°C"
        return Shown(title, parts.joinToString(" · "), hot)
    }

    /** 0.1 km steps up to 10 km, whole km above, so the notification changes rarely. */
    fun distance(km: Double): String =
        if (km < 10.0) String.format(Locale.US, "%.1f km", Math.floor(km * 10) / 10) else "${km.toInt()} km"

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
}
