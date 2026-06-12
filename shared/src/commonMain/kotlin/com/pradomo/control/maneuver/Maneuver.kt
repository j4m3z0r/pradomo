package com.pradomo.control.maneuver

/** Mower pose in the map frame: metres, plus heading in radians (CCW, east = 0). */
data class Pose(val x: Float, val y: Float, val heading: Float)

/** One control command from a maneuver: normalised drive/turn in [-1,1], plus completion. */
data class ManeuverCommand(val drive: Float, val turn: Float, val done: Boolean) {
    companion object {
        /** Convenience terminal command: stop, finished. */
        val DONE = ManeuverCommand(0f, 0f, done = true)
    }
}

/** Which way a turn maneuver curves (taken from the joystick deflection). */
enum class TurnDirection { LEFT, RIGHT }

/**
 * A semi-autonomous motion controller. [step] is called every control tick (~80ms) with
 * the latest telemetry [pose]; it returns the command to send to the mower. Pure and
 * deterministic — no IO, no coroutines — so it can be unit-tested against a kinematic
 * sim. Implementations are stateful across calls (they latch their start pose on the
 * first [step]). The caller is responsible for cancelling a maneuver instantly on a
 * stick touch, E-STOP, disconnect, or backgrounding; a maneuver never disables those.
 */
interface Maneuver {
    fun step(pose: Pose, dtSeconds: Float): ManeuverCommand
}
