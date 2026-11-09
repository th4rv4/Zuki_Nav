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
