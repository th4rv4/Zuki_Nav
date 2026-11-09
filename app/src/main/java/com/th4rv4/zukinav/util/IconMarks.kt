package com.th4rv4.zukinav.util

/**
 * The rider's Dash Test verdicts: for each turn-icon ID, whether it looked
 * right or wrong on their dash. Stored as a compact string ("1:R,7:W").
 */
class IconMarks(private val marks: Map<Int, Mark> = emptyMap()) {

    enum class Mark(val code: Char) { RIGHT('R'), WRONG('W') }

    operator fun get(id: Int): Mark? = marks[id]

    fun with(id: Int, mark: Mark?): IconMarks =
        IconMarks(if (mark == null) marks - id else marks + (id to mark))

    fun count(mark: Mark): Int = marks.values.count { it == mark }

    fun encode(): String = marks.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value.code}" }

    /** Plain-text summary for sharing. */
    fun summary(model: String, lastId: Int): String = buildString {
        append("Zuki Nav Dash Test - ").append(model).append('\n')
        append("Right: ").append(ids(Mark.RIGHT)).append('\n')
        append("Wrong: ").append(ids(Mark.WRONG)).append('\n')
        val untested = (1..lastId).filter { it !in marks }
        append("Untested: ").append(if (untested.isEmpty()) "none" else untested.joinToString(", "))
    }

    private fun ids(mark: Mark): String =
        marks.filterValues { it == mark }.keys.sorted().joinToString(", ").ifEmpty { "none" }

    companion object {
        fun decode(s: String?): IconMarks {
            if (s.isNullOrBlank()) return IconMarks()
            val map = HashMap<Int, Mark>()
            for (part in s.split(',')) {
                val (id, code) = part.split(':').takeIf { it.size == 2 } ?: continue
                val mark = Mark.entries.firstOrNull { it.code.toString() == code } ?: continue
                id.trim().toIntOrNull()?.let { map[it] = mark }
            }
            return IconMarks(map)
        }
    }
}
