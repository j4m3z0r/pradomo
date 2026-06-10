package com.pradomo.control.maneuver

/**
 * Cruise control: hold a constant straight-line command until cancelled externally
 * (stick touch / E-STOP / disconnect / backgrounding). Never self-completes — it has no
 * idea where the yard ends, so the human (or a dropped link) is the off switch.
 *
 * @param drive normalised forward(+) / backward(-) speed in [-1,1]; turn is held at 0.
 */
class CruiseManeuver(private val drive: Float) : Maneuver {
    override fun step(pose: Pose, dtSeconds: Float) =
        ManeuverCommand(drive.coerceIn(-1f, 1f), 0f, done = false)
}
