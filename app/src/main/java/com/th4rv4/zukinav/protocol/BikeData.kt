package com.th4rv4.zukinav.protocol

/**
 * What the dash reports about the bike. The dash sends it on its own every
 * 5 seconds while the link is up.
 */
data class BikeData(
    val speedKmh: Int,
    val odoKm: Int,
    val tripAKm: Double,
    val tripBKm: Double,
    /** Fuel gauge bars, 0 to 6, on petrol models. */
    val fuelBars: Int?,
    /** Battery charge in percent, on electric models. */
    val batteryPercent: Int?,
) {
    /** For saving the last reading: "odo;tripA;tripB;fuel;battery" (speed is not kept). */
    fun encode(): String = listOf(odoKm, tripAKm, tripBKm, fuelBars ?: "", batteryPercent ?: "").joinToString(";")

    companion object {
        const val MAX_FUEL_BARS = 6

        fun decode(s: String?): BikeData? {
            val p = s?.split(';') ?: return null
            if (p.size != 5) return null
            return BikeData(
                speedKmh = 0,
                odoKm = p[0].toIntOrNull() ?: return null,
                tripAKm = p[1].toDoubleOrNull() ?: return null,
                tripBKm = p[2].toDoubleOrNull() ?: return null,
                fuelBars = p[3].toIntOrNull(),
                batteryPercent = p[4].toIntOrNull(),
            )
        }

        /**
         * Reads the dash's "?7" frame, as Suzuki Ride Connect does:
         *
         *   [2..4]   road speed, km/h, 3 ASCII digits
         *   [5..10]  odometer, km, 6 ASCII digits
         *   [11..16] Trip A in 0.1 km, 6 ASCII digits
         *   [17..22] Trip B in 0.1 km, 6 ASCII digits
         *   [24]     fuel bars as '0'..'6'; on e-ACCESS, battery % + 64
         *   [25..27] fuel economy (not decoded)
         *
         * Returns null for anything else, or a frame that fails its checksum.
         */
        fun parse(frame: ByteArray): BikeData? {
            if (frame.size != 30 || frame[0] != SuzukiFrames.START || frame[1] != '7'.code.toByte() ||
                frame[29] != SuzukiFrames.END
            ) return null
            val c = frame[28]
            if (c != SuzukiFrames.checksum(frame, SuzukiFrames.Checksum.SUM) &&
                c != SuzukiFrames.checksum(frame, SuzukiFrames.Checksum.INVERTED)
            ) return null
            val speed = digits(frame, 2, 3) ?: return null
            val odo = digits(frame, 5, 6) ?: return null
            val tripA = digits(frame, 11, 6) ?: return null
            val tripB = digits(frame, 17, 6) ?: return null
            val f = frame[24].toInt() and 0xFF
            val bars = if (f in '0'.code..('0'.code + MAX_FUEL_BARS)) f - '0'.code else null
            val battery = if (bars == null && f >= 64) (f - 64).coerceAtMost(100) else null
            return BikeData(speed, odo, tripA / 10.0, tripB / 10.0, bars, battery)
        }

        private fun digits(f: ByteArray, at: Int, len: Int): Int? {
            var v = 0
            for (i in at until at + len) {
                val d = (f[i].toInt() and 0xFF) - '0'.code
                if (d !in 0..9) return null
                v = v * 10 + d
            }
            return v
        }
    }
}
