package com.th4rv4.zukidashsim

import com.th4rv4.zukinav.protocol.SuzukiFrames

/** What a frame from the phone asks the dash to show, in words. */
object FrameText {

    /** Which dash screen a frame belongs to, and what it says. */
    data class Shown(val screen: String, val text: String)

    fun describe(f: ByteArray): Shown {
        if (f.size != SuzukiFrames.FRAME_SIZE || f[0] != SuzukiFrames.START) return Shown("?", SuzukiFrames.hex(f))
        val bad = f[28] != SuzukiFrames.checksum(f, SuzukiFrames.Checksum.SUM) &&
            f[28] != SuzukiFrames.checksum(f, SuzukiFrames.Checksum.INVERTED)
        val note = if (bad) "  (bad checksum)" else ""
        return when (f[1].toInt().toChar()) {
            '1' -> {
                val active = f[24].toInt().toChar() == '1'
                if (!active) {
                    Shown("Nav", "navigation ended$note")
                } else {
                    Shown(
                        "Nav",
                        "icon ${f[2].toInt() and 0xFF}, turn in ${ascii(f, 4, 4)}${unit(f[8])}, " +
                            "ETA ${ascii(f, 9, 6)}, left ${ascii(f, 18, 4)}${unit(f[22])}, status ${f[23].toInt().toChar()}$note",
                    )
                }
            }
            '2' -> {
                val ringing = f[23].toInt().toChar() == '1'
                Shown("Call", "${kind(f[22])} ${ascii(f, 2, 20)}: ${if (ringing) "ringing" else "ended"}$note")
            }
            '3' -> {
                val clock = ascii(f, 8, 6).let { if (it.length == 6) "${it.substring(0, 2)}:${it.substring(2, 4)}:${it.substring(4)}" else "--" }
                val alert = ascii(f, 4, 3).ifEmpty { "off" }
                val clear = buildList {
                    if (f[14].toInt().toChar() == 'Y') add("clears messages")
                    if (f[15].toInt().toChar() == 'Y') add("clears missed calls")
                }.joinToString(", ")
                Shown("Clock", "$clock, speed alert $alert" + (if (clear.isEmpty()) "" else ", $clear") + note)
            }
            '4' -> Shown("Missed", "${f[3].toInt() and 0xFF}× ${kind(f[24])} ${ascii(f, 4, 20)}$note")
            '5' -> {
                val pop = if (f[3].toInt().toChar() == 'Y') "pops up" else "icon only"
                Shown("Message", "${f[4].toInt() and 0xFF} unread, ${kind(f[25])} ${ascii(f, 5, 20)} ($pop)$note")
            }
            '6' -> Shown("Hello", "rider ${ascii(f, 2, 20)}, ${if (f[27].toInt().toChar() == 'F') "first time" else "again"}$note")
            else -> Shown("?", SuzukiFrames.hex(f))
        }
    }

    private fun kind(b: Byte) = when (b.toInt().toChar()) {
        'W' -> "WhatsApp"
        'N' -> "phone"
        'X' -> "calendar"
        else -> "?"
    }

    private fun unit(b: Byte) = when (b.toInt().toChar()) {
        'K' -> " km"
        'M' -> " m"
        else -> ""
    }

    private fun ascii(f: ByteArray, at: Int, len: Int): String =
        buildString {
            for (i in at until at + len) {
                val c = f[i].toInt() and 0xFF
                if (c in 0x20..0x7E) append(c.toChar())
            }
        }.trim()
}
