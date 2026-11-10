package com.th4rv4.zukinav.protocol

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
     *
     * @param etaLeadingSpace C-series (TFT) clusters get a space instead of a
     *   leading '0' in the ETA, see [ClusterModel.etaLeadingSpace].
     */
    fun navigation(
        iconId: Int,
        toTurn: DashDistance,
        eta: String,
        remaining: DashDistance,
        status: NavStatus = NavStatus.OK,
        active: Boolean = true,
        mode: Checksum = Checksum.SUM,
        etaLeadingSpace: Boolean = false,
    ): ByteArray {
        require(eta.length == 6) { "eta must be 6 chars: '$eta'" }
        val f = newFrame('1')
        f[2] = (if (status.showsArrow) iconId else ClusterIcon.STATUS_PLACEHOLDER).toByte()
        f[3] = FF
        f.putAscii(4, toTurn.digits)
        f[8] = toTurn.unit.code.toByte()
        f.putAscii(9, eta)
        if (etaLeadingSpace && eta[0] == '0') f[9] = ' '.code.toByte()
        f.fill(15, 17, FF)
        f.putAscii(18, remaining.digits)
        f[22] = remaining.unit.code.toByte()
        f[23] = status.code.code.toByte()
        f[24] = (if (active) '1' else '0').code.toByte()
        f.fill(25, 27, FF)
        return seal(f, mode)
    }

    /**
     * A navigation frame with free text in the three number fields, for
     * finding out what the dash can show there (Dash Test > Text Test).
     * Text is upper-cased and cut or padded to 4 / 6 / 4 characters. A number
     * in the distance or remaining spot is padded with zeros in front, the
     * way navigation sends it ("50" -> "0050"); anything else is padded with
     * spaces, which the dash shows as '*'. The unit bytes stay 'M'.
     * Not used while navigating.
     */
    fun navigationText(
        iconId: Int,
        toTurn: String,
        eta: String,
        remaining: String,
        mode: Checksum = Checksum.SUM,
    ): ByteArray {
        fun field(s: String, n: Int) = asciiOnly(s.uppercase()).take(n).padEnd(n, ' ')
        fun number(s: String, n: Int): String {
            val t = asciiOnly(s.trim()).take(n)
            return if (t.isNotEmpty() && t.all { it.isDigit() || it == '.' }) t.padStart(n, '0') else field(s, n)
        }
        val f = newFrame('1')
        f[2] = iconId.toByte()
        f[3] = FF
        f.putAscii(4, number(toTurn, 4))
        f[8] = 'M'.code.toByte()
        f.putAscii(9, field(eta, 6))
        f.fill(15, 17, FF)
        f.putAscii(18, number(remaining, 4))
        f[22] = 'M'.code.toByte()
        f[23] = NavStatus.OK.code.code.toByte()
        f[24] = '1'.code.toByte()
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
     * Status / clock frame ("?3"). The Suzuki app sends it about every 2 s
     * while connected. B-series layout:
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
     *
     * The A-series layout (Access 125, Burgman Street) is the same up to byte
     * 15, except that byte 7 is always an ASCII digit ('0' for no signal),
     * and bytes 16..27 are all 0xFF: no weather or temperature.
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
        layout: ClusterModel.StatusLayout = ClusterModel.StatusLayout.B,
    ): ByteArray {
        require(layout != ClusterModel.StatusLayout.NONE) { "this cluster takes no status frame" }
        val a = layout == ClusterModel.StatusLayout.A
        val f = newFrame('3')
        f.putAscii(2, "1N")
        if (speedAlertKmh in 1..999) f.putAscii(4, "%03d".format(speedAlertKmh)) else f.fill(4, 6, FF)
        val bars = signalBars.coerceIn(0, 3)
        f[7] = if (bars == 0 && !a) 0 else ('0' + bars).code.toByte()
        if (clock == null) {
            f.fill(8, 13, FF)
        } else {
            val h12 = (clock.hour % 12).let { if (it == 0) 12 else it }
            f.putAscii(8, "%02d%02d%02d".format(h12, clock.minute, clock.second))
        }
        f[14] = (if (clearSms) 'Y' else 'N').code.toByte()
        f[15] = (if (clearMissedCall) 'Y' else 'N').code.toByte()
        if (a) {
            f.fill(16, 27, FF)
            return seal(f, mode)
        }
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
     * Missed call frame ("?4").
     *
     *   [2]      0xFF
     *   [3]      number of missed calls (raw byte)
     *   [4..23]  caller name or number, ASCII, NUL padded ('+' removed)
     *   [24]     'N' phone call, 'W' WhatsApp call
     *   [25..27] 0xFF
     */
    fun missedCall(caller: String, count: Int, whatsapp: Boolean = false, mode: Checksum = Checksum.SUM): ByteArray {
        val f = newFrame('4')
        f[2] = FF
        f[3] = count.coerceIn(0, 255).toByte()
        f.putAscii(4, asciiOnly(caller.replace("+", "")).take(20))
        f[24] = (if (whatsapp) 'W' else 'N').code.toByte()
        f.fill(25, 27, FF)
        return seal(f, mode)
    }

    /**
     * Message frame ("?5"): SMS or WhatsApp.
     *
     *   [2]      0xFF
     *   [3]      'N' for a new message
     *   [4]      number of unread messages of this kind (raw byte)
     *   [5..24]  sender, ASCII, NUL padded
     *   [25]     'N' SMS, 'W' WhatsApp
     *   [26..27] 0xFF
     */
    fun message(sender: String, count: Int, whatsapp: Boolean = false, mode: Checksum = Checksum.SUM): ByteArray {
        val f = newFrame('5')
        f[2] = FF
        f[3] = 'N'.code.toByte()
        f[4] = count.coerceIn(0, 255).toByte()
        f.putAscii(5, asciiOnly(sender.replace("+", "")).take(20))
        f[25] = (if (whatsapp) 'W' else 'N').code.toByte()
        f[26] = FF
        f[27] = FF
        return seal(f, mode)
    }

    fun hex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
