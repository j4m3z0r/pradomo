package com.pradomo.control

import com.pradomo.control.maneuver.angleDelta

/**
 * Heading-hold steering assist for manual driving: when the user's steering input is
 * centred, latch the current heading and emit a corrective turn that counters drift
 * (slopes, tread slip) so the mower keeps pointing the way it was pointing. It holds
 * *heading only* — lateral displacement is left alone, by design.
 *
 * Turn commands the heading rate directly (CCW+), so the correction is the same
 * whether driving forward or in reverse. The caller latches/unlatches around user
 * steering; this class is pure state + math.
 */
// Defaults raised from sim tuning (sim/) — heading hold needs authority to counter slope
// yaw; kHead/turnCap held below the sim optimum (which pinned to the search ceiling)
// pending hardware validation.
class HeadingHold(
    private val kHead: Float = 2.5f,   // turn per radian of heading error
    private val turnCap: Float = 0.6f, // cap on the corrective turn
) {
    private var latched: Float? = null

    val isLatched: Boolean get() = latched != null

    fun latch(heading: Float) { latched = heading }

    fun unlatch() { latched = null }

    /** Corrective turn in [-turnCap, turnCap]; 0 when nothing is latched. */
    fun correction(currentHeading: Float): Float {
        val ref = latched ?: return 0f
        return (-kHead * angleDelta(ref, currentHeading)).coerceIn(-turnCap, turnCap)
    }
}
