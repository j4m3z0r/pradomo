package com.pradomo.control

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadingHoldTest {
    private val W = 0.6f // rad/s at turn = 1 (matches the protocol turn scale)
    private val DT = 0.08f

    @Test fun zero_when_unlatched_or_aligned() {
        val hold = HeadingHold()
        assertEquals(0f, hold.correction(1.234f), 0f)
        hold.latch(0.7f)
        assertEquals(0f, hold.correction(0.7f), 0f)
    }

    @Test fun correction_signs_steer_back_toward_latched_heading() {
        val hold = HeadingHold()
        hold.latch(1.0f)
        assertTrue(hold.correction(1.2f) < 0f, "drifted CCW → must steer CW (negative turn)")
        assertTrue(hold.correction(0.8f) > 0f, "drifted CW → must steer CCW (positive turn)")
    }

    @Test fun correction_is_capped() {
        val hold = HeadingHold(turnCap = 0.4f)
        hold.latch(0f)
        assertEquals(0.4f, abs(hold.correction(PI.toFloat() / 2f)))
    }

    @Test fun holds_heading_against_constant_drift() {
        // Mower drifts at 0.1 rad/s (slope); the assist must keep heading near latched.
        val hold = HeadingHold()
        var heading = 0.5f
        hold.latch(heading)
        var maxErr = 0f
        repeat(500) {
            heading += 0.1f * DT                      // external drift
            heading += hold.correction(heading) * W * DT // assist
            maxErr = maxOf(maxErr, abs(heading - 0.5f))
        }
        assertTrue(maxErr < 0.2f, "heading drifted too far: $maxErr rad")
        // Steady state: drift and correction balance well inside the cap.
        assertTrue(abs(heading - 0.5f) < 0.2f)
    }

    @Test fun relatch_uses_the_new_reference() {
        val hold = HeadingHold()
        hold.latch(0f)
        assertTrue(hold.correction(0.3f) != 0f)
        hold.unlatch()
        assertEquals(0f, hold.correction(0.3f), 0f)
        hold.latch(0.3f)
        assertEquals(0f, hold.correction(0.3f), 0f)
    }
}
