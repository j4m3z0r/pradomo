package com.pradomo.control.maneuver

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cruise control: hold a constant forward/back speed. The human (or a dropped link) is the
 * off switch — it never self-completes. The caller blends in live left/right steering and
 * handles disengagement; this class only decides the base command.
 *
 * With [correct] = false it drives dead straight (turn = 0). With [correct] = true it holds
 * the line it started on, using telemetry to counter drift (e.g. on a slope): it latches a
 * line through the start pose along the start heading, then steers toward a heading that
 * leans back toward that line proportional to the cross-track error. It re-converges to the
 * original line and continues along it — it does not backtrack to re-cover missed ground.
 * `sign(drive)` makes the lean correct for reverse cruise.
 *
 * Sign convention matches the app: drive +forward/−back, turn +left (CCW); heading radians
 * CCW, east = 0.
 */
class CruiseManeuver(
    drive: Float,
    private val correct: Boolean = false,
    private val gains: Gains = Gains(),
) : Maneuver {

    data class Gains(
        val kCross: Float = 1.5f,   // lean angle per metre of cross-track error (rad/m, via atan)
        val leanCap: Float = 0.6f,  // max lean toward the line (radians, ~34°)
        val kHead: Float = 1.2f,    // turn per radian of heading error
        val turnCap: Float = 0.4f,  // max |turn| the auto-correction commands (gentle)
    )

    private val drive = drive.coerceIn(-1f, 1f)

    private var started = false
    private var ox = 0f
    private var oy = 0f
    private var lineHeading = 0f

    override fun step(pose: Pose, dtSeconds: Float): ManeuverCommand {
        if (!correct) return ManeuverCommand(drive, 0f, done = false)
        if (!started) {
            started = true
            ox = pose.x; oy = pose.y; lineHeading = pose.heading
        }
        // Signed cross-track error: + means left of the line direction.
        val dirX = cos(lineHeading); val dirY = sin(lineHeading)
        val rx = pose.x - ox; val ry = pose.y - oy
        val cross = dirX * ry - dirY * rx
        // Aim at a heading that leans back toward the line; flip the lean for reverse.
        val lean = atan(gains.kCross * cross).coerceIn(-gains.leanCap, gains.leanCap)
        val desired = lineHeading - (if (drive < 0f) -1f else 1f) * lean
        val headErr = angleDelta(desired, pose.heading) // pose − desired, signed (−π,π]
        val turn = (-gains.kHead * headErr).coerceIn(-gains.turnCap, gains.turnCap)
        return ManeuverCommand(drive, turn, done = false)
    }
}
