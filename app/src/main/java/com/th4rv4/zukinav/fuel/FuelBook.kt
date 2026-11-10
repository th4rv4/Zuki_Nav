package com.th4rv4.zukinav.fuel

import kotlin.math.max

/**
 * A refill: the fuel bars went up (or the rider added one by hand).
 * [litres] and [amount] are what the rider typed in; both may be missing.
 */
data class Refill(
    /** When it was noticed, in ms; also the refill's id. */
    val at: Long,
    val odoKm: Int,
    /** Bars before and after; -1 when added by hand. */
    val barsBefore: Int,
    val barsAfter: Int,
    val litres: Double? = null,
    /** True when [litres] was worked out from [amount] and an earlier price. */
    val litresEstimated: Boolean = false,
    /** Amount paid, in rupees. */
    val amount: Double? = null,
    /** Filled to the top. */
    val full: Boolean = false,
    /** The rider saved or skipped the prompt. */
    val answered: Boolean = false,
) {
    val id: Long get() = at
    val manual: Boolean get() = barsBefore < 0
}

/** Km ridden on one fuel bar, summed over every time it was seen from start to end. */
data class BarStat(val km: Double = 0.0, val samples: Int = 0) {
    val average: Double? get() = if (samples > 0) km / samples else null
    fun add(sampleKm: Double) = BarStat(km + sampleKm, samples + 1)
}

/**
 * Everything the fuel tracker remembers. Range is learned from [bars]: the
 * km ridden while the gauge showed each bar. [segBar] is the bar being
 * watched now, since [segStartKm]; [segClean] is false when the start of
 * that bar was not seen (the app was not connected when it changed).
 */
data class FuelState(
    val refills: List<Refill> = emptyList(),
    val bars: List<BarStat> = List(FuelBook.MAX_BARS + 1) { BarStat() },
    val gaugeBars: Int? = null,
    val lastOdoKm: Int? = null,
    val segBar: Int? = null,
    val segStartKm: Int = 0,
    val segClean: Boolean = false,
    val pendingBars: Int? = null,
    val pendingCount: Int = 0,
)

/** Tank size, reserve and a starting mileage for a model; see [FuelBook.specFor]. */
data class FuelSpec(val tankLitres: Double, val reserveLitres: Double, val defaultKmpl: Double)

data class Range(val km: Int, val estimate: Boolean)

data class Mileage(
    /** Average km/l over every full tank to full tank stretch with litres known. */
    val average: Double?,
    /** The latest such stretch. */
    val last: Double?,
    /** Each stretch, oldest first. */
    val perTank: List<Double>,
    /** Rupees per km, from stretches where every refill has an amount. */
    val costPerKm: Double?,
)

/**
 * Fuel tracking worked out from the fuel bars and the ODO the dash sends.
 * Pure logic; [FuelStore] keeps the state and calls in here.
 */
object FuelBook {

    const val MAX_BARS = 6

    /** A new gauge level must show this many readings in a row (about 15 s). */
    private const val STABLE_READINGS = 3

    /** A bar change seen within this many km of the previous reading counts as seen live. */
    private const val LIVE_GAP_KM = 1

    /** No real bar lasts longer than this; longer samples are ignored. */
    private const val MAX_BAR_KM = 400

    /**
     * Model defaults. Tank sizes are Suzuki's; reserves are the amount left
     * when the last bar blinks, as listed in published specs. All can be
     * changed in Settings > Fuel. Electric models return null.
     */
    fun specFor(modelName: String): FuelSpec? {
        val n = modelName.lowercase()
        return when {
            n.contains("e-access") -> null
            n.contains("v-strom") -> FuelSpec(12.0, 2.4, 35.0)
            n.contains("250") -> FuelSpec(12.0, 2.0, 35.0)
            n.contains("gixxer") -> FuelSpec(12.0, 2.4, 45.0)
            n.contains("burgman") -> FuelSpec(5.5, 1.0, 50.0)
            n.contains("avenis") -> FuelSpec(5.2, 1.0, 50.0)
            n.contains("access") -> FuelSpec(5.0, 1.0, 50.0)
            else -> FuelSpec(12.0, 2.0, 40.0)
        }
    }

    /**
     * Feeds one reading from the dash. Returns the new state and, when the
     * bars went up enough to be a refill, that refill (already added).
     */
    fun onReading(s: FuelState, odoKm: Int, bars: Int, now: Long): Pair<FuelState, Refill?> {
        val shown = s.gaugeBars
        val lastOdo = s.lastOdoKm
        if (shown == null || lastOdo == null) {
            return s.copy(gaugeBars = bars, lastOdoKm = odoKm, segBar = bars, segStartKm = odoKm, segClean = false) to null
        }
        val live = odoKm - lastOdo <= LIVE_GAP_KM
        if (bars == shown) {
            return s.copy(lastOdoKm = odoKm, pendingBars = null, pendingCount = 0) to null
        }
        // While connected, a new level has to hold for a few readings: the
        // gauge can flicker on a slope. After a gap it is taken at once.
        val count = if (s.pendingBars == bars) s.pendingCount + 1 else 1
        if (live && count < STABLE_READINGS) {
            // Keep the ODO of the last steady reading, so the change is placed
            // where it started.
            return s.copy(pendingBars = bars, pendingCount = count) to null
        }
        val base = s.copy(lastOdoKm = odoKm, pendingBars = null, pendingCount = 0)

        if (bars > shown) {
            val refill = bars - shown >= 2 || bars == MAX_BARS
            if (!refill) return base to null // A single bar up: gauge noise.
            val r = Refill(at = now, odoKm = odoKm, barsBefore = shown, barsAfter = bars)
            return base.copy(
                refills = listOf(r) + s.refills,
                gaugeBars = bars,
                segBar = bars,
                segStartKm = odoKm,
                segClean = live,
            ) to r
        }

        // Bars went down.
        var learned = s.bars
        val km = odoKm - s.segStartKm
        if (live && shown - bars == 1 && s.segClean && s.segBar == shown && km in 1..MAX_BAR_KM) {
            learned = learned.toMutableList().also { it[shown] = it[shown].add(km.toDouble()) }
        }
        return base.copy(
            bars = learned,
            gaugeBars = bars,
            segBar = bars,
            segStartKm = odoKm,
            segClean = live && shown - bars == 1,
        ) to null
    }

    /** Km/l from full tank to full tank, counting every refill in between. */
    fun mileage(refills: List<Refill>): Mileage {
        val list = refills.sortedBy { it.at }
        val perTank = ArrayList<Double>()
        var totalKm = 0.0
        var totalLitres = 0.0
        var costKm = 0.0
        var costAmount = 0.0
        var anchor: Refill? = null
        var litres = 0.0
        var amount = 0.0
        var exact = true
        var allPaid = true
        for (r in list) {
            if (anchor != null) {
                val l = r.litres
                if (l == null || r.litresEstimated) exact = false else litres += l
                val a = r.amount
                if (a == null) allPaid = false else amount += a
                if (r.full) {
                    val km = (r.odoKm - anchor.odoKm).toDouble()
                    if (exact && litres > 0 && km > 0) {
                        perTank += km / litres
                        totalKm += km
                        totalLitres += litres
                        if (allPaid && amount > 0) {
                            costKm += km
                            costAmount += amount
                        }
                    }
                }
            }
            if (r.full) {
                anchor = r
                litres = 0.0
                amount = 0.0
                exact = true
                allPaid = true
            }
        }
        return Mileage(
            average = if (totalLitres > 0) totalKm / totalLitres else null,
            last = perTank.lastOrNull(),
            perTank = perTank,
            costPerKm = if (costKm > 0) costAmount / costKm else null,
        )
    }

    /**
     * Litres for a refill where only the amount was typed in, from the
     * price per litre of the latest earlier refill that had both.
     */
    fun litresFromAmount(refills: List<Refill>, amount: Double, before: Long): Double? {
        val ref = refills.filter { it.at < before && !it.litresEstimated && (it.litres ?: 0.0) > 0 && (it.amount ?: 0.0) > 0 }
            .maxByOrNull { it.at } ?: return null
        val perLitre = ref.amount!! / ref.litres!!
        return amount / perLitre
    }

    /** Km a full bar [b] lasts: learned if seen, else from the tank size. */
    fun kmForBar(s: FuelState, b: Int, spec: FuelSpec, kmpl: Double): Double {
        val learned = if (b in 2..MAX_BARS) s.bars[b].average else null
        if (learned != null) return learned
        val litres = if (b <= 1) spec.reserveLitres else (spec.tankLitres - spec.reserveLitres) / (MAX_BARS - 1)
        return litres * kmpl
    }

    /**
     * Km left before the tank is empty. [estimate] is true while it still
     * leans on the tank size instead of what the bike has shown.
     */
    fun range(s: FuelState, odoKm: Int, spec: FuelSpec, mileage: Mileage): Range? {
        val bars = s.gaugeBars ?: return null
        if (bars <= 0) return Range(0, true)
        val kmpl = mileage.average ?: spec.defaultKmpl
        val current = kmForBar(s, bars, spec, kmpl)
        val used = if (s.segClean && s.segBar == bars) (odoKm - s.segStartKm).toDouble() else current / 2
        var km = max(current - used, current * 0.1)
        for (b in 1 until bars) km += kmForBar(s, b, spec, kmpl)
        val estimate = mileage.average == null || (2..bars).any { s.bars[it].samples == 0 }
        return Range(km.toInt(), estimate)
    }
}
