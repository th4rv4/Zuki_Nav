package com.th4rv4.zukinav.nav

import com.th4rv4.zukinav.protocol.ClusterIcon

/**
 * Shape fingerprint of a maneuver arrow, computed from raw ARGB pixels so it
 * runs on the JVM as well as on the phone.
 *
 * The arrow's shape lives in the alpha channel (Maps draws a white or a black
 * glyph on transparency), so colour is ignored. The glyph is cropped to its
 * bounding box and resampled, aspect preserved, into a [SIZE]x[SIZE] grid.
 * Cropping makes the notification's large icon and the drawable rendered from
 * the Maps package line up even though they are padded differently.
 */
class IconSignature private constructor(val cells: FloatArray) {

    /** 0 = identical, 1 = completely different. */
    fun distance(other: IconSignature): Float {
        var inter = 0f
        var union = 0f
        for (i in cells.indices) {
            val a = cells[i]
            val b = other.cells[i]
            inter += minOf(a, b)
            union += maxOf(a, b)
        }
        return if (union <= 0f) 1f else 1f - inter / union
    }

    companion object {
        const val SIZE = 32
        private const val INK_ALPHA = 48

        /** Null for an empty image or something that is clearly not a glyph. */
        fun of(argb: IntArray, width: Int, height: Int): IconSignature? {
            if (width <= 0 || height <= 0 || argb.size < width * height) return null
            var minX = width; var minY = height; var maxX = -1; var maxY = -1
            var opaque = 0
            var satSum = 0.0
            for (y in 0 until height) for (x in 0 until width) {
                val p = argb[y * width + x]
                val a = (p ushr 24) and 0xFF
                if (a < INK_ALPHA) continue
                opaque++
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                satSum += (maxOf(r, g, b) - minOf(r, g, b)) / 255.0
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
            if (maxX < 0) return null
            // Maps also posts photos and coloured badges through the same icon
            // slot. A maneuver glyph is monochrome and does not fill the tile.
            // Thresholds from KTM-Nav-GEN3's measurements on real Maps icons.
            if (opaque.toDouble() / (width * height) > 0.6) return null
            if (satSum / opaque > 0.05) return null

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val side = maxOf(bw, bh).toFloat()
            // Centre the bounding box in a square of side `side`.
            val ox = minX - (side - bw) / 2f
            val oy = minY - (side - bh) / 2f
            val cells = FloatArray(SIZE * SIZE)
            val step = side / SIZE
            // Area-average sampling with a fixed 3x3 sub-grid per cell.
            for (cy in 0 until SIZE) for (cx in 0 until SIZE) {
                var acc = 0f
                for (sy in 0 until 3) for (sx in 0 until 3) {
                    val px = (ox + (cx + (sx + 0.5f) / 3f) * step).toInt()
                    val py = (oy + (cy + (sy + 0.5f) / 3f) * step).toInt()
                    if (px in 0 until width && py in 0 until height) {
                        acc += ((argb[py * width + px] ushr 24) and 0xFF) / 255f
                    }
                }
                cells[cy * SIZE + cx] = acc / 9f
            }
            return IconSignature(cells)
        }
    }
}

/**
 * Nearest-template classifier. Templates are rendered from Google Maps' own
 * `maneuver_*` drawables on the phone (see MapsIconTemplates), so nothing of
 * Google's is bundled in this app.
 */
class IconMatcher(templates: List<Pair<String, IconSignature>>) {

    /** Templates with look-alikes for a different dash icon removed, see [dedupe]. */
    private val templates: List<Pair<String, IconSignature>> = dedupe(templates)

    data class Match(val drawableName: String, val icon: ClusterIcon, val distance: Float, val margin: Float)

    val size: Int get() = templates.size

    fun match(sig: IconSignature): Match? {
        if (templates.isEmpty()) return null
        var best: Pair<String, Float>? = null
        var bestIcon: ClusterIcon? = null
        var second = 1f
        for ((name, t) in templates) {
            val icon = MapsManeuvers.toCluster(name) ?: continue
            val d = sig.distance(t)
            val b = best
            if (b == null || d < b.second) {
                // The runner-up only counts if it would have meant a different icon.
                if (b != null && bestIcon != icon) second = minOf(second, b.second)
                best = name to d
                bestIcon = icon
            } else if (icon != bestIcon) {
                second = minOf(second, d)
            }
        }
        val b = best ?: return null
        val icon = bestIcon ?: return null
        return Match(b.first, icon, b.second, second - b.second)
    }

    companion object {
        /** Accept a match only if it is close and clearly better than the next different icon. */
        const val MAX_DISTANCE = 0.30f
        const val MIN_MARGIN = 0.04f

        /** Two templates closer than this are the same picture. */
        const val SAME_PICTURE = 0.02f

        /**
         * Google Maps reuses one picture for several maneuvers: the
         * `turn_normal_right` and `on_ramp_normal_right` arrows render
         * identically. Kept both, they tie on every right turn and the
         * matcher refuses to answer (margin 0). Of each group of identical
         * pictures, keep the maneuver that is far more common on Indian
         * roads, by [priority].
         */
        fun dedupe(templates: List<Pair<String, IconSignature>>): List<Pair<String, IconSignature>> {
            val sorted = templates.sortedBy { priority(it.first) }
            val kept = ArrayList<Pair<String, IconSignature>>()
            for (t in sorted) {
                val icon = MapsManeuvers.toCluster(t.first)
                val clash = kept.any { k ->
                    MapsManeuvers.toCluster(k.first) != icon && k.second.distance(t.second) < SAME_PICTURE
                }
                if (!clash) kept += t
            }
            return kept
        }

        /** Lower wins. Plain turns first, ramps last. */
        fun priority(name: String): Int {
            val n = name.removePrefix("maneuver_")
            return when {
                n.startsWith("turn_") -> 0
                n == "straight" || n == "depart" -> 1
                n.startsWith("u_turn") -> 2
                n.startsWith("destination") -> 3
                n.startsWith("roundabout_") -> 4
                n.startsWith("keep_") || n.startsWith("fork_") -> 5
                n.startsWith("merge") || n == "name_change" -> 6
                n.startsWith("off_ramp") -> 7
                n.startsWith("on_ramp") -> 8
                else -> 9
            }
        }
    }
}

/** Google Maps maneuver drawable names and what they mean on the Suzuki dash. */
object MapsManeuvers {

    val NAMES: List<String> = listOf(
        "maneuver_depart", "maneuver_destination", "maneuver_destination_left",
        "maneuver_destination_right", "maneuver_destination_straight",
        "maneuver_fork_left", "maneuver_fork_right", "maneuver_keep_left", "maneuver_keep_right",
        "maneuver_merge", "maneuver_merge_left", "maneuver_merge_right", "maneuver_name_change",
        "maneuver_off_ramp_keep_left", "maneuver_off_ramp_keep_right",
        "maneuver_off_ramp_normal_left", "maneuver_off_ramp_normal_right",
        "maneuver_off_ramp_sharp_left", "maneuver_off_ramp_sharp_right",
        "maneuver_off_ramp_slight_left", "maneuver_off_ramp_slight_right",
        "maneuver_off_ramp_u_turn_left", "maneuver_off_ramp_u_turn_right",
        "maneuver_on_ramp_keep_left", "maneuver_on_ramp_keep_right",
        "maneuver_on_ramp_normal_left", "maneuver_on_ramp_normal_right",
        "maneuver_on_ramp_sharp_left", "maneuver_on_ramp_sharp_right",
        "maneuver_on_ramp_slight_left", "maneuver_on_ramp_slight_right",
        "maneuver_on_ramp_u_turn_left", "maneuver_on_ramp_u_turn_right",
        "maneuver_roundabout_enter_and_exit_ccw", "maneuver_roundabout_enter_and_exit_ccw_normal_left",
        "maneuver_roundabout_enter_and_exit_ccw_normal_right", "maneuver_roundabout_enter_and_exit_ccw_sharp_left",
        "maneuver_roundabout_enter_and_exit_ccw_sharp_right", "maneuver_roundabout_enter_and_exit_ccw_slight_left",
        "maneuver_roundabout_enter_and_exit_ccw_slight_right", "maneuver_roundabout_enter_and_exit_ccw_straight",
        "maneuver_roundabout_enter_and_exit_ccw_u_turn",
        "maneuver_roundabout_enter_and_exit_cw", "maneuver_roundabout_enter_and_exit_cw_normal_left",
        "maneuver_roundabout_enter_and_exit_cw_normal_right", "maneuver_roundabout_enter_and_exit_cw_sharp_left",
        "maneuver_roundabout_enter_and_exit_cw_sharp_right", "maneuver_roundabout_enter_and_exit_cw_slight_left",
        "maneuver_roundabout_enter_and_exit_cw_slight_right", "maneuver_roundabout_enter_and_exit_cw_straight",
        "maneuver_roundabout_enter_and_exit_cw_u_turn",
        "maneuver_roundabout_enter_ccw", "maneuver_roundabout_enter_cw",
        "maneuver_roundabout_exit_ccw", "maneuver_roundabout_exit_cw",
        "maneuver_straight", "maneuver_turn_normal_left", "maneuver_turn_normal_right",
        "maneuver_turn_sharp_left", "maneuver_turn_sharp_right",
        "maneuver_turn_slight_left", "maneuver_turn_slight_right",
        "maneuver_u_turn_left", "maneuver_u_turn_right",
    )

    /**
     * Clockwise roundabouts (left-hand traffic, India) are travelled
     * left -> straight -> right, so a sharp-left exit is the shortest arc and
     * a sharp-right exit the longest. Counter-clockwise is the mirror image.
     */
    private val CW_ARC = mapOf(
        "sharp_left" to 40f, "normal_left" to 90f, "slight_left" to 130f, "straight" to 180f,
        "slight_right" to 220f, "normal_right" to 270f, "sharp_right" to 310f, "u_turn" to 350f,
    )
    private val CCW_ARC = mapOf(
        "sharp_right" to 40f, "normal_right" to 90f, "slight_right" to 130f, "straight" to 180f,
        "slight_left" to 220f, "normal_left" to 270f, "sharp_left" to 310f, "u_turn" to 350f,
    )

    fun toCluster(name: String): ClusterIcon? {
        val n = name.removePrefix("maneuver_")
        if (n.startsWith("roundabout_enter_and_exit_")) {
            val rest = n.removePrefix("roundabout_enter_and_exit_")
            val cw = rest.startsWith("cw")
            val dir = rest.removePrefix(if (cw) "cw" else "ccw").removePrefix("_")
            if (dir.isEmpty()) return ClusterIcon.ENTER_ROUNDABOUT
            val arc = (if (cw) CW_ARC else CCW_ARC)[dir] ?: return ClusterIcon.ENTER_ROUNDABOUT
            return ClusterIcon.roundabout(arc, leftHandTraffic = cw)
        }
        if (n.startsWith("roundabout_")) return ClusterIcon.ENTER_ROUNDABOUT
        if (n.startsWith("destination")) return ClusterIcon.DESTINATION
        if (n.startsWith("off_ramp_") || n.startsWith("on_ramp_")) {
            return when {
                n.endsWith("u_turn_left") -> ClusterIcon.U_TURN_RHT
                n.endsWith("u_turn_right") -> ClusterIcon.U_TURN
                n.endsWith("left") -> ClusterIcon.OFF_RAMP_LEFT
                n.endsWith("right") -> ClusterIcon.OFF_RAMP_RIGHT
                else -> ClusterIcon.TAKE_RAMP
            }
        }
        return when (n) {
            "depart", "straight" -> ClusterIcon.STRAIGHT
            "name_change" -> ClusterIcon.CONTINUE_STRAIGHT
            "fork_left", "keep_left" -> ClusterIcon.KEEP_LEFT
            "fork_right", "keep_right" -> ClusterIcon.KEEP_RIGHT
            "merge" -> ClusterIcon.CONTINUE_STRAIGHT
            "merge_left" -> ClusterIcon.BEAR_LEFT
            "merge_right" -> ClusterIcon.BEAR_RIGHT
            "turn_normal_left" -> ClusterIcon.TURN_LEFT
            "turn_normal_right" -> ClusterIcon.TURN_RIGHT
            "turn_sharp_left" -> ClusterIcon.SHARP_LEFT
            "turn_sharp_right" -> ClusterIcon.SHARP_RIGHT
            "turn_slight_left" -> ClusterIcon.SLIGHT_LEFT
            "turn_slight_right" -> ClusterIcon.SLIGHT_RIGHT
            // A U-turn that swings right is the left-hand-traffic one.
            "u_turn_right" -> ClusterIcon.U_TURN
            "u_turn_left" -> ClusterIcon.U_TURN_RHT
            else -> null
        }
    }
}
