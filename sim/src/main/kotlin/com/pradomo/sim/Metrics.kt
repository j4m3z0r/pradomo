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
    val placementErrM: Float = 0f,   // K-turn: LATERAL offset from the target row line
    val overrunM: Float = 0f,        // K-turn: max forward excursion past the row-end line
    val scrubFrac: Float = 0f,       // K-turn: fraction of turning time with one tread near-stalled
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
    private const val SCRUB_RATIO = 0.1f   // tread considered dragging below this fraction of the other
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
        // Lateral offset from the target row line (along-row position is free: the mower
        // mows down the new row from wherever it ends, e.g. after the acquire phase).
        val dx = cos(targetHeading); val dy = sin(targetHeading)
        val placement = abs(dx * (end.y - ty) - dy * (end.x - tx))
        // forward overrun along the start heading
        val overrun = truePath.maxOfOrNull { (it.x - start.x) * cos(start.heading) + (it.y - start.y) * sin(start.heading) }?.coerceAtLeast(0f) ?: 0f
        // Scrub time: fraction of meaningfully-turning steps where one tread is near-stalled
        // while the other rolls (a globally slow arc is fine; a dragging tread is not).
        var scrubSteps = 0; var turnSteps = 0
        for ((vl, vr) in treads) {
            val mx = max(abs(vl), abs(vr))
            if (mx > 0.15f * maxTread && abs(vl - vr) > 0.04f) {
                turnSteps++
                if (kotlin.math.min(abs(vl), abs(vr)) < SCRUB_RATIO * mx) scrubSteps++
            }
        }
        val scrub = if (turnSteps > 0) scrubSteps.toFloat() / turnSteps else 0f
        val balanced = 1.0f * (headingErr / HEAD) +
            1.0f * (placement / PLACE) +
            0.7f * scrub +
            0.3f * (timeSec / 20f) +
            0.3f * (overrun / 0.5f) +
            0.4f * (jerk / JERK) +
            (if (completed) 0f else 10f)
        return Metrics(headingErrRad = headingErr, placementErrM = placement, overrunM = overrun,
            scrubFrac = scrub, timeSec = timeSec, completed = completed, balanced = balanced)
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
