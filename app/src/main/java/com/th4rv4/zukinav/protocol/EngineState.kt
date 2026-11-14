package com.th4rv4.zukinav.protocol

/**
 * What the engine temperature means, against the two limits the rider sets
 * (0 means that limit is off). With both limits off there is no label.
 */
enum class EngineState(val label: String) {
    WARMING_UP("Warming Up"),
    NORMAL("Normal"),
    HOT("Hot");

    companion object {
        fun of(tempC: Int, warmUpC: Int, hotC: Int): EngineState? = when {
            hotC > 0 && tempC >= hotC -> HOT
            warmUpC > 0 && tempC < warmUpC -> WARMING_UP
            warmUpC > 0 || hotC > 0 -> NORMAL
            else -> null
        }
    }
}
