package com.pradomo.control

/**
 * How aggressively controlled deceleration ramps the sent drive/turn toward the
 * commanded target. [ratePerSec] is on the joystick scale (−1..1) per second, so
 * GENTLE takes ~1.7s to go full-stop→full, FIRM ~0.4s.
 */
enum class SmoothLevel(val label: String, val ratePerSec: Float) {
    GENTLE("Gentle", 0.6f),
    MEDIUM("Medium", 1.2f),
    FIRM("Firm", 2.5f),
}
