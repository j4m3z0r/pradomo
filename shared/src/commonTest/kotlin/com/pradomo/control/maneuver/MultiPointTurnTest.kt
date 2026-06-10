package com.pradomo.control.maneuver

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential-drive kinematic sim, identical to the demo mower's integration
 * (FakeMowerTransport): heading += ω·dt; x += v·cosθ·dt; y += v·sinθ·dt, where
 * v = drive·linearScale and ω = turn·turnScale. An optional [minRadius] models a mower
 * that physically can't turn as tight as commanded (ω is clamped down).
 */
private class DiffDriveSim(
    var x: Float,
    var y: Float,
    var heading: Float,
    val linearScale: Float = 0.5f,
    val turnScale: Float = 0.6f,
    val minRadius: Float = 0f,
) {
    fun apply(cmd: ManeuverCommand, dt: Float) {
        val v = cmd.drive * linearScale
        var w = cmd.turn * turnScale
        if (minRadius > 0f && w != 0f && v != 0f && abs(v / w) < minRadius) {
            w = sign(w) * abs(v) / minRadius
        }
        heading += w * dt
        x += v * cos(heading) * dt
        y += v * sin(heading) * dt
    }

    fun pose() = Pose(x, y, heading)
}

private const val DT = 0.04f

/** Run a maneuver to completion (or a step cap) and return the final sim pose. */
private fun run(sim: DiffDriveSim, m: Maneuver, maxIter: Int = 4000): Pose {
    repeat(maxIter) {
        val cmd = m.step(sim.pose(), DT)
        if (cmd.done) return sim.pose()
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

private fun headingErr(a: Float, b: Float) = abs(angleDelta(a, b))

class MultiPointTurnTest {

    @Test fun left_turn_wide_pitch_single_arc_lands_one_pitch_over() {
        // pitch >= 2*minRadius -> a single forward semicircle.
        val start = Pose(2f, 1f, 0.5f)
        val sim = DiffDriveSim(start.x, start.y, start.heading)
        val pitch = 0.6f
        val end = run(sim, MultiPointTurn(TurnDirection.LEFT, pitch))
        val t = target(start, TurnDirection.LEFT, pitch)
        assertTrue(hypot(end.x - t.x, end.y - t.y) <= 0.12f, "pos off: end=$end target=$t")
        assertTrue(headingErr(end.heading, t.heading) <= 0.12f, "heading off: $end vs $t")
    }

    @Test fun left_turn_tight_pitch_three_point_lands_one_pitch_over() {
        // pitch < 2*minRadius -> genuine 3-point turn (forward, reverse, forward).
        val start = Pose(-3f, 5f, 2.2f)
        val sim = DiffDriveSim(start.x, start.y, start.heading)
        val pitch = 0.2f
        val end = run(sim, MultiPointTurn(TurnDirection.LEFT, pitch))
        val t = target(start, TurnDirection.LEFT, pitch)
        assertTrue(hypot(end.x - t.x, end.y - t.y) <= 0.12f, "pos off: end=$end target=$t")
        assertTrue(headingErr(end.heading, t.heading) <= 0.12f, "heading off: $end vs $t")
    }

    @Test fun right_turn_mirrors_left() {
        val start = Pose(0f, 0f, 0f)
        val sim = DiffDriveSim(start.x, start.y, start.heading)
        val pitch = 0.3f
        val end = run(sim, MultiPointTurn(TurnDirection.RIGHT, pitch))
        val t = target(start, TurnDirection.RIGHT, pitch)
        assertTrue(hypot(end.x - t.x, end.y - t.y) <= 0.12f, "pos off: end=$end target=$t")
        assertTrue(headingErr(end.heading, t.heading) <= 0.12f, "heading off: $end vs $t")
        // A right turn must end on the right side of travel (negative v in the start frame).
        val v = -(end.x - start.x) * sin(start.heading) + (end.y - start.y) * cos(start.heading)
        assertTrue(v < 0f, "expected rightward lateral shift, got v=$v")
    }

    @Test fun converges_across_a_range_of_pitches() {
        for (pitchCm in intArrayOf(0, 5, 15, 25, 40, 60, 90)) {
            val pitch = pitchCm / 100f
            val start = Pose(1f, 2f, 0.9f)
            val sim = DiffDriveSim(start.x, start.y, start.heading)
            val end = run(sim, MultiPointTurn(TurnDirection.LEFT, pitch))
            val t = target(start, TurnDirection.LEFT, pitch)
            assertTrue(
                hypot(end.x - t.x, end.y - t.y) <= 0.12f && headingErr(end.heading, t.heading) <= 0.12f,
                "pitch=${pitch}m did not converge: end=$end target=$t",
            )
        }
    }

    @Test fun terminates_and_reverses_heading_even_when_min_radius_infeasible() {
        // Mower physically can't turn tighter than 0.45m, but the plan wants 0.25m.
        // It can't hit the lateral target, but it must still finish with heading reversed
        // (no spinning forever).
        val start = Pose(4f, 4f, 1.0f)
        val sim = DiffDriveSim(start.x, start.y, start.heading, minRadius = 0.45f)
        val end = run(sim, MultiPointTurn(TurnDirection.LEFT, 0.2f))
        val t = target(start, TurnDirection.LEFT, 0.2f)
        assertTrue(headingErr(end.heading, t.heading) <= 0.2f, "heading not reversed: $end vs $t")
    }

    @Test fun cruise_holds_a_steady_command_and_never_self_completes() {
        val sim = DiffDriveSim(0f, 0f, 0f)
        val cruise = CruiseManeuver(0.5f)
        repeat(50) {
            val cmd = cruise.step(sim.pose(), DT)
            assertTrue(!cmd.done && cmd.drive == 0.5f && cmd.turn == 0f)
            sim.apply(cmd, DT)
        }
        assertTrue(sim.x > 0f, "cruise should have moved the mower forward")
    }
}
