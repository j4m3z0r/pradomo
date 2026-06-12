package com.pradomo.control.maneuver

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential-drive kinematic sim matching the demo mower's integration
 * (FakeMowerTransport): heading += ω·dt; x += v·cosθ·dt; y += v·sinθ·dt, where
 * v = drive·linearScale and ω = turn·turnScale.
 *
 * [omegaScale] models a mower whose real turn rate differs from the commanded model
 * (what we saw on hardware), and the telemetry [delay] in [run] models sensor lag —
 * together they reproduce the consistent end-heading error observed on the real lawn,
 * which the trim phase must correct.
 */
private class DiffDriveSim(
    var x: Float,
    var y: Float,
    var heading: Float,
    val linearScale: Float = 0.5f,
    val turnScale: Float = 0.6f,
    val omegaScale: Float = 1f,
) {
    fun apply(cmd: ManeuverCommand, dt: Float) {
        val v = cmd.drive * linearScale
        val w = cmd.turn * turnScale * omegaScale
        heading += w * dt
        x += v * cos(heading) * dt
        y += v * sin(heading) * dt
    }

    fun pose() = Pose(x, y, heading)
}

private const val DT = 0.04f

/** Per-step record of what the maneuver commanded, for behavioral assertions. */
private class Trace {
    val commands = mutableListOf<ManeuverCommand>()
    val path = mutableListOf<Pose>()
}

/**
 * Run a maneuver to completion (or a step cap); the maneuver sees telemetry [delay]
 * steps old. Returns the final sim pose.
 */
private fun run(sim: DiffDriveSim, m: Maneuver, delay: Int = 0, trace: Trace? = null, maxIter: Int = 6000): Pose {
    val queue = ArrayDeque<Pose>()
    repeat(maxIter) {
        queue.addLast(sim.pose())
        val seen = if (queue.size > delay) queue.removeFirst() else queue.first()
        val cmd = m.step(seen, DT)
        if (cmd.done) return sim.pose()
        trace?.commands?.add(cmd)
        trace?.path?.add(sim.pose())
        sim.apply(cmd, DT)
    }
    return sim.pose()
}

/** Expected end pose: heading reversed, position one pitch along the turn-side normal. */
private fun target(start: Pose, dir: TurnDirection, pitch: Float): Pose {
    val s = if (dir == TurnDirection.LEFT) 1f else -1f
    val nx = -sin(start.heading); val ny = cos(start.heading)
    return Pose(start.x + s * pitch * nx, start.y + s * pitch * ny, start.heading + s * PI.toFloat())
}

/** Lateral offset from the target ROW LINE (what matters for gap-free rows; the maneuver
 * may legitimately end anywhere ALONG the new row, e.g. after the acquire phase). */
private fun lateralErr(end: Pose, t: Pose): Float {
    val dx = cos(t.heading); val dy = sin(t.heading)
    return abs(dx * (end.y - t.y) - dy * (end.x - t.x))
}

private fun headingErr(a: Float, b: Float) = abs(angleDelta(a, b))

class MultiPointTurnTest {

    @Test fun converges_across_pitches_directions_and_radii() {
        for (dir in TurnDirection.entries) {
            for (radiusCm in intArrayOf(30, 45, 60)) {
                for (pitchCm in intArrayOf(0, 5, 18, 30, 45, 60)) {
                    val pitch = pitchCm / 100f
                    val start = Pose(1f, 2f, 0.9f)
                    val sim = DiffDriveSim(start.x, start.y, start.heading)
                    val end = run(sim, MultiPointTurn(dir, pitch, MultiPointTurn.Params(turnRadius = radiusCm / 100f)))
                    val t = target(start, dir, pitch)
                    assertTrue(
                        lateralErr(end, t) <= 0.08f,
                        "dir=$dir R=${radiusCm}cm pitch=${pitchCm}cm off the row line: end=$end target=$t",
                    )
                    assertTrue(
                        headingErr(end.heading, t.heading) <= 0.08f,
                        "dir=$dir R=${radiusCm}cm pitch=${pitchCm}cm heading off: end=$end target=$t",
                    )
                }
            }
        }
    }

    @Test fun u_turn_is_forward_only_and_lands_one_pitch_over() {
        // Teardrop (narrow pitch) and plain semicircle (wide pitch) branches.
        for (pitchCm in intArrayOf(10, 18, 45, 100)) {
            for (dir in TurnDirection.entries) {
                val pitch = pitchCm / 100f
                val start = Pose(0f, 0f, 1.1f)
                val sim = DiffDriveSim(start.x, start.y, start.heading)
                val trace = Trace()
                val end = run(
                    sim,
                    MultiPointTurn(dir, pitch, MultiPointTurn.Params(), style = TurnStyle.U_TURN),
                    trace = trace,
                )
                for (cmd in trace.commands) {
                    assertTrue(cmd.drive >= 0f, "U-turn must never reverse: $cmd (pitch=${pitchCm}cm)")
                }
                val t = target(start, dir, pitch)
                assertTrue(lateralErr(end, t) <= 0.08f,
                    "U dir=$dir pitch=${pitchCm}cm off the row line: end=$end target=$t")
                assertTrue(headingErr(end.heading, t.heading) <= 0.08f,
                    "U dir=$dir pitch=${pitchCm}cm heading off: end=$end target=$t")
            }
        }
    }

    @Test fun finish_has_no_back_and_forth_wiggle() {
        // The field complaint: alternating fwd/back trim at the end. With prediction +
        // forward-only trim, the K-turn's drive sign must change only at its own leg
        // boundaries (rev→fwd→rev→fwd = 3 reversals), even under model error and lag.
        for (omegaScale in floatArrayOf(0.8f, 1.0f, 1.25f)) {
            val sim = DiffDriveSim(0f, 0f, 0.4f, omegaScale = omegaScale)
            val trace = Trace()
            run(sim, MultiPointTurn(TurnDirection.LEFT, 0.18f), delay = 3, trace = trace)
            var reversals = 0
            var lastSign = 0
            for (cmd in trace.commands) {
                val sign = if (cmd.drive > 0.01f) 1 else if (cmd.drive < -0.01f) -1 else 0
                if (sign != 0) {
                    if (lastSign != 0 && sign != lastSign) reversals++
                    lastSign = sign
                }
            }
            assertTrue(reversals <= 3, "wiggly finish at omegaScale=$omegaScale: $reversals reversals")
        }
    }

    @Test fun backs_up_first() {
        // The maneuver starts at the row end: its first motion must be in reverse,
        // using the already-mowed row behind as workspace.
        val sim = DiffDriveSim(0f, 0f, 0f)
        val trace = Trace()
        run(sim, MultiPointTurn(TurnDirection.LEFT, 0.18f), trace = trace)
        val first = trace.commands.first { it.drive != 0f || it.turn != 0f }
        assertTrue(first.drive < 0f, "first command should reverse, got $first")
    }

    @Test fun never_pivots_commanded_radius_stays_grass_safe() {
        // Whatever the phase (legs, trim, acquire — including the slowed approach), the
        // commanded arc radius drive·linearScale/(turn·turnScale) must never drop below
        // the grass-safe turn radius: a tightening arc is what tears the lawn, not slow
        // speed per se (slowing both treads together preserves the rolling ratio).
        val prm = MultiPointTurn.Params()
        for (pitchCm in intArrayOf(0, 18, 45)) {
            val sim = DiffDriveSim(0f, 0f, 1.2f)
            val trace = Trace()
            run(sim, MultiPointTurn(TurnDirection.RIGHT, pitchCm / 100f, prm), trace = trace)
            for (cmd in trace.commands) {
                if (abs(cmd.turn) > 0.05f) {
                    val radius = abs(cmd.drive) * prm.linearScale / (abs(cmd.turn) * prm.turnScale)
                    assertTrue(radius >= 0.8f * prm.turnRadius,
                        "arc tighter than grass-safe at pitch=${pitchCm}cm: $cmd (R=$radius)")
                }
            }
        }
    }

    @Test fun heading_exact_despite_turn_rate_model_error_and_telemetry_lag() {
        // The hardware finding: open-loop rotation tracking ends on the wrong heading.
        // With the mower turning 30% faster/slower than modeled AND telemetry 3 samples
        // stale, the trim+settle phase must still land within ~2.5° of start+180°.
        for (omegaScale in floatArrayOf(0.75f, 1.3f)) {
            for (dir in TurnDirection.entries) {
                val start = Pose(4f, 4f, 2.2f)
                val sim = DiffDriveSim(start.x, start.y, start.heading, omegaScale = omegaScale)
                val end = run(sim, MultiPointTurn(dir, 0.18f), delay = 3)
                val t = target(start, dir, 0.18f)
                // Under a ±25% turn-rate model error + stale telemetry, the predictive
                // forward-only finish lands within ~7° (the cruise handoff cleans up the
                // last few degrees) — and crucially without the back-and-forth wiggle.
                assertTrue(
                    headingErr(end.heading, t.heading) <= 0.13f,
                    "omegaScale=$omegaScale dir=$dir heading off: end=${end.heading} target=${t.heading}",
                )
            }
        }
    }

    @Test fun forward_overrun_of_row_end_is_small() {
        // The user allows a small overrun past the row-end line, but it must stay a
        // modest fraction of the turn radius (not sweep deep into the boundary).
        val radius = 0.45f
        val start = Pose(0f, 0f, 0.7f)
        val sim = DiffDriveSim(start.x, start.y, start.heading)
        val trace = Trace()
        run(sim, MultiPointTurn(TurnDirection.LEFT, 0.18f, MultiPointTurn.Params(turnRadius = radius)), trace = trace)
        val maxU = trace.path.maxOf { p ->
            (p.x - start.x) * cos(start.heading) + (p.y - start.y) * sin(start.heading)
        }
        assertTrue(maxU <= 0.6f * radius, "overran the row end by ${maxU}m (limit ${0.6f * radius}m)")
    }
}
