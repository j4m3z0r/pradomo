package com.pradomo.control

import com.pradomo.control.maneuver.Pose
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** Truth mower: integrates commands at a fixed effectiveness (eff<1 = slip/poor traction). */
private class TruthSim(var x: Float = 0f, var y: Float = 0f, var h: Float = 0f,
                       val lin: Float = 0.5f, val ang: Float = 0.6f, val eff: Float = 1f) {
    fun apply(drive: Float, turn: Float, dt: Float) {
        h += turn * ang * eff * dt
        val v = drive * lin * eff
        x += v * cos(h) * dt; y += v * sin(h) * dt
    }
    fun pose() = Pose(x, y, h)
}

private fun dist(a: Pose, b: Pose) = hypot(a.x - b.x, a.y - b.y)

class PoseEstimatorTest {
    private val DT = 0.08f

    @Test fun forward_model_integrates_commanded_motion() {
        val est = PoseEstimator(linearScale = 0.5f, turnScale = 0.6f)
        est.observe(Pose(0f, 0f, 0f), 0f, 0f) // anchor
        est.predict(DT, drive = 1f, turn = 0f, dt = DT)
        // nominal forward: 1 * 0.5 * kLin(=1) * 0.08 = 0.04 m along heading 0
        assertEquals(0.04f, est.estimate().x, 1e-4f)
        assertEquals(0f, est.estimate().y, 1e-4f)
    }

    @Test fun predict_does_nothing_until_anchored() {
        val est = PoseEstimator(0.5f, 0.6f)
        est.predict(DT, 1f, 0f, DT)
        assertEquals(0f, est.estimate().x, 0f)
    }

    @Test fun reconcile_snaps_to_truth() {
        val est = PoseEstimator(0.5f, 0.6f)
        est.observe(Pose(0f, 0f, 0f), 0f, 0f)
        est.predict(DT, 1f, 0f, DT)
        // a fresh sample at the current instant must place the estimate exactly on truth
        est.observe(Pose(3f, -2f, 1.1f), 2 * DT, 2 * DT)
        assertEquals(3f, est.estimate().x, 1e-5f)
        assertEquals(-2f, est.estimate().y, 1e-5f)
        assertEquals(1.1f, est.estimate().heading, 1e-5f)
    }

    @Test fun learns_low_traction_from_recent_samples() {
        // Truth moves at 60% of commanded; the estimator should learn kLin/kAng ≈ 0.6.
        val truth = TruthSim(eff = 0.6f)
        val est = PoseEstimator(0.5f, 0.6f)
        est.observe(truth.pose(), 0f, 0f)
        var t = 0f; var nextSample = 0.6f
        repeat(120) {
            t += DT
            est.predict(t, drive = 0.8f, turn = 0.3f, dt = DT)
            truth.apply(0.8f, 0.3f, DT)
            if (t >= nextSample) { est.observe(truth.pose(), t, t); nextSample += 0.6f }
        }
        assertTrue(est.kLin in 0.5f..0.72f, "kLin did not converge to ~0.6: ${est.kLin}")
        assertTrue(est.kAng in 0.5f..0.72f, "kAng did not converge to ~0.6: ${est.kAng}")
    }

    @Test fun tracks_truth_better_than_stale_telemetry() {
        // The whole point: between 600ms samples the estimate should track the moving mower
        // far better than the frozen last sample.
        val truth = TruthSim(eff = 0.6f)
        val est = PoseEstimator(0.5f, 0.6f)
        est.observe(truth.pose(), 0f, 0f)
        var last = truth.pose()
        var t = 0f; var nextSample = 0.6f
        var sumEst = 0f; var sumRaw = 0f; var n = 0
        repeat(300) {
            t += DT
            est.predict(t, drive = 0.7f, turn = 0.25f, dt = DT)
            truth.apply(0.7f, 0.25f, DT)
            if (t >= nextSample) { est.observe(truth.pose(), t, t); last = truth.pose(); nextSample += 0.6f }
            sumEst += dist(est.estimate(), truth.pose())
            sumRaw += dist(last, truth.pose())
            n++
        }
        val mEst = sumEst / n; val mRaw = sumRaw / n
        assertTrue(mEst < 0.5f * mRaw, "estimator ($mEst) not clearly better than raw ($mRaw)")
    }
}
