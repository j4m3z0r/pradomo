package com.pradomo.control.maneuver

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drive cruise through a constant heading disturbance (a "slope" that keeps nudging the
 * mower off course) and check the optional auto-correction holds the start line, while the
 * uncorrected mower drifts away. Sim integration matches the demo mower (FakeMowerTransport).
 */
class CruiseManeuverTest {
    private val dt = 0.04f
    private val V = 0.5f
    private val W = 0.6f

    /** Run cruise with an external heading drift each tick; return final cross-track |y|. */
    private fun runDrift(drive: Float, correct: Boolean, driftRadPerSec: Float): Pair<Float, Float> {
        var x = 0f; var y = 0f; var h = 0f
        val cruise = CruiseManeuver(drive, correct)
        repeat(500) {
            val cmd = cruise.step(Pose(x, y, h), dt)
            h += cmd.turn * W * dt
            x += cmd.drive * V * cos(h) * dt
            y += cmd.drive * V * sin(h) * dt
            h += driftRadPerSec * dt // external slip/slope disturbance
        }
        return x to y
    }

    @Test fun uncorrected_cruise_commands_straight() {
        val cruise = CruiseManeuver(0.5f, correct = false)
        repeat(20) {
            val cmd = cruise.step(Pose(it * 0.1f, 0.3f, 0.2f), dt)
            assertTrue(cmd.turn == 0f && cmd.drive == 0.5f && !cmd.done)
        }
    }

    @Test fun correction_holds_the_line_against_drift_forward() {
        val (x, y) = runDrift(drive = 0.5f, correct = true, driftRadPerSec = 0.15f)
        assertTrue(x > 1f, "should have advanced forward, x=$x")
        assertTrue(abs(y) < 0.3f, "cross-track should stay bounded near the line, y=$y")
    }

    @Test fun without_correction_the_mower_drifts_far_off_line() {
        val (_, y) = runDrift(drive = 0.5f, correct = false, driftRadPerSec = 0.15f)
        assertTrue(abs(y) > 1f, "uncorrected cruise should veer well off the line, y=$y")
    }

    @Test fun correction_holds_the_line_in_reverse() {
        val (x, y) = runDrift(drive = -0.5f, correct = true, driftRadPerSec = 0.15f)
        assertTrue(x < -1f, "should have advanced backward, x=$x")
        assertTrue(abs(y) < 0.35f, "cross-track should stay bounded in reverse, y=$y")
    }
}
