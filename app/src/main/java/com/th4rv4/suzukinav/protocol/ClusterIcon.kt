package com.th4rv4.suzukinav.protocol

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
 * IDs marked "unverified" are known to exist but nothing in the Suzuki app
 * produces them from a normal route, so their artwork is a guess. Use the
 * Icon Test panel in the app to check them on the dash.
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
    UNKNOWN_11(11, "Unverified 11"),
    UNKNOWN_12(12, "Unverified 12"),
    END_OF_ROAD_LEFT(13, "End Of Road, Left"),
    END_OF_ROAD_RIGHT(14, "End Of Road, Right"),
    UNKNOWN_15(15, "Unverified 15"),
    UNKNOWN_16(16, "Unverified 16"),
    UNKNOWN_17(17, "Unverified 17"),
    UNKNOWN_18(18, "Unverified 18"),
    UNKNOWN_19(19, "Unverified 19"),

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
    UNKNOWN_34(34, "Unverified 34"),
    UNKNOWN_35(35, "Unverified 35"),
    UNKNOWN_36(36, "Unverified 36"),
    UNKNOWN_37(37, "Unverified 37"),
    TAKE_RAMP(38, "Take The Ramp"),
    /** U-turn for left-hand traffic (Mappls 41) - the one India uses. */
    U_TURN(39, "U-Turn"),
    UNKNOWN_40(40, "Unverified 40"),
    UNKNOWN_41(41, "Unverified 41"),
    UNKNOWN_42(42, "Unverified 42"),
    FERRY(43, "Ferry"),
    OFF_RAMP_LEFT(44, "Take Left Ramp"),
    ENTER_ROUNDABOUT(45, "Enter Roundabout"),

    // Roundabout exits, right-hand traffic (counter-clockwise).
    RAB_CCW_45(46, "Roundabout (RHT) Exit 45°"),
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
         * status is not 1, 3 or 5 (GPS lost, no network, ...).
         */
        const val STATUS_PLACEHOLDER = 46

        fun fromId(id: Int): ClusterIcon? = entries.firstOrNull { it.id == id }

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
