package com.pradomo.sim

import com.pradomo.control.PoseEstimator
import com.pradomo.control.maneuver.Pose

/**
 * What pose the controller acts on each control tick — the variable we're A/B testing.
 * All three are seeded with the true start pose so the maneuver latches the right target.
 */
enum class PoseStrategy { RAW, DEAD_RECKON, ESTIMATOR }

interface PoseSource {
    fun reset(start: Pose)
    /** A real telemetry sample just arrived (truth at sample.sampleT, delivered at nowT). */
    fun onFresh(sample: TelemetrySampler.Sample, nowT: Float)
    /** Advance with the command about to be sent at time t. */
    fun predict(t: Float, drive: Float, turn: Float, dt: Float)
    fun pose(): Pose

    companion object {
        fun of(strategy: PoseStrategy, linearScale: Float, turnScale: Float): PoseSource = when (strategy) {
            PoseStrategy.RAW -> RawSource()
            // Dead-reckoning = pure command integration: effectiveness pinned to 1, no drift.
            PoseStrategy.DEAD_RECKON -> EstimatorSource(
                PoseEstimator(linearScale, turnScale, PoseEstimator.Params(effMin = 1f, effMax = 1f, driftAlpha = 0f)),
            )
            PoseStrategy.ESTIMATOR -> EstimatorSource(PoseEstimator(linearScale, turnScale))
        }
    }
}

/** Today's behavior: act on the last delivered (stale) telemetry sample. */
private class RawSource : PoseSource {
    private var last = Pose(0f, 0f, 0f)
    override fun reset(start: Pose) { last = start }
    override fun onFresh(sample: TelemetrySampler.Sample, nowT: Float) { last = sample.pose }
    override fun predict(t: Float, drive: Float, turn: Float, dt: Float) {}
    override fun pose() = last
}

private class EstimatorSource(private val est: PoseEstimator) : PoseSource {
    override fun reset(start: Pose) = est.observe(start, 0f, 0f)
    override fun onFresh(sample: TelemetrySampler.Sample, nowT: Float) =
        est.observe(sample.pose, sample.sampleT, nowT)
    override fun predict(t: Float, drive: Float, turn: Float, dt: Float) = est.predict(t, drive, turn, dt)
    override fun pose() = est.estimate()
}
