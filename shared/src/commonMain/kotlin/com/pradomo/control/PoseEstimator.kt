package com.pradomo.control

import com.pradomo.control.maneuver.Pose
import com.pradomo.control.maneuver.angleDelta
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Adaptive dead-reckoning pose observer for closed-loop maneuvers.
 *
 * The mower only reports its pose ~1.6 Hz (≈600 ms), but the control loop runs at ~80 ms,
 * so a maneuver acting directly on the last telemetry sample is reacting to a ~600 ms-old
 * pose — enough lag to overshoot turns and wander in cruise. This estimator fills the gap:
 * every control tick it advances an *estimated current pose* by integrating the commanded
 * motion, scaled by how effective the commands have *recently* actually been, PLUS an
 * additive world-frame **drift** bias learned from the part of the recent motion the
 * commands *don't* explain (a slope sliding the mower, a constant yaw bias). So both
 * multiplicative slip (low traction → `kLin/kAng` shrink) and additive disturbances
 * (slope → `drift*`) are predicted between samples. When a real sample arrives it
 * re-anchors to that truth and replays the buffered commands forward to "now".
 *
 * Pure and deterministic — reusable by both the app loop and the off-app simulator.
 */
class PoseEstimator(
    private val linearScale: Float,
    private val turnScale: Float,
    private val params: Params = Params(),
) {
    data class Params(
        val effAlpha: Float = 0.4f,
        val effMin: Float = 0.2f,
        val effMax: Float = 1.5f,
        val minLinObs: Float = 0.03f,   // metres of nominal motion before trusting an obs
        val minAngObs: Float = 0.05f,   // radians
        /** EMA weight for the additive drift estimate (0 disables drift learning). */
        val driftAlpha: Float = 0.3f,
        val driftMax: Float = 0.6f,     // m/s clamp on translational drift
        val driftWMax: Float = 1.0f,    // rad/s clamp on yaw drift
        val historySeconds: Float = 2.0f,
    )

    var kLin = 1f; private set
    var kAng = 1f; private set
    var driftX = 0f; private set
    var driftY = 0f; private set
    var driftW = 0f; private set

    private var estX = 0f
    private var estY = 0f
    private var estHeading = 0f
    private var started = false

    private val cmdT = ArrayList<Float>()
    private val cmdD = ArrayList<Float>()
    private val cmdW = ArrayList<Float>()

    private var lastSampleT = Float.NaN
    private var lastSampleX = 0f
    private var lastSampleY = 0f
    private var lastSampleHeading = 0f

    fun estimate(): Pose = Pose(estX, estY, estHeading)

    fun predict(tSeconds: Float, drive: Float, turn: Float, dt: Float) {
        if (!started) return
        cmdT.add(tSeconds); cmdD.add(drive); cmdW.add(turn)
        trimHistory(tSeconds)
        var h = estHeading + turn * turnScale * kAng * dt + driftW * dt
        val v = drive * linearScale * kLin
        estX += v * cos(h) * dt + driftX * dt
        estY += v * sin(h) * dt + driftY * dt
        estHeading = h
    }

    fun observe(sample: Pose, sampleT: Float, nowT: Float) {
        if (!lastSampleT.isNaN()) calibrate(sample, sampleT)
        lastSampleT = sampleT
        lastSampleX = sample.x; lastSampleY = sample.y; lastSampleHeading = sample.heading
        // Re-anchor to truth, replay commands forward to now (with current k + drift).
        val end = integrate(sample, sampleT, nowT, withDrift = true)
        estX = end.x; estY = end.y; estHeading = end.heading
        started = true
    }

    private fun calibrate(sample: Pose, sampleT: Float) {
        // 1) multiplicative effectiveness from motion magnitudes the commands predicted.
        var nomLin = 0f; var nomAng = 0f; var prevT = lastSampleT
        for (i in cmdT.indices) {
            val t = cmdT[i]; if (t < lastSampleT || t > sampleT) continue
            val seg = (t - prevT).coerceAtLeast(0f)
            nomLin += kotlin.math.abs(cmdD[i]) * linearScale * seg
            nomAng += kotlin.math.abs(cmdW[i]) * turnScale * seg
            prevT = t
        }
        val obsLin = hypot(sample.x - lastSampleX, sample.y - lastSampleY)
        val obsAng = kotlin.math.abs(angleDelta(lastSampleHeading, sample.heading))
        if (nomLin > params.minLinObs) kLin = ema(kLin, obsLin / nomLin, params.effAlpha).coerceIn(params.effMin, params.effMax)
        if (nomAng > params.minAngObs) kAng = ema(kAng, obsAng / nomAng, params.effAlpha).coerceIn(params.effMin, params.effMax)

        // 2) additive drift: the residual between the observed sample and what the commands
        // (with current k) predict — i.e. the motion the commands DON'T explain (slope etc.).
        val interval = sampleT - lastSampleT
        if (interval > 1e-3f && params.driftAlpha > 0f) {
            val pred = integrate(Pose(lastSampleX, lastSampleY, lastSampleHeading), lastSampleT, sampleT, withDrift = false)
            driftX = ema(driftX, (sample.x - pred.x) / interval, params.driftAlpha).coerceIn(-params.driftMax, params.driftMax)
            driftY = ema(driftY, (sample.y - pred.y) / interval, params.driftAlpha).coerceIn(-params.driftMax, params.driftMax)
            driftW = ema(driftW, angleDelta(pred.heading, sample.heading) / interval, params.driftAlpha).coerceIn(-params.driftWMax, params.driftWMax)
        }
    }

    /** Pure forward integration of the buffered commands over (from, to], from a pose. */
    private fun integrate(from: Pose, fromT: Float, toT: Float, withDrift: Boolean): Pose {
        var x = from.x; var y = from.y; var h = from.heading; var prevT = fromT
        for (i in cmdT.indices) {
            val t = cmdT[i]; if (t < fromT || t > toT) continue
            val seg = (t - prevT).coerceAtLeast(0f)
            if (seg > 0f) {
                h += cmdW[i] * turnScale * kAng * seg + (if (withDrift) driftW * seg else 0f)
                val v = cmdD[i] * linearScale * kLin
                x += v * cos(h) * seg + (if (withDrift) driftX * seg else 0f)
                y += v * sin(h) * seg + (if (withDrift) driftY * seg else 0f)
            }
            prevT = t
        }
        return Pose(x, y, h)
    }

    private fun ema(old: Float, new: Float, alpha: Float) = old + alpha * (new - old)

    private fun trimHistory(nowT: Float) {
        val cutoff = nowT - params.historySeconds
        var drop = 0
        while (drop < cmdT.size && cmdT[drop] < cutoff && (lastSampleT.isNaN() || cmdT[drop] < lastSampleT)) drop++
        if (drop > 0) {
            cmdT.subList(0, drop).clear(); cmdD.subList(0, drop).clear(); cmdW.subList(0, drop).clear()
        }
    }
}
