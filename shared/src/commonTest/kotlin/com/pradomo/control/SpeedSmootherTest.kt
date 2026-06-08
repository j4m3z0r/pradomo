package com.pradomo.control

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedSmootherTest {
    private val tol = 1e-5f

    @Test fun rampsTowardTargetByRateTimesDt() {
        val s = SpeedSmoother(ratePerSec = 1f)
        assertEquals(0.1f, s.step(1f, 0f, 0.1f).first, tol)
        assertEquals(0.2f, s.step(1f, 0f, 0.1f).first, tol)
    }

    @Test fun doesNotOvershootTarget() {
        val s = SpeedSmoother(1f)
        // maxDelta 0.1 exceeds the remaining 0.05 → land exactly on target.
        assertEquals(0.05f, s.step(0.05f, 0f, 0.1f).first, tol)
    }

    @Test fun deceleratesToZero() {
        val s = SpeedSmoother(1f).apply { reset(1f, 0f) }
        assertEquals(0.9f, s.step(0f, 0f, 0.1f).first, tol)
    }

    @Test fun rampsThroughZeroOnReversal() {
        val s = SpeedSmoother(1f).apply { reset(0.05f, 0f) }
        assertEquals(-0.05f, s.step(-1f, 0f, 0.1f).first, tol)
    }

    @Test fun resetIsInstant() {
        val s = SpeedSmoother(1f).apply { reset(0.8f, -0.3f) }
        val (l, a) = s.step(0.8f, -0.3f, 0.1f) // already at target
        assertEquals(0.8f, l, tol)
        assertEquals(-0.3f, a, tol)
    }

    @Test fun angularIsSmoothedToo() {
        val s = SpeedSmoother(1f)
        assertEquals(0.1f, s.step(0f, 1f, 0.1f).second, tol)
    }
}
