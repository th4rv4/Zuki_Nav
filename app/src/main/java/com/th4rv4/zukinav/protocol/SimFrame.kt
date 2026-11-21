package com.th4rv4.zukinav.protocol

/**
 * A frame only Zuki Dash Sim sends (type 'Z'), never a real dash: the
 * simulated ride's time, how much faster than real it runs, and a made-up
 * position. Same 30-byte shape as the dash's frames:
 *
 *   [0]      0xA5
 *   [1]      'Z'
 *   [2..9]   ride time, ms since 1970, big-endian
 *   [10..11] speed-up × 10 (10 = real time)
 *   [12..15] latitude × 10^7, signed
 *   [16..19] longitude × 10^7, signed
 *   [20]     1 when the position is set
 *   [21..22] road speed, km/h
 *   [23]     bit 0: ignition on, bit 1: engine running
 *   [24..25] heading, whole degrees (0 = north)
 *   [28]     checksum over bytes 1..27
 *   [29]     0x7F
 */
data class SimFrame(
    val rideTime: Long,
    val speedUp: Double,
    val lat: Double?,
    val lng: Double?,
    val speedKmh: Int,
    val ignitionOn: Boolean = true,
    val engineOn: Boolean = true,
    val headingDeg: Int = 0,
) {
    fun encode(): ByteArray {
        val f = ByteArray(SuzukiFrames.FRAME_SIZE)
        f[0] = SuzukiFrames.START
        f[1] = 'Z'.code.toByte()
        putLong(f, 2, rideTime)
        putShort(f, 10, (speedUp * 10).toInt().coerceIn(10, 65_535))
        if (lat != null && lng != null) {
            putInt(f, 12, Math.round(lat * 1e7).toInt())
            putInt(f, 16, Math.round(lng * 1e7).toInt())
            f[20] = 1
        }
        putShort(f, 21, speedKmh.coerceIn(0, 999))
        f[23] = ((if (ignitionOn) 1 else 0) or (if (engineOn) 2 else 0)).toByte()
        putShort(f, 24, ((headingDeg % 360) + 360) % 360)
        f[28] = sum(f)
        f[29] = SuzukiFrames.END
        return f
    }

    companion object {
        /** The point [km] further along [headingDeg] from (lat, lng). Good enough for short steps. */
        fun move(lat: Double, lng: Double, headingDeg: Double, km: Double): Pair<Double, Double> {
            val h = Math.toRadians(headingDeg)
            val dLat = km * Math.cos(h) / 111.32
            val dLng = km * Math.sin(h) / (111.32 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            return (lat + dLat) to (lng + dLng)
        }

        fun parse(f: ByteArray): SimFrame? {
            if (f.size != SuzukiFrames.FRAME_SIZE || f[0] != SuzukiFrames.START || f[1] != 'Z'.code.toByte() ||
                f[29] != SuzukiFrames.END || f[28] != sum(f)
            ) {
                return null
            }
            val hasPos = f[20].toInt() == 1
            return SimFrame(
                rideTime = getLong(f, 2),
                speedUp = getShort(f, 10) / 10.0,
                lat = if (hasPos) getInt(f, 12) / 1e7 else null,
                lng = if (hasPos) getInt(f, 16) / 1e7 else null,
                speedKmh = getShort(f, 21),
                ignitionOn = f[23].toInt() and 1 != 0,
                engineOn = f[23].toInt() and 2 != 0,
                headingDeg = getShort(f, 24),
            )
        }

        private fun sum(f: ByteArray): Byte {
            var s = 0
            for (i in 1..27) s += f[i].toInt() and 0xFF
            return (s and 0xFF).toByte()
        }

        private fun putLong(f: ByteArray, at: Int, v: Long) { for (i in 0 until 8) f[at + i] = (v shr (56 - 8 * i)).toByte() }
        private fun putInt(f: ByteArray, at: Int, v: Int) { for (i in 0 until 4) f[at + i] = (v shr (24 - 8 * i)).toByte() }
        private fun putShort(f: ByteArray, at: Int, v: Int) { f[at] = (v shr 8).toByte(); f[at + 1] = v.toByte() }
        private fun getLong(f: ByteArray, at: Int): Long { var v = 0L; for (i in 0 until 8) v = (v shl 8) or (f[at + i].toLong() and 0xFF); return v }
        private fun getInt(f: ByteArray, at: Int): Int { var v = 0; for (i in 0 until 4) v = (v shl 8) or (f[at + i].toInt() and 0xFF); return v }
        private fun getShort(f: ByteArray, at: Int): Int = ((f[at].toInt() and 0xFF) shl 8) or (f[at + 1].toInt() and 0xFF)
    }
}
