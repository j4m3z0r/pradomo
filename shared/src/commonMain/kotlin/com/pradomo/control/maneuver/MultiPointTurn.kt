package com.pradomo.control.maneuver

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Multi-point K-turn: reverse the mower's heading by 180° and leave it on the adjacent
 * row line, one row-pitch over (pitch = cutting width − overlap), so it can mow the next
 * strip with no gap.
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
 * Closed-loop refinements for the real mower's slow (~1.6 Hz), latent telemetry:
 *  - Leg transitions fire on *accumulated heading from telemetry*, and rotation slows as a
 *    leg's breakpoint approaches ([Params.approachHorizon]) so a blind window between
 *    samples can't sweep far past it (drive scales with turn, preserving the arc radius).
 *  - A TRIM phase then servos heading to exactly the reversed target with short alternating
 *    fwd/back arcs (never a pivot).
 *  - SETTLE only declares the heading reached after the pose has *changed* since the stop
 *    was commanded (i.e. a fresh telemetry sample confirmed it, not the stale one) — with a
 *    timeout fallback if telemetry is static.
 *  - An optional ACQUIRE phase then fixes lateral placement closed-loop: drive forward down
 *    the new row, steering onto the target row line (slope drift and model error make
 *    open-loop placement structurally inaccurate), then re-trim the heading.
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
        val kTrim: Float = 1.5f,         // trim/acquire servo gain (sim-tuned; gentler resists overshoot)
        val minTrimTurn: Float = 0.2f,   // floor so trim overcomes motor deadband (sim-tuned)
        val settleTicks: Int = 3,        // min ticks stopped before done
        val maxSteps: Int = 2000,        // hard safety cap (~160s at 80ms) → finish
        // Approach-rate profiling: slow rotation when a leg's remaining angle is below this
        // horizon, so slow telemetry can't blind-sweep far past the breakpoint. 0 disables.
        val approachHorizon: Float = 0.35f, // radians (~20°, sim-tuned)
        val minApproachFrac: Float = 0.3f,  // slow-down floor (deadband-viable)
        // Fresh-pose settle: require the pose to change after stopping before declaring the
        // heading verified (a stale sample can't confirm a stop). Timeout for static poses.
        val settleFreshPose: Boolean = true,
        val settleMaxTicks: Int = 16,       // ~1.3s at 80ms ≳ one telemetry period + margin
        // Closed-loop lateral placement: after the heading is settled, steer onto the target
        // row line while driving down the new row, then re-trim heading.
        val acquireRow: Boolean = true,
        val posTolerance: Float = 0.06f,    // acceptable lateral offset from the row line (m)
        val kAcquireCross: Float = 4.0f,    // aim-lean per metre of lateral offset (rad/m, atan)
        val acquireLeanCap: Float = 0.6f,   // max lean toward the line (radians)
        val acquireMaxTravel: Float = 2.5f, // give up acquiring after this much travel (m)
    )

    private enum class Phase { LEGS, TRIM, SETTLE, ACQUIRE }

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

    private var phase = Phase.LEGS
    private var started = false
    private var targetHeading = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var prevHeading = 0f
    private var totalRot = 0f   // unwrapped accumulated rotation (signed; same sign as s)
    private var legIndex = 0
    private var steps = 0

    // TRIM/SETTLE/ACQUIRE state.
    private var trimDriveSign = 1f
    private var trimDist = 0f
    private var settleSteps = 0
    private var settleFreshSeen = false
    private var settleStartX = 0f
    private var settleStartY = 0f
    private var settleStartHeading = 0f
    private var acquireTravel = 0f
    private var trimReentries = 0

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
            // Target row line: one pitch along the turn-side normal of the start heading.
            val nx = -sin(pose.heading); val ny = cos(pose.heading)
            targetX = pose.x + s * pitch * nx
            targetY = pose.y + s * pitch * ny
        } else {
            totalRot += angleDelta(prevHeading, pose.heading)
            prevHeading = pose.heading
        }

        steps++
        if (steps >= params.maxSteps) return ManeuverCommand.DONE

        return when (phase) {
            Phase.LEGS -> stepLegs(pose)
            Phase.TRIM -> stepTrim(pose, dtSeconds)
            Phase.SETTLE -> stepSettle(pose)
            Phase.ACQUIRE -> stepAcquire(pose, dtSeconds)
        }
    }

    private fun stepLegs(pose: Pose): ManeuverCommand {
        // Advance past any legs whose rotation target the mower has already swept through.
        while (legIndex < legs.size && reached(legs[legIndex])) legIndex++
        if (legIndex >= legs.size) {
            phase = Phase.TRIM
            return stepTrim(pose, 0f)
        }
        val leg = legs[legIndex]
        // Approach-rate profiling: slow rotation near the breakpoint so a blind telemetry
        // window can't sweep far past it. Drive scales with turn → arc radius preserved.
        val f = if (params.approachHorizon > 0f) {
            val remaining = (s * leg.rotTarget - s * totalRot).coerceAtLeast(0f)
            (remaining / params.approachHorizon).coerceIn(params.minApproachFrac, 1f)
        } else 1f
        return ManeuverCommand(leg.driveSign * driveMag * f, s * f, done = false)
    }

    /** Under a persistent yaw disturbance the mower cannot HOLD ±tol while stopped; after
     * a few trim↔settle cycles we accept a widened band rather than loop forever. */
    private fun effectiveTolerance(): Float =
        if (trimReentries > MAX_TRIM_REENTRIES) 2f * params.headingTolerance else params.headingTolerance

    private fun stepTrim(pose: Pose, dtSeconds: Float): ManeuverCommand {
        val err = angleDelta(pose.heading, targetHeading)
        if (abs(err) <= effectiveTolerance()) {
            enterSettle(pose)
            return ManeuverCommand(0f, 0f, done = false)
        }
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

    private fun enterSettle(pose: Pose) {
        phase = Phase.SETTLE
        settleSteps = 0
        settleFreshSeen = false
        settleStartX = pose.x; settleStartY = pose.y; settleStartHeading = pose.heading
    }

    private fun stepSettle(pose: Pose): ManeuverCommand {
        settleSteps++
        if (pose.x != settleStartX || pose.y != settleStartY || pose.heading != settleStartHeading) {
            settleFreshSeen = true
        }
        val err = angleDelta(pose.heading, targetHeading)
        if (settleFreshSeen && abs(err) > effectiveTolerance()) {
            // A fresh sample shows we coasted out of tolerance — keep trimming.
            trimReentries++
            phase = Phase.TRIM
            return ManeuverCommand(0f, 0f, done = false)
        }
        val verified = settleFreshSeen || !params.settleFreshPose || settleSteps >= params.settleMaxTicks
        if (settleSteps >= params.settleTicks && verified) {
            return finishOrAcquire(pose)
        }
        return ManeuverCommand(0f, 0f, done = false) // stay stopped and wait
    }

    private fun finishOrAcquire(pose: Pose): ManeuverCommand {
        if (!params.acquireRow || acquireTravel >= params.acquireMaxTravel) return ManeuverCommand.DONE
        if (abs(crossTrack(pose)) <= params.posTolerance) return ManeuverCommand.DONE
        phase = Phase.ACQUIRE
        return ManeuverCommand(0f, 0f, done = false)
    }

    private fun stepAcquire(pose: Pose, dtSeconds: Float): ManeuverCommand {
        val cross = crossTrack(pose)
        val headErr = angleDelta(pose.heading, targetHeading)
        // Finish WHILE ROLLING: on the line and pointed down it. Stopping to re-verify
        // would just let slope drift slide the mower off the line again — the natural
        // hand-off is straight into mowing motion along the new row.
        if (abs(cross) <= params.posTolerance && abs(headErr) <= 2f * params.headingTolerance) {
            return ManeuverCommand.DONE
        }
        if (acquireTravel >= params.acquireMaxTravel) {
            phase = Phase.TRIM // budget spent: square the heading up and accept placement
            return ManeuverCommand(0f, 0f, done = false)
        }
        acquireTravel += driveMag * params.linearScale * dtSeconds
        // Aim at a heading that leans toward the row line, proportional to the offset
        // (same scheme as cruise line-hold), driving forward down the new row.
        val lean = atan(params.kAcquireCross * cross).coerceIn(-params.acquireLeanCap, params.acquireLeanCap)
        val desired = targetHeading - lean
        val err = angleDelta(pose.heading, desired)
        val turn = (params.kTrim * err).coerceIn(-1f, 1f)
        return ManeuverCommand(driveMag, turn, done = false)
    }

    /** Signed lateral offset from the target row line (+ = left of the line direction). */
    private fun crossTrack(pose: Pose): Float {
        val dx = cos(targetHeading); val dy = sin(targetHeading)
        return dx * (pose.y - targetY) - dy * (pose.x - targetX)
    }

    private fun reached(leg: Leg): Boolean = s * totalRot >= s * leg.rotTarget - ROT_EPS

    private companion object {
        const val ROT_EPS = 1e-3f
        const val TRIM_LEG_FRACTION = 0.4f // flip trim drive direction every 0.4·R of travel
        const val MAX_TRIM_REENTRIES = 4   // then widen tolerance (can't hold ±2° on a yawing slope)
    }
}

/** Smallest signed rotation (radians) from [from] to [to], in (−π, π]. */
fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % (2f * PI.toFloat())
    if (d <= -PI.toFloat()) d += 2f * PI.toFloat()
    if (d > PI.toFloat()) d -= 2f * PI.toFloat()
    return d
}
