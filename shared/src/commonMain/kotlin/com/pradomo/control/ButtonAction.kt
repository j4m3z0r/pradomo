package com.pradomo.control

/** What a configurable controller button does while held down. */
enum class ButtonAction(val label: String, val short: String, val speedMode: SpeedMode?) {
    NONE("None", "None", null),
    SLOW("Slow (hold)", "Slow", SpeedMode.SLOW),
    TURBO("Turbo (hold)", "Turbo", SpeedMode.TURBO),
}
