package com.pradomo.protocol

import kotlin.math.abs

/**
 * Blade-motor speeds the mower accepts (PbInput field f13.f2). Values 1 and 2 are
 * unused.
 */
enum class BladeSpeed(val code: Int, val label: String) {
    OFF(0, "Off"),
    CO(3, "Eco"),
    STANDARD(4, "Standard"),
    POWER(5, "Power"),
    TURBO(6, "Turbo");

    companion object {
        fun fromCode(code: Int): BladeSpeed = entries.firstOrNull { it.code == code } ?: OFF
    }
}

/**
 * Cutting-deck heights the mower accepts: the displayed millimetre value maps to
 * the device value sent in PbInput f13.f3. Identity up to 75 mm,
 * then the device compresses (80->79, 85->83, 90->87, 95->91, 100->96). 30 mm
 * wasn't cleanly in the capture; by the identity pattern it is 30.
 */
object DeckHeights {
    val STEPS: List<Pair<Int, Int>> = listOf(
        30 to 30, 35 to 35, 40 to 40, 45 to 45, 50 to 50, 55 to 55, 60 to 60,
        65 to 65, 70 to 70, 75 to 75, 80 to 79, 85 to 83, 90 to 87, 95 to 91, 100 to 96,
    )

    /** Displayed millimetre options, ascending. */
    val MM: List<Int> = STEPS.map { it.first }

    /** Device value (f13.f3) for the given displayed mm, nearest-match if not exact. */
    fun encoded(mm: Int): Int =
        STEPS.firstOrNull { it.first == mm }?.second
            ?: STEPS.minBy { abs(it.first - mm) }.second
}
