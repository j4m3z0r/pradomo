package com.pradomo.control.maneuver

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Closed-loop K-turn: reverse the mower's heading by 180° and leave it one row-pitch
 * over, so it can mow the adjacent strip with no gap (pitch = cutting width − overlap).
 *
 * Why not a plain arc U-turn: to land exactly one pitch over, a 180° forward arc would
 * need radius = pitch/2, which is below a differential-drive mower's tightest
 * non-scrubbing radius for narrow rows — so it either overshoots (gaps) or scrubs the
 * grass. A three-point turn (forward arc, reverse arc, forward arc, all curving the same
 * way) reverses heading with an arbitrarily small lateral shift and never scrubs.
 *
 * Geometry (row frame: u = start heading, v = left; left turn shown, s = +1):
 *   Each leg is a circular arc of radius R, heading always advancing the same way.
 *   Forward leg over heading φ: Δu = R·sinφ contribution, Δv = R·(1−cosφ) ; reverse
 *   legs integrate with ds < 0. Solving end pose = (u=0, v=s·pitch, heading=s·π):
 *     R = max(minRadius, pitch/2)
 *     single forward semicircle when pitch ≥ 2·minRadius (R = pitch/2), else legs at
 *     θ1 = acos(½ − pitch/(4R)), θ2 = π − θ1, π  (forward, reverse, forward).
 *   Leg transitions fire on *accumulated heading from telemetry*, not timers, so the
 *   turn tracks the mower's real rotation. Drive magnitude is derived so the commanded
 *   turn stays at full authority at the chosen radius.
 *
 * Sign convention matches the app: drive +forward/−back, turn +left (CCW); heading is
 * radians CCW with east = 0 (same frame as telemetry and the demo sim).
 *
 * Precise lateral placement holds under the nominal kinematic model; on the real mower
 * the scales ([Params.linearScale]/[Params.turnScale]) and min radius need calibrating —
 * a closed-loop position corrector is a follow-up (see [Params.maxSteps] safety cap).
 */
class MultiPointTurn(
    direction: TurnDirection,
    rowPitchMetres: Float,
    private val params: Params = Params(),
) : Maneuver {

    data class Params(
        val turnScale: Float = 0.6f,     // rad/s at turn = 1.0 (LymowProtocol.TURN_LIMIT)
        val linearScale: Float = 0.5f,   // m/s at drive = 1.0 (nominal; SpeedMode.NORMAL)
        val minRadius: Float = 0.25f,    // tightest non-scrubbing arc we'll command (m)
        val posTolerance: Float = 0.08f, // metres — early "arrived" check
        val headingTolerance: Float = 0.12f, // radians (~7°)
        val maxSteps: Int = 1500,        // hard safety cap (~120s at 80ms) → finish
    )

    private val s = if (direction == TurnDirection.LEFT) 1f else -1f
    private val pitch = rowPitchMetres.coerceAtLeast(0f)

    /** Arc radius and the normalised drive magnitude that realises it at full turn. */
    private val radius = maxOf(params.minRadius, pitch / 2f)
    private val driveMag = (radius * params.turnScale / params.linearScale).coerceIn(0.05f, 1f)

    /** Legs: signed accumulated-rotation target at leg end, and the drive sign for the leg. */
    private val legs: List<Leg> = planLegs()

    private var started = false
    private var startX = 0f
    private var startY = 0f
    private var startHeading = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var targetHeading = 0f

    private var prevHeading = 0f
    private var totalRot = 0f   // unwrapped accumulated rotation (signed; same sign as s)
    private var legIndex = 0
    private var steps = 0

    private class Leg(val driveSign: Float, val rotTarget: Float)

    private fun planLegs(): List<Leg> {
        // pitch ≥ 2·minRadius → a single forward semicircle of radius pitch/2 suffices.
        if (pitch >= 2f * params.minRadius) return listOf(Leg(1f, s * PI.toFloat()))
        // Tight pitch → three-point turn at the minimum radius.
        val cosT1 = (0.5f - pitch / (4f * radius)).coerceIn(-1f, 1f)
        val t1 = acos(cosT1)
        val t2 = PI.toFloat() - t1
        return listOf(
            Leg(1f, s * t1),
            Leg(-1f, s * t2),
            Leg(1f, s * PI.toFloat()),
        )
    }

    override fun step(pose: Pose, dtSeconds: Float): ManeuverCommand {
        if (!started) {
            started = true
            startX = pose.x; startY = pose.y; startHeading = pose.heading
            prevHeading = pose.heading
            totalRot = 0f
            // Target: heading reversed, position shifted one pitch along the turn-side normal.
            // Left normal of the start heading = (−sin h, cos h); s flips it for a right turn.
            val nx = -sin(startHeading); val ny = cos(startHeading)
            targetX = startX + s * pitch * nx
            targetY = startY + s * pitch * ny
            targetHeading = startHeading + s * PI.toFloat()
        } else {
            totalRot += angleDelta(prevHeading, pose.heading)
            prevHeading = pose.heading
        }

        steps++
        if (steps >= params.maxSteps) return ManeuverCommand.DONE

        // Arrived (handles the single-arc case and tight model tracking).
        val posErr = hypot(targetX - pose.x, targetY - pose.y)
        val headErr = abs(angleDelta(pose.heading, targetHeading))
        if (posErr <= params.posTolerance && headErr <= params.headingTolerance) {
            return ManeuverCommand.DONE
        }

        // Advance past any legs whose rotation target the mower has already swept through.
        while (legIndex < legs.size && reached(legs[legIndex])) legIndex++
        // Planned rotation complete but pose not yet in tolerance: nominal model is
        // exhausted (real-world drift). Stop rather than spin — corrector is a follow-up.
        if (legIndex >= legs.size) return ManeuverCommand.DONE

        val leg = legs[legIndex]
        return ManeuverCommand(leg.driveSign * driveMag, s * 1f, done = false)
    }

    private fun reached(leg: Leg): Boolean = s * totalRot >= s * leg.rotTarget - ROT_EPS

    private companion object {
        const val ROT_EPS = 1e-3f
    }
}

/** Smallest signed rotation (radians) from [from] to [to], in (−π, π]. */
internal fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % (2f * PI.toFloat())
    if (d <= -PI.toFloat()) d += 2f * PI.toFloat()
    if (d > PI.toFloat()) d -= 2f * PI.toFloat()
    return d
}
