package com.th4rv4.suzukinav.protocol

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A distance as the cluster wants it: exactly four ASCII characters plus a
 * unit byte, 'M' for metres or 'K' for kilometres.
 *
 * Reproduces what Suzuki Ride Connect produces from a Mappls distance string
 * (see `d2.C()`), including its quirks:
 *  - up to 999 m  -> "0350" M
 *  - 1 to 9.99 km -> "02.5" K (two decimals rounded half-even, then the
 *                    second decimal is dropped, not rounded)
 *  - 10 to 99 km  -> "12.3" K
 *  - 100 km+      -> "0123" K
 */
data class DashDistance(val digits: String, val unit: Char) {
    init {
        require(digits.length == 4) { "digits must be 4 chars: '$digits'" }
        require(unit == 'M' || unit == 'K') { "unit must be M or K" }
    }

    companion object {
        val ZERO = DashDistance("0000", 'M')

        /**
         * @param roundDownTo10 the Suzuki app floors the distance to the next
         *   turn to a multiple of 10 m before formatting; the remaining trip
         *   distance is not floored.
         */
        fun fromMeters(meters: Double, roundDownTo10: Boolean = false): DashDistance {
            var m = if (meters.isNaN() || meters < 0) 0.0 else meters
            if (roundDownTo10 && m > 0) m = Math.floor(m / 10.0) * 10.0

            // Mappls switches to km above 999 m.
            if (m <= 999.0) {
                val whole = (m + 0.5).toInt().coerceIn(0, 999)
                return DashDistance(whole.toString().padStart(4, '0'), 'M')
            }
            // Mappls divides in 32-bit float and DecimalFormat rounds that
            // float's exact value, so 10.15 km becomes 10.1. Do the same.
            val km = BigDecimal((m.toFloat() / 1000f).toDouble())
            if (m <= 9990.0) {
                // "0.##" in Mappls, then Suzuki keeps one decimal by truncation.
                val two = km.setScale(2, RoundingMode.HALF_EVEN)
                val oneTruncated = two.setScale(1, RoundingMode.DOWN)
                if (oneTruncated >= BigDecimal.TEN) {
                    return DashDistance("10.0", 'K')
                }
                return DashDistance("0" + oneTruncated.toPlainString(), 'K')
            }
            if (m < 100_000.0) {
                val one = km.setScale(1, RoundingMode.HALF_EVEN)
                if (one >= BigDecimal(100)) return DashDistance("0100", 'K')
                return DashDistance(one.toPlainString().padStart(4, '0'), 'K')
            }
            val whole = (m.toFloat() / 1000f + 0.5f).toLong().coerceAtMost(9999L)
            return DashDistance(whole.toString().padStart(4, '0'), 'K')
        }
    }
}
