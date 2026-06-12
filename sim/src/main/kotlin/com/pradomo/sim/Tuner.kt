package com.pradomo.sim

/**
 * Coordinate-descent search over the tunable knobs. For each axis it tries the candidates,
 * keeps the value that minimises the aggregate score (mean + 0.5·worst-case across the
 * scenario sweep) for that axis's behavior, and repeats for a couple of passes. Disjoint
 * params per behavior, so each axis is scored against its own behavior only.
 */
object Tuner {
    private class Axis(
        val name: String,
        val behaviorOf: (TuneParams) -> Behavior,
        val candidates: List<Float>,
        val set: (TuneParams, Float) -> TuneParams,
        val get: (TuneParams) -> Float,
    )

    // Note: turnRadius is deliberately NOT tuned — it's the user's grass-vs-space preference
    // (a Settings knob), not a control gain. We optimise the control gains only.
    private fun axes() = listOf(
        Axis("kturn.kTrim", { Behaviors.kturn(it) }, listOf(1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f),
            { p, v -> p.copy(kTrim = v) }, { it.kTrim }),
        Axis("kturn.settleTicks", { Behaviors.kturn(it) }, listOf(2f, 3f, 4f, 6f),
            { p, v -> p.copy(settleTicks = v.toInt()) }, { it.settleTicks.toFloat() }),
        Axis("kturn.minTrimTurn", { Behaviors.kturn(it) }, listOf(0.1f, 0.2f, 0.3f, 0.4f),
            { p, v -> p.copy(minTrimTurn = v) }, { it.minTrimTurn }),
        Axis("kturn.approachHorizon", { Behaviors.kturn(it) }, listOf(0.25f, 0.35f, 0.45f, 0.6f),
            { p, v -> p.copy(approachHorizon = v) }, { it.approachHorizon }),
        Axis("kturn.kAcquireCross", { Behaviors.kturn(it) }, listOf(2f, 4f, 6f, 8f),
            { p, v -> p.copy(kAcquireCross = v) }, { it.kAcquireCross }),
    )

    fun aggregate(behavior: Behavior, scenarios: List<Scenario>, strategy: PoseStrategy, runner: Runner): Double {
        val scores = scenarios.map { runner.run(behavior, it, strategy).metrics.balanced.toDouble() }
        return scores.average() + 0.5 * (scores.maxOrNull() ?: 0.0)
    }

    data class Result(val params: TuneParams, val log: List<String>)

    fun tune(base: TuneParams, scenarios: List<Scenario>, strategy: PoseStrategy, runner: Runner, passes: Int = 2): Result {
        var p = base
        val log = ArrayList<String>()
        repeat(passes) { pass ->
            for (ax in axes()) {
                var bestV = ax.get(p); var bestScore = aggregate(ax.behaviorOf(p), scenarios, strategy, runner)
                val before = bestScore
                for (c in ax.candidates) {
                    val cand = ax.set(p, c)
                    val sc = aggregate(ax.behaviorOf(cand), scenarios, strategy, runner)
                    if (sc < bestScore - 1e-6) { bestScore = sc; bestV = c }
                }
                if (bestV != ax.get(p)) {
                    log.add("pass${pass} ${ax.name}: ${ax.get(p)} -> $bestV  (score ${"%.2f".format(before)} -> ${"%.2f".format(bestScore)})")
                    p = ax.set(p, bestV)
                }
            }
        }
        return Result(p, log)
    }
}
