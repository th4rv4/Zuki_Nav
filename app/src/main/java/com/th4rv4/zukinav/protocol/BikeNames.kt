package com.th4rv4.zukinav.protocol

/**
 * Model names as the rider knows them. Some dashes are shared by several
 * models (the Gixxer 250 and Gixxer SF 250 report the same Bluetooth name),
 * so the rider picks which one they have.
 */
object BikeNames {

    /** The models one dash can belong to; a single entry when there is no choice. */
    fun variants(displayName: String): List<String> = when (displayName) {
        "Gixxer / Gixxer SF" -> listOf("Gixxer", "Gixxer SF")
        "Gixxer 250 / SF 250 / FFV" -> listOf("Gixxer 250", "Gixxer SF 250", "Gixxer SF 250 FFV")
        else -> listOf(displayName)
    }

    /** The rider's choice if it fits this dash, otherwise the first model. */
    fun name(displayName: String, chosen: String?): String {
        val all = variants(displayName)
        return chosen?.takeIf { it in all } ?: all.first()
    }

    /**
     * Splits a name for the racing lettering: the main words, and the part
     * picked out in the accent colour (the number onwards, or a short edition
     * tag such as "SX"). "Gixxer SF 250" -> ("GIXXER SF", "250").
     */
    fun lettering(name: String): Pair<String, String> {
        val words = name.uppercase().trim().split(Regex("\\s+"))
        val number = words.indexOfFirst { w -> w.any { it.isDigit() } }
        return when {
            number > 0 -> words.take(number).joinToString(" ") to words.drop(number).joinToString(" ")
            words.size > 1 && words.last().length <= 3 -> words.dropLast(1).joinToString(" ") to words.last()
            else -> words.joinToString(" ") to ""
        }
    }
}
