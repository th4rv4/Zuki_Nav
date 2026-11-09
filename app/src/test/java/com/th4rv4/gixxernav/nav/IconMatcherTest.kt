package com.th4rv4.gixxernav.nav

import com.th4rv4.gixxernav.protocol.ClusterIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconMatcherTest {

    /** Draws a thick L-shaped arrow (shaft up, head pointing left or right) at a given scale and offset. */
    private fun arrow(size: Int, scale: Float, ox: Int, oy: Int, right: Boolean, white: Boolean = true): IntArray {
        val px = IntArray(size * size)
        val ink = if (white) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        fun dot(x: Int, y: Int) {
            val xx = if (right) x else (40 - x)
            val sx = (ox + xx * scale).toInt(); val sy = (oy + y * scale).toInt()
            for (dy in 0..scale.toInt()) for (dx in 0..scale.toInt()) {
                val a = sx + dx; val b = sy + dy
                if (a in 0 until size && b in 0 until size) px[b * size + a] = ink
            }
        }
        for (y in 10..40) for (x in 14..20) dot(x, y)          // shaft
        for (y in 10..16) for (x in 14..32) dot(x, y)          // arm
        for (i in 0..8) for (y in 13 - i..13 + i) dot(32 + (8 - i), y) // head
        return px
    }

    @Test fun matchesAcrossScaleOffsetAndInk() {
        val templates = listOf(
            "maneuver_turn_normal_right" to IconSignature.of(arrow(128, 3f, 2, 0, right = true), 128, 128)!!,
            "maneuver_turn_normal_left" to IconSignature.of(arrow(128, 3f, 2, 0, right = false), 128, 128)!!,
        )
        val matcher = IconMatcher(templates)
        // Notification icon: smaller, offset, black ink.
        val sig = IconSignature.of(arrow(64, 1.2f, 9, 7, right = false, white = false), 64, 64)
        assertNotNull(sig)
        val m = matcher.match(sig!!)!!
        assertEquals(ClusterIcon.TURN_LEFT, m.icon)
        assertTrue("distance ${m.distance}", m.distance < IconMatcher.MAX_DISTANCE)
        assertTrue("margin ${m.margin}", m.margin > IconMatcher.MIN_MARGIN)
    }

    @Test fun rejectsPhotos() {
        val photo = IntArray(48 * 48) { i -> 0xFF000000.toInt() or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        assertNull(IconSignature.of(photo, 48, 48))
    }

    @Test fun drawableNames() {
        assertEquals(ClusterIcon.RAB_CW_90, MapsManeuvers.toCluster("maneuver_roundabout_enter_and_exit_cw_normal_left"))
        assertEquals(ClusterIcon.RAB_CW_270, MapsManeuvers.toCluster("maneuver_roundabout_enter_and_exit_cw_normal_right"))
        assertEquals(ClusterIcon.RAB_CCW_90, MapsManeuvers.toCluster("maneuver_roundabout_enter_and_exit_ccw_normal_right"))
        assertEquals(ClusterIcon.U_TURN, MapsManeuvers.toCluster("maneuver_u_turn_right"))
        assertEquals(ClusterIcon.OFF_RAMP_LEFT, MapsManeuvers.toCluster("maneuver_off_ramp_slight_left"))
        for (n in MapsManeuvers.NAMES) assertNotNull(n, MapsManeuvers.toCluster(n))
    }
}
