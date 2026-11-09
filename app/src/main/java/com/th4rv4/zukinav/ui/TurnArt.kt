package com.th4rv4.zukinav.ui

import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.protocol.ClusterIcon

/**
 * Picture for each turn on the in-app guidance card. Right-hand variants are
 * drawn; left-hand ones are the same picture mirrored.
 */
object TurnArt {

    data class Art(val res: Int, val mirrored: Boolean)

    fun of(icon: ClusterIcon?): Art {
        val name = icon?.name ?: return Art(R.drawable.ic_turn_straight, false)
        val left = name.contains("LEFT")
        return when {
            icon == ClusterIcon.DESTINATION -> Art(R.drawable.ic_turn_destination, false)
            name.startsWith("RAB_") || icon == ClusterIcon.ENTER_ROUNDABOUT -> Art(R.drawable.ic_turn_roundabout, false)
            icon == ClusterIcon.U_TURN -> Art(R.drawable.ic_turn_uturn_right, false)
            icon == ClusterIcon.U_TURN_RHT -> Art(R.drawable.ic_turn_uturn_right, true)
            name.startsWith("SHARP_") || name.startsWith("FORK_SHARP_") -> Art(R.drawable.ic_turn_sharp_right, left)
            name.startsWith("SLIGHT_") || name.startsWith("BEAR_") ||
                name.startsWith("OFF_RAMP_") || icon == ClusterIcon.TAKE_RAMP -> Art(R.drawable.ic_turn_slight_right, left)
            name.startsWith("KEEP_") -> Art(R.drawable.ic_turn_keep_right, left)
            name.startsWith("TURN_") || name.startsWith("END_OF_ROAD_") -> Art(R.drawable.ic_turn_right, left)
            else -> Art(R.drawable.ic_turn_straight, false)
        }
    }
}
