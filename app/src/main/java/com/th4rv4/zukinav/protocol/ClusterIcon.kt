package com.th4rv4.zukinav.protocol

/**
 * Turn-icon IDs understood by the Suzuki Ride Connect instrument cluster.
 *
 * The cluster byte is what goes into byte 2 of a navigation frame. The table
 * was recovered from Suzuki Ride Connect 8.0 (`d2.C()`), which converts Mappls
 * maneuver IDs into these cluster IDs. The meaning of each Mappls ID comes from
 * the `$N` suffixes in Mappls' own instruction templates
 * (`mappls-directions-en_short_instructions.json`) and from
 * `DirectionsUtils.getManeuverId()` for roundabouts.
 *
 * Every icon from 1 to 46 has been checked on a Gixxer SF 250 dash.
 */
enum class ClusterIcon(val id: Int, val label: String) {
    TURN_LEFT(1, "Turn Left"),
    SHARP_LEFT(2, "Sharp Left"),
    SLIGHT_LEFT(3, "Slight Left"),
    TURN_RIGHT(4, "Turn Right"),
    SHARP_RIGHT(5, "Sharp Right"),
    SLIGHT_RIGHT(6, "Slight Right"),
    /** U-turn for right-hand traffic (Mappls 6). */
    U_TURN_RHT(7, "U-Turn (Right-Hand Traffic)"),
    STRAIGHT(8, "Straight"),
    DESTINATION(9, "Destination"),
    OFF_RAMP_RIGHT(10, "Take Right Ramp"),
    /** Turn off the main road to the left (road continues straight). */
    BRANCH_LEFT(11, "Branch Left"),
    BRANCH_RIGHT(12, "Branch Right"),
    END_OF_ROAD_LEFT(13, "End Of Road, Left"),
    END_OF_ROAD_RIGHT(14, "End Of Road, Right"),
    // Compass arrows, used by the Suzuki app for "Head <direction>" steps.
    HEAD_SOUTHEAST(15, "Head Southeast"),
    HEAD_SOUTH(16, "Head South"),
    HEAD_SOUTHWEST(17, "Head Southwest"),
    HEAD_WEST(18, "Head West"),
    HEAD_NORTHWEST(19, "Head Northwest"),

    // Roundabout exits, left-hand traffic (India: clockwise). Bucketed by the
    // angle travelled round the roundabout: <=45, <=90, ... >270 degrees.
    RAB_CW_45(20, "Roundabout Exit 45°"),
    RAB_CW_90(21, "Roundabout Exit 90° (Left)"),
    RAB_CW_135(22, "Roundabout Exit 135°"),
    RAB_CW_180(23, "Roundabout Exit 180° (Straight)"),
    RAB_CW_225(24, "Roundabout Exit 225°"),
    RAB_CW_270(25, "Roundabout Exit 270° (Right)"),
    RAB_CW_315(26, "Roundabout Exit 315°+"),

    KEEP_LEFT(27, "Keep Left"),
    KEEP_RIGHT(28, "Keep Right"),
    FORK_SHARP_LEFT(29, "Fork Sharp Left"),
    FORK_SHARP_RIGHT(30, "Fork Sharp Right"),
    BEAR_LEFT(31, "Bear Left / Merge Left"),
    BEAR_RIGHT(32, "Bear Right / Merge Right"),
    CONTINUE_STRAIGHT(33, "Continue Straight"),
    CURVE_SHARP_LEFT(34, "Curve Sharp Left"),
    CURVE_RIGHT(35, "Curve Right"),
    CURVE_SHARP_RIGHT(36, "Curve Sharp Right"),
    CURVE_LEFT(37, "Curve Left"),
    TAKE_RAMP(38, "Take The Ramp"),
    /** U-turn for left-hand traffic (Mappls 41) - the one India uses. */
    U_TURN(39, "U-Turn"),
    HEAD_NORTH(40, "Head North"),
    HEAD_NORTHEAST(41, "Head Northeast"),
    HEAD_EAST(42, "Head East"),
    FERRY(43, "Ferry"),
    OFF_RAMP_LEFT(44, "Take Left Ramp"),
    ENTER_ROUNDABOUT(45, "Enter Roundabout"),

    // Roundabout exits, right-hand traffic (counter-clockwise). Sent as
    // selected by the Traffic Side setting. Not every dash draws them: on
    // the Gixxer SF 250, 46 is a "!" warning and 47 to 64 are blank.
    RAB_CCW_45(46, "Roundabout (RHT) Exit 45° / Warning (!)"),
    RAB_CCW_90(47, "Roundabout (RHT) Exit 90° (Right)"),
    RAB_CCW_135(48, "Roundabout (RHT) Exit 135°"),
    RAB_CCW_180(49, "Roundabout (RHT) Exit 180° (Straight)"),
    RAB_CCW_225(50, "Roundabout (RHT) Exit 225°"),
    RAB_CCW_270(51, "Roundabout (RHT) Exit 270° (Left)"),
    RAB_CCW_315(52, "Roundabout (RHT) Exit 315°+");

    companion object {
        /** The Suzuki app sends this alternately with the turn arrow to flash an alert. */
        const val ALERT_FLASH = 61

        /**
         * Shown by the Suzuki app instead of the turn arrow whenever the nav
         * status is not 1, 3 or 5 (GPS lost, no network, ...). Drawn as a
         * "!" on the Gixxer SF 250.
         */
        const val STATUS_PLACEHOLDER = 46

        fun fromId(id: Int): ClusterIcon? = entries.firstOrNull { it.id == id }

        /** Compass arrow for a direction word ("north", "south-west", ...), or null. */
        fun compass(direction: String): ClusterIcon? =
            when (direction.lowercase().replace("-", "").replace(" ", "")) {
                "north" -> HEAD_NORTH
                "northeast" -> HEAD_NORTHEAST
                "east" -> HEAD_EAST
                "southeast" -> HEAD_SOUTHEAST
                "south" -> HEAD_SOUTH
                "southwest" -> HEAD_SOUTHWEST
                "west" -> HEAD_WEST
                "northwest" -> HEAD_NORTHWEST
                else -> null
            }

        /** Clockwise angle of a compass arrow from north, or null for other icons. */
        fun compassDegrees(icon: ClusterIcon): Int? = when (icon) {
            HEAD_NORTH -> 0
            HEAD_NORTHEAST -> 45
            HEAD_EAST -> 90
            HEAD_SOUTHEAST -> 135
            HEAD_SOUTH -> 180
            HEAD_SOUTHWEST -> 225
            HEAD_WEST -> 270
            HEAD_NORTHWEST -> 315
            else -> null
        }

        /**
         * Roundabout exit by angle travelled, same buckets as Mappls'
         * `getManeuverId()` / `e.h()`.
         */
        fun roundabout(degrees: Float, leftHandTraffic: Boolean): ClusterIcon {
            val bucket = when {
                degrees <= 45f -> 0
                degrees <= 90f -> 1
                degrees <= 135f -> 2
                degrees <= 180f -> 3
                degrees <= 225f -> 4
                degrees <= 270f -> 5
                else -> 6
            }
            return fromId((if (leftHandTraffic) 20 else 46) + bucket)!!
        }
    }
}
