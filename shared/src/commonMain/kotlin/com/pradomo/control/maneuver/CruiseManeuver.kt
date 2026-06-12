package com.pradomo.control.maneuver

/**
 * Cruise control: hold a constant forward/back speed with equal motor effort — the human
 * steers live (the caller blends stick left/right onto the command) and is the off switch
 * (stick fwd/back past a threshold, any button, E-STOP, or a dropped link). It never
 * self-completes.
 *
 * Deliberately NO automatic steering correction: field testing showed telemetry-based
 * correction steers worse than simply driving both treads at equal speed.
 */
class CruiseManeuver(drive: Float) : Maneuver {
    private val drive = drive.coerceIn(-1f, 1f)

    override fun step(pose: Pose, dtSeconds: Float) = ManeuverCommand(drive, 0f, done = false)
}
