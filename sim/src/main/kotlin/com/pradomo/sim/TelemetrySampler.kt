package com.pradomo.sim

import com.pradomo.control.maneuver.Pose
import kotlin.random.Random

/**
 * Models the mower's telemetry channel: low-rate (default ~1.6 Hz, measured on hardware),
 * jittered, latent and noisy. Each emitted sample is the true pose as it was [latencyMs]
 * ago, captured only every ~[periodMs]. The controller reads [latest] each control tick,
 * which holds the previous sample between updates (as the app does).
 */
class TelemetrySampler(
    private val periodMs: Float = NOMINAL_PERIOD_MS,
    private val jitterMs: Float = 30f,
    private val latencyMs: Float = 120f,
    private val posNoise: Float = 0.01f,
    private val headingNoise: Float = 0.01f,
    seed: Long = 7L,
) {
    /** A delivered sample plus the times it represents and was delivered. */
    data class Sample(val pose: Pose, val sampleT: Float, val deliveredT: Float)

    private val rng = Random(seed)
    private val history = ArrayList<Pair<Float, Pose>>() // (t, true pose)
    private var nextSampleT = 0f
    private var latest: Sample? = null
    private var justDelivered: Sample? = null

    /** Record the true pose at time [t] and possibly deliver a new (delayed) sample. */
    fun tick(t: Float, truth: Pose) {
        history.add(t to truth)
        // keep ~3s of history
        while (history.size > 2 && history.first().first < t - 3f) history.removeAt(0)
        justDelivered = null
        if (t >= nextSampleT) {
            val sampleT = t - latencyMs / 1000f
            val truePose = poseAt(sampleT)
            val noisy = Pose(
                truePose.x + noise(posNoise),
                truePose.y + noise(posNoise),
                truePose.heading + noise(headingNoise),
            )
            val s = Sample(noisy, sampleT, t)
            latest = s; justDelivered = s
            nextSampleT = t + (periodMs + noise(jitterMs)) / 1000f
        }
    }

    /** The most recent delivered sample (what the controller currently "knows"). */
    fun latest(): Sample? = latest

    /** Non-null only on the tick a fresh sample was delivered (for the estimator's observe). */
    fun fresh(): Sample? = justDelivered

    private fun poseAt(t: Float): Pose {
        if (history.isEmpty()) return Pose(0f, 0f, 0f)
        // nearest-by-time (history is dense at the control rate).
        var best = history.first()
        for (h in history) if (kotlin.math.abs(h.first - t) < kotlin.math.abs(best.first - t)) best = h
        return best.second
    }

    private fun noise(stddev: Float) = if (stddev > 0f) (rng.nextFloat() * 2f - 1f) * stddev else 0f

    companion object {
        /** Measured nominal telemetry cadence on real hardware (~1.6 Hz). */
        const val NOMINAL_PERIOD_MS = 600f
    }
}
