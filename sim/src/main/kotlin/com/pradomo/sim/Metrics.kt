package com.pradomo.sim

import com.pradomo.control.maneuver.Pose
import com.pradomo.control.maneuver.angleDelta
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Per-run metrics on the TRUE path (not what the controller saw). Lower balanced = better. */
data class Metrics(
    val headingErrRad: Float = 0f,   // K-turn: |end heading − (start+π)|
    val placementErrM: Float = 0f,   // K-turn: distance from the one-pitch-over target point
    val overrunM: Float = 0f,        // K-turn: max forward excursion past the row-end line
    val minTreadFrac: Float = 1f,    // K-turn: min over turning steps of min(|vL|,|vR|)/maxTread
    val crossTrackRmsM: Float = 0f,  // cruise/hold: RMS perpendicular distance from the line
    val crossTrackMaxM: Float = 0f,
    val timeSec: Float = 0f,
    val completed: Boolean = true,
    val balanced: Float = 0f,
)

object Scoring {
    // Acceptable-level normalizers (a term ≈ 1.0 at the threshold we care about).
    private const val HEAD = 0.035f   // ~2°
    private const val PLACE = 0.05f   // 5 cm
    private const val CROSS = 0.04f   // 4 cm RMS
    private const val CROSS_MAX = 0.12f
    private const val SCRUB_THRESH = 0.15f // tread fraction below this = scrubbing
    private const val JERK = 0.04f         // mean |Δturn| per tick we consider "smooth"

    fun kturn(start: Pose, pitch: Float, dir: Int, truePath: List<Pose>,
              treads: List<Pair<Float, Float>>, maxTread: Float, timeSec: Float, completed: Boolean,
              jerk: Float): Metrics {
        val end = truePath.lastOrNull() ?: start
        val s = dir.toFloat()
        val targetHeading = start.heading + s * PI.toFloat()
        val nx = -sin(start.heading); val ny = cos(start.heading)
        val tx = start.x + s * pitch * nx; val ty = start.y + s * pitch * ny
        val headingErr = abs(angleDelta(end.heading, targetHeading))
        val placement = hypot(end.x - tx, end.y - ty)
        // forward overrun along the start heading
        val overrun = truePath.maxOfOrNull { (it.x - start.x) * cos(start.heading) + (it.y - start.y) * sin(start.heading) }?.coerceAtLeast(0f) ?: 0f
        // min tread fraction while turning (both treads should keep rolling)
        var minFrac = 1f
        for ((vl, vr) in treads) {
            val turning = abs(vl - vr) > 0.05f
            if (turning) minFrac = minOf(minFrac, minOf(abs(vl), abs(vr)) / maxTread)
        }
        val scrub = max(0f, SCRUB_THRESH - minFrac) / SCRUB_THRESH
        val balanced = 1.0f * (headingErr / HEAD) +
            1.0f * (placement / PLACE) +
            0.7f * scrub +
            0.3f * (timeSec / 20f) +
            0.3f * (overrun / 0.5f) +
            0.4f * (jerk / JERK) +
            (if (completed) 0f else 10f)
        return Metrics(headingErrRad = headingErr, placementErrM = placement, overrunM = overrun,
            minTreadFrac = minFrac, timeSec = timeSec, completed = completed, balanced = balanced)
    }

    /** Cruise / heading-hold: deviation from the line through start along start heading. */
    fun lineHold(start: Pose, truePath: List<Pose>, timeSec: Float, jerk: Float): Metrics {
        val dirX = cos(start.heading); val dirY = sin(start.heading)
        var sumSq = 0f; var mx = 0f; var n = 0
        for (p in truePath) {
            val cross = dirX * (p.y - start.y) - dirY * (p.x - start.x)
            sumSq += cross * cross; mx = max(mx, abs(cross)); n++
        }
        val rms = if (n > 0) sqrt(sumSq / n) else 0f
        val end = truePath.lastOrNull() ?: start
        val headErr = abs(angleDelta(end.heading, start.heading))
        val balanced = 1.0f * (rms / CROSS) + 0.6f * (mx / CROSS_MAX) + 0.4f * (headErr / HEAD) +
            0.5f * (jerk / JERK)
        return Metrics(crossTrackRmsM = rms, crossTrackMaxM = mx, headingErrRad = headErr,
            timeSec = timeSec, completed = true, balanced = balanced)
    }
}
