package com.th4rv4.zukinav.nav

import com.th4rv4.zukinav.protocol.ClusterIcon
import java.time.LocalTime

/**
 * Pure string parsing for Google Maps' turn-by-turn notification. No Android
 * types, so it runs in JVM unit tests.
 *
 * Google Maps' navigation notification, as seen on recent versions:
 *   title    "350 m"                         (or "350 m · Turn left onto X")
 *   text     "towards MG Road"               (or "Turn left onto MG Road")
 *   subText  "12 min · 4.5 km · 7:42 pm ETA"
 *   android.shortCriticalText "350 m"       (Android 16+)
 *
 * The approach to the two title layouts follows KTM-Nav-GEN3's NavFields
 * (MIT, github.com/Pavanayi1/KTM-Nav-GEN3).
 */
object NavText {

    data class Parsed(
        val metersToTurn: Double?,
        val instruction: String?,
        val remainingMeters: Double?,
        val eta: LocalTime?,
        val textIcon: ClusterIcon?,
    )

    private val DISTANCE = Regex("""(\d+(?:[.,]\d+)?)\s*(km|mi|ft|yd|m)\b""", RegexOption.IGNORE_CASE)
    private val DISTANCE_ONLY = Regex("""^\s*\d+(?:[.,]\d+)?\s*(?:km|mi|ft|yd|m)\s*$""", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("""\s*[·•|]\s*""")
    private val CLOCK = Regex("""\b(\d{1,2})\s*[:.]\s*(\d{2})\s*([ap]\.?\s?m\.?)?""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(\d+)\s*(?:hr|hrs|hour|hours|h)\b""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+)\s*(?:min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE)

    fun parse(
        title: String?,
        text: String?,
        subText: String?,
        shortCriticalText: String?,
        now: LocalTime,
        leftHandTraffic: Boolean = true,
    ): Parsed {
        val (dist, instruction) = splitTitle(title, text, shortCriticalText)
        val status = subText.orEmpty()
        return Parsed(
            metersToTurn = dist?.let(::toMeters),
            instruction = instruction,
            remainingMeters = DISTANCE.find(status)?.value?.let(::toMeters),
            eta = parseEta(status, now),
            textIcon = maneuverFromText(listOfNotNull(title, text).joinToString(" "), leftHandTraffic),
        )
    }

    /** Returns (distance string, road/instruction line). */
    fun splitTitle(title: String?, text: String?, shortCriticalText: String?): Pair<String?, String?> {
        val t = title?.trim().orEmpty()
        val x = text?.trim().orEmpty()
        val critical = shortCriticalText?.trim()?.takeIf { it.isNotEmpty() }

        if (x.isNotEmpty()) {
            val d = when {
                DISTANCE_ONLY.matches(t) -> t
                critical != null && DISTANCE_ONLY.matches(critical) -> critical
                else -> t.split(SEPARATOR, limit = 2).firstOrNull()?.takeIf { DISTANCE_ONLY.matches(it) }
            }
            return d to x
        }
        if (t.isEmpty()) return critical to null
        val parts = t.split(SEPARATOR, limit = 2)
        if (parts.size == 2 && DISTANCE_ONLY.matches(parts[0])) return parts[0].trim() to parts[1].trim().ifEmpty { null }
        if (critical != null && t.startsWith(critical)) {
            val rest = t.removePrefix(critical).trimStart(' ', '·', '•', '|').trim()
            return critical to rest.ifEmpty { null }
        }
        if (DISTANCE_ONLY.matches(t)) return t to null
        return critical to t
    }

    fun toMeters(s: String): Double? {
        val m = DISTANCE.find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "km" -> v * 1000.0
            "mi" -> v * 1609.344
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            else -> v
        }
    }

    /**
     * ETA from the trip-status line. Prefers an explicit clock time; falls back
     * to now + the "1 hr 5 min" duration if Maps only shows the duration.
     */
    fun parseEta(status: String, now: LocalTime): LocalTime? {
        CLOCK.find(status)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ampm = m.groupValues[3].lowercase().replace(".", "").replace(" ", "")
            if (min > 59 || h > 23) return@let
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            return LocalTime.of(h, min)
        }
        val hours = HOURS.find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val mins = MINUTES.find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (hours == 0L && mins == 0L) return null
        return now.plusMinutes(hours * 60 + mins).withSecond(0).withNano(0)
    }

    // "on the left/right" is a side, not a road name, so it is kept.
    private val ROAD_TAIL = Regex(
        """\b(?:onto|towards?|into)\b.*|\bon\b(?!\s+(?:the\s+)?(?:left|right)\b).*""",
        RegexOption.IGNORE_CASE,
    )
    private val ORDINAL_EXIT = Regex("""(\d+)(?:st|nd|rd|th)?\s+exit""")
    private val EXIT_N = Regex("""exit\s+(\d+)""")
    private val WORD_EXITS = listOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6)
    private val wordRegex = HashMap<String, Regex>()

    private fun String.hasWord(w: String): Boolean =
        synchronized(wordRegex) { wordRegex.getOrPut(w) { Regex("""\b${Regex.escape(w)}\b""") } }.containsMatchIn(this)

    /**
     * English maneuver words -> cluster icon, or null if the text names no
     * maneuver (Maps often only says "towards X"; the arrow icon is then the
     * only source). Road names are cut off first so "Turn left onto Right
     * Bank Rd" stays a left turn.
     */
    fun maneuverFromText(raw: String, leftHandTraffic: Boolean = true): ClusterIcon? {
        val t = raw.split(SEPARATOR).joinToString(" ") { ROAD_TAIL.replace(it, "") }.lowercase().trim()
        if (t.isBlank()) return null
        val left = t.hasWord("left")
        val right = t.hasWord("right")

        if (t.hasWord("roundabout") || t.hasWord("rotary") || t.hasWord("circle")) {
            val exit = ORDINAL_EXIT.find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: EXIT_N.find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: WORD_EXITS.firstOrNull { t.contains("${it.first} exit") }?.second
            return when {
                exit != null -> roundaboutByExit(exit, leftHandTraffic)
                left && !right -> ClusterIcon.roundabout(if (leftHandTraffic) 90f else 270f, leftHandTraffic)
                right && !left -> ClusterIcon.roundabout(if (leftHandTraffic) 270f else 90f, leftHandTraffic)
                t.hasWord("straight") -> ClusterIcon.roundabout(180f, leftHandTraffic)
                else -> ClusterIcon.ENTER_ROUNDABOUT
            }
        }
        if (t.contains("u-turn") || t.contains("u turn") || t.contains("uturn")) {
            return if (leftHandTraffic) ClusterIcon.U_TURN else ClusterIcon.U_TURN_RHT
        }
        if (t.hasWord("ferry")) return ClusterIcon.FERRY
        if ((t.hasWord("sharp") || t.hasWord("hard")) && (left xor right)) {
            return if (left) ClusterIcon.SHARP_LEFT else ClusterIcon.SHARP_RIGHT
        }
        if ((t.hasWord("slight") || t.hasWord("bear")) && (left xor right)) {
            return if (left) ClusterIcon.SLIGHT_LEFT else ClusterIcon.SLIGHT_RIGHT
        }
        if ((t.hasWord("keep") || t.hasWord("stay") || t.hasWord("fork")) && (left xor right)) {
            return if (left) ClusterIcon.KEEP_LEFT else ClusterIcon.KEEP_RIGHT
        }
        if (t.hasWord("merge")) {
            return when {
                left && !right -> ClusterIcon.BEAR_LEFT
                right && !left -> ClusterIcon.BEAR_RIGHT
                else -> ClusterIcon.CONTINUE_STRAIGHT
            }
        }
        if (t.hasWord("ramp") || t.hasWord("exit") || t.hasWord("flyover")) {
            return when {
                left && !right -> ClusterIcon.OFF_RAMP_LEFT
                right && !left -> ClusterIcon.OFF_RAMP_RIGHT
                else -> ClusterIcon.TAKE_RAMP
            }
        }
        if (isArrival(t)) return ClusterIcon.DESTINATION
        if (left && !right) return ClusterIcon.TURN_LEFT
        if (right && !left) return ClusterIcon.TURN_RIGHT
        if (t.hasWord("straight") || t.hasWord("continue") || t.hasWord("head") || t.hasWord("proceed")) {
            return ClusterIcon.STRAIGHT
        }
        return null
    }

    /**
     * There is no angle in the text, only the exit number. On a typical
     * four-way roundabout exit 1 is a left (in India), 2 straight, 3 right,
     * 4 back round.
     */
    fun roundaboutByExit(exit: Int, leftHandTraffic: Boolean): ClusterIcon {
        val deg = when (exit) {
            1 -> 90f
            2 -> 180f
            3 -> 270f
            else -> 330f
        }
        return ClusterIcon.roundabout(deg, leftHandTraffic)
    }

    private val ARRIVAL = listOf(
        "you have arrived", "you've arrived", "arriving at", "arrive at your destination",
        "destination will be on", "destination is on", "your destination", "destination on the",
    )
    private val ARRIVED_WORD = Regex("""\barrived\b""")
    private val ARRIVE_AT_PLACE = Regex("""\barrive\s+(?:at|in|on)\s+(?!\d{1,2}\s*[:.]\s*\d{2})""")

    private fun isArrival(t: String): Boolean =
        ARRIVAL.any { t.contains(it) } || ARRIVED_WORD.containsMatchIn(t) || ARRIVE_AT_PLACE.containsMatchIn(t)
}
