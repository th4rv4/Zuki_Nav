package com.th4rv4.gixxernav.protocol

import java.time.LocalTime

/**
 * Builds the 30-byte frames the Suzuki Ride Connect cluster accepts.
 *
 * Every frame:
 *   [0]      0xA5 start
 *   [1]      frame type, ASCII ('1' nav, '2' call, '3' status, '4' missed call,
 *            '5' SMS/notification, '6' hello)
 *   [2..27]  payload
 *   [28]     checksum over bytes 1..27
 *   [29]     0x7F end
 *
 * See docs/PROTOCOL.md for where each field was found in the Suzuki app.
 */
object SuzukiFrames {

    const val FRAME_SIZE = 30
    const val START: Byte = 0xA5.toByte()
    const val END: Byte = 0x7F
    private const val FF: Byte = 0xFF.toByte()

    /**
     * Sum mode is used by the B-series clusters (Gixxer, Gixxer 250/SF 250,
     * V-Strom SX, Avenis, Burgman EX). The A-series scooter clusters (Access
     * 125, Burgman Street) want 255 minus the sum.
     */
    enum class Checksum { SUM, INVERTED }

    /** Status byte 23 of the nav frame, as set by the Suzuki app. */
    enum class NavStatus(val code: Char) {
        NO_SIM('0'),
        OK('1'),
        STATUS_2('2'),
        STATUS_3('3'),
        GPS_LOST('4'),
        ARRIVED('5'),
        NO_NETWORK('6');

        /** The Suzuki app swaps the turn arrow for a placeholder in any other state. */
        val showsArrow: Boolean get() = this == OK || this == STATUS_3 || this == ARRIVED
    }

    fun checksum(frame: ByteArray, mode: Checksum): Byte {
        var sum = 0
        for (i in 1..27) sum += frame[i].toInt() and 0xFF
        sum = sum and 0xFF
        return (if (mode == Checksum.INVERTED) 255 - sum else sum).toByte()
    }

    private fun newFrame(type: Char): ByteArray =
        ByteArray(FRAME_SIZE).also {
            it[0] = START
            it[1] = type.code.toByte()
        }

    private fun seal(frame: ByteArray, mode: Checksum): ByteArray {
        frame[28] = checksum(frame, mode)
        frame[29] = END
        return frame
    }

    private fun ByteArray.putAscii(at: Int, s: String) {
        for (i in s.indices) this[at + i] = s[i].code.toByte()
    }

    private fun ByteArray.fill(from: Int, toInclusive: Int, b: Byte) {
        for (i in from..toInclusive) this[i] = b
    }

    /** Printable ASCII only; the cluster has no font for anything else. */
    fun asciiOnly(s: String): String =
        buildString { for (c in s) append(if (c.code in 0x20..0x7E) c else ' ') }
            .replace(Regex(" {2,}"), " ")
            .trim()

    /**
     * Navigation frame ("?1").
     *
     *   [2]      cluster turn-icon ID (raw byte, see [ClusterIcon])
     *   [3]      0xFF
     *   [4..7]   distance to the turn, 4 ASCII chars
     *   [8]      its unit, 'K' or 'M'
     *   [9..14]  ETA, 6 ASCII chars ("0742PM", or "001942" in 24-hour mode)
     *   [15..17] 0xFF
     *   [18..21] remaining trip distance, 4 ASCII chars
     *   [22]     its unit
     *   [23]     [NavStatus]
     *   [24]     '1' while navigating, '0' to tell the cluster navigation ended
     *   [25..27] 0xFF
     */
    fun navigation(
        iconId: Int,
        toTurn: DashDistance,
        eta: String,
        remaining: DashDistance,
        status: NavStatus = NavStatus.OK,
        active: Boolean = true,
        mode: Checksum = Checksum.SUM,
    ): ByteArray {
        require(eta.length == 6) { "eta must be 6 chars: '$eta'" }
        val f = newFrame('1')
        f[2] = (if (status.showsArrow) iconId else ClusterIcon.STATUS_PLACEHOLDER).toByte()
        f[3] = FF
        f.putAscii(4, toTurn.digits)
        f[8] = toTurn.unit.code.toByte()
        f.putAscii(9, eta)
        f.fill(15, 17, FF)
        f.putAscii(18, remaining.digits)
        f[22] = remaining.unit.code.toByte()
        f[23] = status.code.code.toByte()
        f[24] = (if (active) '1' else '0').code.toByte()
        f.fill(25, 27, FF)
        return seal(f, mode)
    }

    /**
     * ETA field. In 12-hour mode the Suzuki app sends "hhmma" ("0742PM"). In
     * 24-hour mode it strips the colon from "HH:mm" and left-pads to six
     * characters ("001942"), which is reproduced here as-is.
     */
    fun etaField(time: LocalTime?, use24h: Boolean): String {
        if (time == null) return "000000"
        return if (use24h) {
            "%02d%02d".format(time.hour, time.minute).padStart(6, '0')
        } else {
            val h12 = (time.hour % 12).let { if (it == 0) 12 else it }
            "%02d%02d%s".format(h12, time.minute, if (time.hour < 12) "AM" else "PM")
        }
    }

    /**
     * Hello frame ("?6"), sent a few times right after connecting and before
     * navigation starts.
     *
     *   [2..21]  rider name, ASCII, NUL padded (20 chars max)
     *   [22..26] 0xFF
     *   [27]     'F' the first time this phone talks to this cluster, else 'R'
     */
    fun hello(riderName: String, firstPairing: Boolean, mode: Checksum = Checksum.SUM): ByteArray {
        val f = newFrame('6')
        f.putAscii(2, asciiOnly(riderName).take(20))
        f.fill(22, 26, FF)
        f[27] = (if (firstPairing) 'F' else 'R').code.toByte()
        return seal(f, mode)
    }

    /** Weather icon codes for the status frame (Suzuki's own buckets). */
    enum class Weather(val code: Int) {
        NONE(0), CLEAR(1), CLOUDY(2), FOG(3), SHOWERS(4), THUNDER(5), RAIN(6),
        SNOW(7), SLEET(8), HOT(9), COLD(10), WINDY(11)
    }

    /**
     * Status / clock frame ("?3"), B-series layout (Gixxer family). The Suzuki
     * app sends it about every 2 s while connected.
     *
     *   [2..3]   "1N"
     *   [4..6]   speed-alert limit "080", or FF FF FF when off
     *   [7]      phone signal bars '1'..'3', or 0x00 for none
     *   [8..13]  clock "hhmmss" (12-hour), FF x6 if unknown
     *   [14]     'Y' clears the SMS indicator, else 'N'
     *   [15]     'Y' clears the missed-call indicator, else 'N'
     *   [16..20] 0xFF
     *   [21]     [Weather] code
     *   [22]     temperature as 115 + degrees Fahrenheit (0 = none)
     *   [23]     1
     *   [24..27] 0xFF
     */
    fun status(
        clock: LocalTime?,
        speedAlertKmh: Int = 0,
        signalBars: Int = 3,
        clearSms: Boolean = false,
        clearMissedCall: Boolean = false,
        weather: Weather = Weather.NONE,
        tempCelsius: Double? = null,
        mode: Checksum = Checksum.SUM,
    ): ByteArray {
        val f = newFrame('3')
        f.putAscii(2, "1N")
        if (speedAlertKmh in 1..999) f.putAscii(4, "%03d".format(speedAlertKmh)) else f.fill(4, 6, FF)
        f[7] = if (signalBars <= 0) 0 else ('0' + signalBars.coerceAtMost(3)).code.toByte()
        if (clock == null) {
            f.fill(8, 13, FF)
        } else {
            val h12 = (clock.hour % 12).let { if (it == 0) 12 else it }
            f.putAscii(8, "%02d%02d%02d".format(h12, clock.minute, clock.second))
        }
        f[14] = (if (clearSms) 'Y' else 'N').code.toByte()
        f[15] = (if (clearMissedCall) 'Y' else 'N').code.toByte()
        f.fill(16, 20, FF)
        f[21] = weather.code.toByte()
        f[22] = if (tempCelsius == null) 0 else
            (115 + Math.ceil(tempCelsius * 9.0 / 5.0 + 32.0)).toInt().coerceIn(0, 255).toByte()
        f[23] = 1
        f.fill(24, 27, FF)
        return seal(f, mode)
    }

    /**
     * Incoming call frame ("?2").
     *
     *   [2..21]  caller name or number, ASCII, NUL padded ('+' removed)
     *   [22]     'N' phone call, 'W' WhatsApp call
     *   [23]     '1' ringing, '2' ended / answered
     *   [24..27] 0xFF
     */
    fun call(caller: String, ringing: Boolean, whatsapp: Boolean = false, mode: Checksum = Checksum.SUM): ByteArray {
        val f = newFrame('2')
        f.putAscii(2, asciiOnly(caller.replace("+", "")).take(20))
        f[22] = (if (whatsapp) 'W' else 'N').code.toByte()
        f[23] = (if (ringing) '1' else '2').code.toByte()
        f.fill(24, 27, FF)
        return seal(f, mode)
    }

    /**
     * Picks the checksum flavour from the cluster's BLE name, the way the
     * Suzuki app does: names look like "SBM21xxxxxxx", and an 'A' in the
     * second position means an A-series scooter cluster.
     */
    fun checksumFor(clusterName: String?): Checksum =
        if (clusterName != null && clusterName.length > 1 && clusterName[1] == 'A') Checksum.INVERTED else Checksum.SUM

    /** Human-readable model guess from the BLE name, same table as the Suzuki app. */
    fun modelFor(clusterName: String?): String {
        val n = clusterName ?: return "Unknown"
        if (n.length < 5) return "Unknown"
        return when (n.substring(1, 5)) {
            "AS01", "AS02" -> "Access 125"
            "AS11", "AS12" -> "Burgman Street"
            "BS21" -> "Avenis"
            "BS31" -> "Burgman Street EX"
            "BM01" -> "V-Strom SX"
            "BM11" -> "Gixxer / Gixxer SF"
            "BM21" -> "Gixxer 250 / SF 250 / FFV"
            else -> "Suzuki ($n)"
        }
    }

    /** True for names the Suzuki app lists as clusters. */
    fun looksLikeCluster(name: String?): Boolean =
        name != null && name.length == 12 && (name.contains("SA") || name.contains("SB") || name.contains("SC"))

    fun hex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
