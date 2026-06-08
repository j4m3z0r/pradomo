package com.pradomo.control

/**
 * Drive speed mode: scales how far the joystick maps into linearSpeed (full stick
 * -> [maxLinear]). Speed is purely client-side scaling — the mower has no "set
 * speed" command. NORMAL (0.5) is the conservative default; TURBO is experimental
 * (the mower may clamp it internally).
 */
enum class SpeedMode(val maxLinear: Float, val label: String) {
    SLOW(0.3f, "Slow"),
    NORMAL(0.5f, "Normal"),
    TURBO(1.0f, "Turbo"),
}
