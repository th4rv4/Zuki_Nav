package com.th4rv4.zukinav.update

/**
 * Compares app versions as written in release tags ("v0.2.1") and in the
 * app ("0.2.1"). Builds started by hand are named "0.0.0-<commit>", which
 * counts as older than every release.
 */
object Versions {

    /** Negative if [a] is older than [b], 0 if the same, positive if newer. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        // Same numbers: a plain release is newer than a "-something" build.
        val sa = a.contains('-')
        val sb = b.contains('-')
        return when {
            sa == sb -> 0
            sa -> -1
            else -> 1
        }
    }

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}

/** Joins the notes of every release between the installed version and the newest one. */
object ReleaseNotes {

    /** Most releases shown; older ones are on GitHub. */
    const val MAX_RELEASES = 12

    /**
     * [releases] as (version, notes), in any order. Keeps those newer than
     * [installed] and not newer than [latest], newest first. One release
     * gives its notes as they are; several get a "v1.2.3" line above each.
     */
    fun since(releases: List<Pair<String, String>>, installed: String, latest: String): String {
        val wanted = releases
            .filter { (v, _) -> Versions.isNewer(v, installed) && !Versions.isNewer(v, latest) }
            .distinctBy { it.first.removePrefix("v") }
            .sortedWith { a, b -> Versions.compare(b.first, a.first) }
        if (wanted.isEmpty()) return ""
        if (wanted.size == 1) return wanted[0].second.trim()
        val shown = wanted.take(MAX_RELEASES)
        val text = shown.joinToString("\n\n") { (v, notes) ->
            "v" + v.removePrefix("v") + "\n" + notes.trim().ifEmpty { "• Small fixes" }
        }
        return if (wanted.size > shown.size) "$text\n\nOlder changes are on GitHub." else text
    }
}
