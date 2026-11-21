package com.th4rv4.zukidashsim

import com.th4rv4.zukinav.protocol.SimFrame
import com.th4rv4.zukinav.protocol.SuzukiFrames
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin

/**
 * A made-up Gixxer SF 250: speed, ODO, trip meters, fuel and engine
 * temperature, moved on by [step] in ride time.
 */
class Bike {
    var ignitionOn = false
        set(v) {
            field = v
            if (!v) engineOn = false
        }
    var engineOn = false
        set(v) {
            field = v && ignitionOn && fuelL > 0.0
        }

    /** Where the rider holds the throttle; the bike gets there smoothly. */
    var targetKmh = 0
    var speedKmh = 0.0
        private set

    var odoKm = 12_345.0
    var tripAKm = 0.0
    var tripBKm = 0.0
    var fuelL = 10.0
    var engineC = AMBIENT_C

    var lat = 28.6129
    var lng = 77.2295
    var headingDeg = 30.0
        private set
    /** Distance moved in this sim, for bending the made-up road. */
    var movedKm = 0.0

    val fuelBars: Int
        get() = if (fuelL < 0.4) 0 else ceil(fuelL / TANK_L * 6).toInt().coerceIn(1, 6)

    fun step(ms: Long) {
        if (ms <= 0) return
        val s = ms / 1000.0
        val target = if (engineOn) targetKmh.toDouble() else 0.0
        speedKmh = if (speedKmh < target) {
            (speedKmh + ACCEL_KMH_S * s).coerceAtMost(target)
        } else {
            (speedKmh - BRAKE_KMH_S * s).coerceAtLeast(target)
        }

        val km = speedKmh * s / 3600.0
        if (km > 0) {
            odoKm += km
            tripAKm = (tripAKm + km) % 1000.0
            tripBKm = (tripBKm + km) % 1000.0
            movedKm += km
            // A gently winding road that comes round in a big loop (about
            // 200 km), so long test rides stay near home instead of
            // heading off across the map.
            headingDeg = (movedKm / LOOP_KM * 360.0 + 25.0 * sin(movedKm / 4.0)) % 360.0
            val (la, ln) = SimFrame.move(lat, lng, headingDeg, km)
            lat = la
            lng = ln
        }

        if (engineOn) {
            fuelL = (fuelL - km / KM_PER_L - IDLE_L_PER_H * s / 3600.0).coerceAtLeast(0.0)
            if (fuelL <= 0.0) engineOn = false // ran dry
            // Oil-cooled: runs hotter standing still than on the move.
            val hot = if (speedKmh < 20) 96.0 else 84.0
            val maxStep = WARM_C_PER_S * s
            engineC += (hot - engineC).coerceIn(-maxStep, maxStep)
        } else {
            engineC += (AMBIENT_C - engineC) * (1 - exp(-s / COOL_TAU_S))
        }
    }

    /** The dash's "?7" reading, as the real one sends it every 5 seconds. */
    fun reading(): ByteArray {
        val f = ByteArray(SuzukiFrames.FRAME_SIZE)
        f[0] = SuzukiFrames.START
        f[1] = '7'.code.toByte()
        put(f, 2, "%03d".format(speedKmh.toInt().coerceIn(0, 999)))
        put(f, 5, "%06d".format(odoKm.toLong().coerceIn(0, 999_999)))
        put(f, 11, "%06d".format((tripAKm * 10).toLong().coerceIn(0, 999_999)))
        put(f, 17, "%06d".format((tripBKm * 10).toLong().coerceIn(0, 999_999)))
        f[23] = 'N'.code.toByte()
        f[24] = ('0'.code + fuelBars).toByte()
        f[25] = Math.round(engineC).toInt().coerceIn(0, 254).toByte()
        f[26] = 0xFF.toByte()
        f[27] = 0xFF.toByte()
        f[28] = SuzukiFrames.checksum(f, SuzukiFrames.Checksum.SUM)
        f[29] = SuzukiFrames.END
        return f
    }

    fun simFrame(rideTime: Long, speedUp: Double): ByteArray =
        SimFrame(
            rideTime, speedUp, lat, lng, speedKmh.toInt(),
            ignitionOn = ignitionOn, engineOn = engineOn, headingDeg = Math.round(headingDeg).toInt(),
        ).encode()

    private fun put(f: ByteArray, at: Int, s: String) {
        for (i in s.indices) f[at + i] = s[i].code.toByte()
    }

    companion object {
        const val TANK_L = 12.0
        private const val LOOP_KM = 200.0
        const val AMBIENT_C = 25.0
        private const val KM_PER_L = 35.0
        private const val IDLE_L_PER_H = 0.25
        private const val ACCEL_KMH_S = 4.0
        private const val BRAKE_KMH_S = 8.0
        private const val WARM_C_PER_S = 0.4 // 2 °C every 5 seconds
        private const val COOL_TAU_S = 1500.0
    }
}
