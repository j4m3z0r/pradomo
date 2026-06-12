package com.pradomo.sim

import kotlin.math.cos
import kotlin.math.sin

/** A named set of adverse conditions: plant env + telemetry channel params + seed. */
data class Scenario(
    val name: String,
    val env: MowerPlant.Env,
    val telemetryPeriodMs: Float,
    val telemetryLatencyMs: Float,
    val seed: Long,
)

object Scenarios {
    /**
     * A representative sweep (not full cartesian — that would be tens of thousands of runs).
     * Centred on the measured hardware profile (600 ms telemetry), spanning slope drift,
     * per-tread grip imbalance, tread-speed imbalance, turn-rate error, telemetry rate, and
     * latency. ~A few hundred scenarios.
     */
    fun sweep(): List<Scenario> {
        val out = ArrayList<Scenario>()
        var seed = 1L

        // Slope: downhill drift in several directions + a slope-induced yaw.
        val slopes = listOf(0f, 0.05f, 0.10f, 0.15f) // ~ m/s downhill slide
        val slopeDirs = listOf(0f, 1.2f, 2.5f, 4.0f) // radians, world frame
        // Per-tread grip and systematic tread imbalance.
        val grips = listOf(1f to 1f, 0.9f to 1f, 1f to 0.85f, 0.8f to 0.95f)
        val imbalances = listOf(0f, 0.06f, -0.06f)
        val turnErrs = listOf(0.85f, 1.0f, 1.2f)
        val telems = listOf(120f to 0f, 600f to 120f, 1000f to 250f) // (periodMs, latencyMs)

        // Core sweep: nominal telemetry × the physical adversities.
        for ((gl, gr) in grips) for (imb in imbalances) for (te in turnErrs) {
            out += Scenario(
                "grip${gl}_${gr}_imb${imb}_terr${te}",
                MowerPlant.Env(gripLeft = gl, gripRight = gr, treadImbalance = imb, turnRateError = te,
                    processNoise = 0.002f),
                telemetryPeriodMs = 600f, telemetryLatencyMs = 120f, seed = seed++,
            )
        }
        // Slope sweep at nominal telemetry.
        for (s in slopes) for (dir in slopeDirs) {
            if (s == 0f && dir != 0f) continue
            out += Scenario(
                "slope${s}_dir${dir}",
                MowerPlant.Env(slopeDriftX = s * cos(dir), slopeDriftY = s * sin(dir),
                    slopeYaw = s * 0.4f, processNoise = 0.002f),
                telemetryPeriodMs = 600f, telemetryLatencyMs = 120f, seed = seed++,
            )
        }
        // Telemetry-rate sensitivity under a fixed moderate adversity.
        for ((p, l) in telems) {
            out += Scenario(
                "telem${p}",
                MowerPlant.Env(gripLeft = 0.9f, gripRight = 1f, slopeDriftX = 0.06f,
                    turnRateError = 1.1f, processNoise = 0.003f),
                telemetryPeriodMs = p, telemetryLatencyMs = l, seed = seed++,
            )
        }
        return out
    }
}
