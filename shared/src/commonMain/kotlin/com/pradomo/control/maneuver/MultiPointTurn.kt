package com.pradomo.control.maneuver

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Multi-point K-turn: reverse the mower's heading by 180° and leave it one row-pitch
 * over (pitch = cutting width − overlap), so it can mow the adjacent strip with no gap.
 *
 * Shape (left turn shown; mirrored for right): starting AT the end of the row,
 *   ① reverse-right  — back up while the nose swings left,
 *   ② forward arc left — the main sweep,
 *   ③ reverse-right  — nose keeps coming around while backing toward the row end,
 *   ④ forward-left   — finish the rotation and drive off down the new row.
 * The heading rotates monotonically through all four legs and every leg is an arc at a
 * grass-safe radius [Params.turnRadius], so both treads keep rolling the whole time —
 * no near-pivot ever scrubs the lawn. Backing up first means the maneuver mostly uses
 * the already-mowed row behind as its workspace (it may overrun the row-end line by a
 * small fraction of the radius on legs ③–④).
 *
 * Geometry (row frame: u = start heading, v = s·left; all arcs radius R; p = pitch/2R):
 * legs have drive signs (−,+,−,+) and cumulative-rotation breakpoints (a, b, c, π) with
 *   sin b = sin a + sin c,   cos b = (cos a − p) + cos c
 * (from requiring the end pose to be u=0, v=pitch). With a fixed at 30° the rest is
 * closed-form: A = cos a − p, B = sin a →
 *   c = π + asin(√(A²+B²)/2) − atan2(A, B),   b = acos(A + cos c).
 *
 * Leg transitions fire on *accumulated heading from telemetry*, then a closed-loop
 * TRIM phase servos the heading to exactly the reversed target (within
 * [Params.headingTolerance]) using short alternating fwd/back arcs, and a SETTLE check
 * only declares done once the mower has actually stopped yawing inside tolerance — so
 * the end heading is exact regardless of real turn dynamics, latency, or overshoot.
 *
 * Sign convention matches the app: drive +forward/−back, turn +left (CCW); heading is
 * radians CCW with east = 0 (same frame as telemetry and the demo sim).
 */
class MultiPointTurn(
    direction: TurnDirection,
    rowPitchMetres: Float,
    private val params: Params = Params(),
) : Maneuver {

    data class Params(
        val turnScale: Float = 0.6f,     // rad/s at turn = 1.0 (LymowProtocol.TURN_LIMIT)
        val linearScale: Float = 0.5f,   // m/s at drive = 1.0 (current SpeedMode.maxLinear)
        val turnRadius: Float = 0.45f,   // grass-safe arc radius (m); wider = gentler
        val headingTolerance: Float = 0.035f, // ~2° — the "exactly reversed" guarantee
        val kTrim: Float = 2.0f,         // trim servo gain (sim-tuned: gentler avoids overshoot under lag)
        val minTrimTurn: Float = 0.15f,  // floor so trim overcomes motor deadband
        val settleTicks: Int = 3,        // ticks stopped-in-tolerance before done (kept robust for exact heading)
        val maxSteps: Int = 2000,        // hard safety cap (~160s at 80ms) → finish
    )

    private val s = if (direction == TurnDirection.LEFT) 1f else -1f
    private val pitch = rowPitchMetres.coerceAtLeast(0f)

    /** Arc radius: at least the grass-safe setting, raised for wide pitch (keeps the
     * solver branch valid, p ≤ 0.6), capped to what the drive channel can realise. */
    private val radius = min(
        max(params.turnRadius, pitch / 1.2f),
        0.95f * params.linearScale / params.turnScale,
    )

    /** Drive magnitude that realises [radius] at full turn authority. */
    private val driveMag = (radius * params.turnScale / params.linearScale).coerceIn(0.05f, 1f)

    /** Legs: drive sign and the signed cumulative-rotation target at leg end. */
    private val legs: List<Leg> = planLegs()

    private var started = false
    private var targetHeading = 0f

    private var prevHeading = 0f
    private var totalRot = 0f   // unwrapped accumulated rotation (signed; same sign as s)
    private var legIndex = 0
    private var steps = 0

    // TRIM/SETTLE state.
    private var trimDriveSign = 1f
    private var trimDist = 0f
    private var settleCount = 0

    private class Leg(val driveSign: Float, val rotTarget: Float)

    private fun planLegs(): List<Leg> {
        val a = (PI / 6).toFloat()                  // 30°: fwd-arc apex ≈ the row-end line
        val p = pitch / (2f * radius)
        val bigA = cos(a) - p
        val bigB = sin(a)
        val m = sqrt(bigA * bigA + bigB * bigB)
        val c = (PI + asin((m / 2f).toDouble()) - atan2(bigA.toDouble(), bigB.toDouble())).toFloat()
        val b = acos((bigA + cos(c)).coerceIn(-1f, 1f))
        return listOf(
            Leg(-1f, s * a),                // ① reverse, nose swings toward the turn
            Leg(1f, s * b),                 // ② forward main arc
            Leg(-1f, s * c),                // ③ reverse again
            Leg(1f, s * PI.toFloat()),      // ④ forward, off down the new row
        )
    }

    override fun step(pose: Pose, dtSeconds: Float): ManeuverCommand {
        if (!started) {
            started = true
            prevHeading = pose.heading
            totalRot = 0f
            targetHeading = pose.heading + s * PI.toFloat()
        } else {
            totalRot += angleDelta(prevHeading, pose.heading)
            prevHeading = pose.heading
        }

        steps++
        if (steps >= params.maxSteps) return ManeuverCommand.DONE

        // Advance past any legs whose rotation target the mower has already swept through.
        while (legIndex < legs.size && reached(legs[legIndex])) legIndex++
        if (legIndex < legs.size) {
            val leg = legs[legIndex]
            return ManeuverCommand(leg.driveSign * driveMag, s * 1f, done = false)
        }

        // Planned rotation complete → closed-loop TRIM to the exact reversed heading,
        // then SETTLE (must hold tolerance while stopped) before declaring done.
        val err = angleDelta(pose.heading, targetHeading)
        if (abs(err) <= params.headingTolerance) {
            settleCount++
            if (settleCount > params.settleTicks) return ManeuverCommand.DONE
            return ManeuverCommand(0f, 0f, done = false) // stop and let the yaw settle
        }
        settleCount = 0
        // Trim with short alternating fwd/back arcs — never a pivot.
        trimDist += driveMag * params.linearScale * dtSeconds
        if (trimDist >= TRIM_LEG_FRACTION * radius) {
            trimDist = 0f
            trimDriveSign = -trimDriveSign
        }
        val turnMag = max(params.minTrimTurn, min(1f, params.kTrim * abs(err)))
        val turn = if (err >= 0f) turnMag else -turnMag
        return ManeuverCommand(trimDriveSign * driveMag, turn, done = false)
    }

    private fun reached(leg: Leg): Boolean = s * totalRot >= s * leg.rotTarget - ROT_EPS

    private companion object {
        const val ROT_EPS = 1e-3f
        const val TRIM_LEG_FRACTION = 0.4f // flip trim drive direction every 0.4·R of travel
    }
}

/** Smallest signed rotation (radians) from [from] to [to], in (−π, π]. */
fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % (2f * PI.toFloat())
    if (d <= -PI.toFloat()) d += 2f * PI.toFloat()
    if (d > PI.toFloat()) d -= 2f * PI.toFloat()
    return d
}
