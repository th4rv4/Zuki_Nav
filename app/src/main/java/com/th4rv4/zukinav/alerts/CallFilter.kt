package com.th4rv4.zukinav.alerts

/**
 * Decides whether an incoming phone call is from an unknown number, going
 * by what the phone app shows: a saved contact shows a name, anyone else a
 * bare number, "Unknown" or "Private number".
 */
class CallFilter(private val repeatWindowMs: Long = REPEAT_WINDOW_MS) {

    /** Unknown callers declined lately, by what the phone app showed. */
    private val declined = HashMap<String, Long>()

    /**
     * True if this call should be declined. With [allowRepeat], a second call
     * from the same unknown number within [repeatWindowMs] rings through.
     */
    fun shouldDecline(caller: String?, allowRepeat: Boolean, now: Long): Boolean {
        if (!isUnknown(caller)) return false
        val key = normalise(caller)
        declined.entries.removeAll { now - it.value > repeatWindowMs }
        if (allowRepeat && key.isNotEmpty() && declined.containsKey(key)) {
            declined.remove(key)
            return false
        }
        declined[key] = now
        return true
    }

    /** True if [caller] was declined in the last minute, so its missed-call notice can be ignored. */
    fun recentlyDeclined(caller: String?, now: Long): Boolean {
        val at = declined[normalise(caller)] ?: return false
        return now - at <= QUIET_MS
    }

    companion object {
        const val REPEAT_WINDOW_MS = 5 * 60_000L
        const val QUIET_MS = 60_000L

        private val HIDDEN = Regex(
            """(?i)^(unknown( caller| number)?|private( number| caller)?|no caller id|restricted( number)?|withheld( number)?|anonymous|unavailable)$""",
        )
        private val NUMBER = Regex("""^\+?\d{4,15}$""")

        fun isUnknown(caller: String?): Boolean {
            val c = caller?.trim().orEmpty()
            if (c.isEmpty()) return true
            if (HIDDEN.matches(c)) return true
            return NUMBER.matches(digits(c))
        }

        /** "+91 98765-43210" -> "+919876543210"; anything with letters stays as it is. */
        private fun digits(s: String): String = s.replace(Regex("""[\s\-().‎‏‪-‮]"""), "")

        private fun normalise(caller: String?): String {
            val c = caller?.trim().orEmpty()
            val d = digits(c)
            return if (NUMBER.matches(d)) d.takeLast(10) else c.lowercase()
        }
    }
}
