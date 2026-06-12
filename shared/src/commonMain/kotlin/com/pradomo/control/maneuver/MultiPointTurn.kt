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
 * About-face maneuver: reverse the mower's heading by 180° and leave it on the adjacent
 * row line, one row-pitch over (pitch = cutting width − overlap), so it can mow the next
 * strip with no gap. Two [TurnStyle]s share the machinery:
 *
 * K_TURN (left turn shown; mirrored for right) — starting AT the end of the row:
 *   ① reverse-right, ② forward arc left, ③ reverse-right, ④ forward-left down the new
 *   row. Rotation is monotonic; backing up first means it works the already-mowed row
 *   behind instead of needing headland. Geometry (p = pitch/2R, a fixed at 30°):
 *   sin b = sin a + sin c, cos b = (cos a − p) + cos c → closed form via
 *   c = π + asin(√(A²+B²)/2) − atan2(A,B), b = acos(A + cos c), with A = cos a − p,
 *   B = sin a.
 *
 * U_TURN — forward-only: a plain semicircle of radius pitch/2 when pitch ≥ 2R; otherwise
 *   a teardrop: counter-arc AWAY by θ₀ then one big loop to the reversed heading, with
 *   cos θ₀ = pitch/2R (from the two-arc lateral condition). Never reverses, but sweeps
 *   past the row end — that's its documented trade-off.
 *
 * Every leg is an arc at a grass-safe radius (≥ [Params.turnRadius]) so both treads keep
 * rolling — no near-pivot ever scrubs the lawn.
 *
 * Closed-loop machinery for the real mower's slow (~1.6 Hz), latent telemetry:
 *  - An internal heading predictor: commanded rotation is integrated between telemetry
 *    samples, scaled by an online-learned turn effectiveness (kAng, from confirmed
 *    rotation vs commanded rotation between fresh samples). Leg transitions act on the
 *    predicted rotation plus a stop-lead for actuation lag, so the mower stops rotating
 *    ON the breakpoints instead of blind-sweeping past them.
 *  - Approach-rate profiling near each breakpoint (drive scales with turn — radius kept).
 *  - A finish WITHOUT back-and-forth: after the legs, stop and verify against a FRESH
 *    telemetry sample (a stale one can't confirm a stop). If outside tolerance, at most
 *    [Params.maxTrimPasses] forward-only trim arcs — never alternating fwd/back wiggles.
 *  - An optional ACQUIRE phase fixes lateral placement closed-loop (steer onto the target
 *    row line while rolling down the new row) and finishes while moving.
 *
 * Sign convention matches the app: drive +forward/−back, turn +left (CCW); heading is
 * radians CCW with east = 0 (same frame as telemetry and the demo sim).
 */
class MultiPointTurn(
    direction: TurnDirection,
    rowPitchMetres: Float,
    private val params: Params = Params(),
    private val style: TurnStyle = TurnStyle.K_TURN,
) : Maneuver {

    data class Params(
        val turnScale: Float = 0.6f,     // rad/s at turn = 1.0 (LymowProtocol.TURN_LIMIT)
        val linearScale: Float = 0.5f,   // m/s at drive = 1.0 (current SpeedMode.maxLinear)
        val turnRadius: Float = 0.45f,   // grass-safe arc radius (m); wider = gentler
        val headingTolerance: Float = 0.06f, // ~3.4° — loosened: cruise-handoff makes the last degrees cheap
        val kTrim: Float = 2.0f,         // trim/acquire servo gain (sim-tuned)
        val minTrimTurn: Float = 0.3f,   // floor so trim overcomes motor deadband (sim-tuned)
        val settleTicks: Int = 2,        // min ticks stopped before done (sim-tuned)
        val maxSteps: Int = 2000,        // hard safety cap (~160s at 80ms) → finish
        // Approach-rate profiling: slow rotation when a leg's remaining angle is below this
        // horizon, so slow telemetry can't blind-sweep far past the breakpoint. 0 disables.
        val approachHorizon: Float = 0.25f, // radians (~14°, sim-tuned)
        val minApproachFrac: Float = 0.3f,  // slow-down floor (deadband-viable)
        // Internal heading prediction between telemetry samples (and its model constants).
        val usePrediction: Boolean = true,
        val actuationLagS: Float = 0.2f,    // stop-lead: expected coast after a command change
        val telemetryLatencyS: Float = 0.12f, // samples describe the pose this long ago
        // Finish behavior.
        val settleFreshPose: Boolean = true, // require a pose update after stopping before done
        val settleMaxTicks: Int = 16,       // ~1.3s at 80ms ≳ one telemetry period + margin
        val maxTrimPasses: Int = 2,         // forward-only trim arcs before accepting the residual
        val alternatingTrim: Boolean = false, // legacy wiggle finish (kept for sim A/B only)
        // Closed-loop lateral placement onto the target row line.
        val acquireRow: Boolean = true,
        val posTolerance: Float = 0.06f,    // acceptable lateral offset from the row line (m)
        val kAcquireCross: Float = 4.0f,    // aim-lean per metre of lateral offset (rad/m, atan)
        val acquireLeanCap: Float = 0.6f,   // max lean toward the line (radians)
        val acquireMaxTravel: Float = 2.5f, // give up acquiring after this much travel (m)
    )

    private enum class Phase { LEGS, TRIM, SETTLE, ACQUIRE }

    private val s = if (direction == TurnDirection.LEFT) 1f else -1f
    private val pitch = rowPitchMetres.coerceAtLeast(0f)

    /** Grass-safe arc radius, capped to what the drive channel can realise. The K-turn
     * also raises it for wide pitch (solver branch validity, p ≤ 0.6); the U-turn keeps
     * the setting and switches to a plain semicircle once the pitch allows. */
    private val radius = run {
        val cap = 0.95f * params.linearScale / params.turnScale
        when (style) {
            TurnStyle.K_TURN -> min(max(params.turnRadius, pitch / 1.2f), cap)
            TurnStyle.U_TURN -> min(params.turnRadius, cap)
        }
    }

    /** Drive magnitude that realises [radius] at full turn authority (trim/acquire). */
    private val driveMag = driveFor(radius)

    /** Legs: drive sign, rotation direction, signed cumulative-rotation target, arc radius. */
    private val legs: List<Leg> = planLegs()

    private var phase = Phase.LEGS
    private var started = false
    private var targetHeading = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var prevHeading = 0f
    private var totalRot = 0f   // unwrapped TELEMETRY-confirmed rotation (signed)
    private var legIndex = 0
    private var steps = 0

    // Heading predictor: commanded-but-unconfirmed rotation since the last fresh sample.
    private var kAng = 1f                 // learned turn effectiveness (confirmed/commanded)
    private var nomRotSinceFresh = 0f     // commanded rotation since the last fresh sample
    private var lastCmdTurn = 0f          // last turn command we issued

    // TRIM/SETTLE/ACQUIRE state.
    private var trimDriveSign = 1f
    private var trimDist = 0f
    private var trimPasses = 0
    private var settleSteps = 0
    private var settleFreshSeen = false
    private var settleStartX = 0f
    private var settleStartY = 0f
    private var settleStartHeading = 0f
    private var acquireTravel = 0f
    private var trimReentries = 0

    private class Leg(val driveSign: Float, val rotDir: Float, val rotTarget: Float, val radiusM: Float)

    private fun driveFor(r: Float) = (r * params.turnScale / params.linearScale).coerceIn(0.05f, 1f)

    private fun planLegs(): List<Leg> = when (style) {
        TurnStyle.K_TURN -> {
            val a = (PI / 6).toFloat()                  // 30°: fwd-arc apex ≈ the row-end line
            val p = pitch / (2f * radius)
            val bigA = cos(a) - p
            val bigB = sin(a)
            val m = sqrt(bigA * bigA + bigB * bigB)
            val c = (PI + asin((m / 2f).toDouble()) - atan2(bigA.toDouble(), bigB.toDouble())).toFloat()
            val b = acos((bigA + cos(c)).coerceIn(-1f, 1f))
            listOf(
                Leg(-1f, s, s * a, radius),             // ① reverse, nose swings toward the turn
                Leg(1f, s, s * b, radius),              // ② forward main arc
                Leg(-1f, s, s * c, radius),             // ③ reverse again
                Leg(1f, s, s * PI.toFloat(), radius),   // ④ forward, off down the new row
            )
        }
        TurnStyle.U_TURN -> {
            if (pitch >= 2f * radius) {
                // Wide pitch: one forward semicircle of radius pitch/2 lands exactly one pitch over.
                listOf(Leg(1f, s, s * PI.toFloat(), pitch / 2f))
            } else {
                // Teardrop: counter-arc away by θ₀, then one big forward loop to reversed.
                // Two same-radius forward arcs give lateral 2R·cosθ₀ = pitch → cosθ₀ = p.
                val theta0 = acos((pitch / (2f * radius)).coerceIn(0f, 1f))
                listOf(
                    Leg(1f, -s, -s * theta0, radius),   // flick away from the new row
                    Leg(1f, s, s * PI.toFloat(), radius), // the loop, all the way around
                )
            }
        }
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
            val d = angleDelta(prevHeading, pose.heading)
            prevHeading = pose.heading
            if (d != 0f) {
                // Fresh telemetry: learn how effective our turn commands really are.
                totalRot += d
                if (params.usePrediction && abs(nomRotSinceFresh) > 0.03f) {
                    kAng = (kAng + KANG_ALPHA * (d / nomRotSinceFresh - kAng)).coerceIn(0.3f, 1.7f)
                }
                nomRotSinceFresh = 0f
            }
        }
        // Accumulate the rotation we commanded last tick (confirmed only via telemetry).
        nomRotSinceFresh += lastCmdTurn * params.turnScale * dtSeconds

        steps++
        if (steps >= params.maxSteps) return ManeuverCommand.DONE

        val cmd = when (phase) {
            Phase.LEGS -> stepLegs()
            Phase.TRIM -> stepTrim(pose, dtSeconds)
            Phase.SETTLE -> stepSettle(pose)
            Phase.ACQUIRE -> stepAcquire(pose, dtSeconds)
        }
        lastCmdTurn = if (cmd.done) 0f else cmd.turn
        return cmd
    }

    /** Best estimate of rotation so far: telemetry + predicted-unconfirmed (+ latency tail). */
    private fun effRot(): Float =
        if (params.usePrediction) {
            totalRot + kAng * (nomRotSinceFresh + lastCmdTurn * params.turnScale * params.telemetryLatencyS)
        } else {
            totalRot
        }

    /** Rotation still expected after cutting the command, from actuation lag. */
    private fun stopLead(): Float =
        if (params.usePrediction) lastCmdTurn * params.turnScale * kAng * params.actuationLagS else 0f

    private fun stepLegs(): ManeuverCommand {
        val rot = effRot() + stopLead()
        while (legIndex < legs.size && reached(legs[legIndex], rot)) legIndex++
        if (legIndex >= legs.size) {
            phase = Phase.TRIM
            return ManeuverCommand(0f, 0f, done = false) // let it coast into the finish check
        }
        val leg = legs[legIndex]
        // Approach-rate profiling: slow rotation near the breakpoint so a blind telemetry
        // window can't sweep far past it. Drive scales with turn → arc radius preserved.
        val f = if (params.approachHorizon > 0f) {
            val remaining = (leg.rotDir * leg.rotTarget - leg.rotDir * rot).coerceAtLeast(0f)
            (remaining / params.approachHorizon).coerceIn(params.minApproachFrac, 1f)
        } else 1f
        return ManeuverCommand(leg.driveSign * driveFor(leg.radiusM) * f, leg.rotDir * f, done = false)
    }

    /** Under a persistent yaw disturbance the mower cannot HOLD ±tol while stopped; after
     * a few trim↔settle cycles we accept a widened band rather than loop forever. */
    private fun effectiveTolerance(): Float =
        if (trimReentries > MAX_TRIM_REENTRIES) 2f * params.headingTolerance else params.headingTolerance

    private fun stepTrim(pose: Pose, dtSeconds: Float): ManeuverCommand {
        // Predicted current heading error (telemetry error minus unconfirmed rotation).
        val errNow = angleDelta(pose.heading, targetHeading) - (effRot() - totalRot) - stopLead()
        if (abs(errNow) <= effectiveTolerance() || trimPasses >= params.maxTrimPasses) {
            enterSettle(pose)
            return ManeuverCommand(0f, 0f, done = false)
        }
        trimDist += driveMag * params.linearScale * dtSeconds
        if (params.alternatingTrim) {
            // Legacy finish (sim A/B only): short alternating fwd/back arcs.
            if (trimDist >= TRIM_LEG_FRACTION * radius) {
                trimDist = 0f
                trimDriveSign = -trimDriveSign
            }
        } else {
            trimDriveSign = 1f // forward-only — no back-and-forth wiggle on the lawn
        }
        val turnMag = max(params.minTrimTurn, min(1f, params.kTrim * abs(errNow)))
        val turn = if (errNow >= 0f) turnMag else -turnMag
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
        if (settleFreshSeen && abs(err) > effectiveTolerance() && trimPasses < params.maxTrimPasses) {
            // A fresh sample shows we're outside tolerance — one more (forward) trim pass.
            trimReentries++
            trimPasses++
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
        // (same scheme as a line-follower), driving forward down the new row.
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

    private fun reached(leg: Leg, rot: Float): Boolean =
        leg.rotDir * rot >= leg.rotDir * leg.rotTarget - ROT_EPS

    private companion object {
        const val ROT_EPS = 1e-3f
        const val TRIM_LEG_FRACTION = 0.4f // legacy alternating trim: flip every 0.4·R of travel
        const val MAX_TRIM_REENTRIES = 4   // then widen tolerance (can't hold ±tol on a yawing slope)
        const val KANG_ALPHA = 0.35f       // learning rate for the turn-effectiveness estimate
    }
}

/** Smallest signed rotation (radians) from [from] to [to], in (−π, π]. */
fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % (2f * PI.toFloat())
    if (d <= -PI.toFloat()) d += 2f * PI.toFloat()
    if (d > PI.toFloat()) d -= 2f * PI.toFloat()
    return d
}
