package com.pradomo.sim

import java.io.File

private val OUT = File("sim/out")
private val STRATEGIES = PoseStrategy.entries

fun main(args: Array<String>) {
    when (args.firstOrNull() ?: "assess") {
        "assess" -> assess()
        "tune" -> tune()
        "ab" -> abKturn()
        "all" -> { assess(); abKturn(); tune() }
        else -> { println("usage: assess | tune | ab | all"); return }
    }
}

/**
 * The K-turn planner A/B: legacy (iteration-3 behavior) vs the v2 planner (approach-rate
 * profiling + fresh-pose settle + row acquisition), each under RAW telemetry and the
 * heading-prediction pose source.
 */
private fun abKturn() {
    val runner = Runner()
    val scenarios = Scenarios.sweep()
    val configs = listOf(
        "legacy" to com.pradomo.control.maneuver.MultiPointTurn.Params(
            turnScale = TURN_SCALE, linearScale = LINEAR_SCALE,
            approachHorizon = 0f, settleFreshPose = false, acquireRow = false,
        ),
        "v2" to com.pradomo.control.maneuver.MultiPointTurn.Params(
            turnScale = TURN_SCALE, linearScale = LINEAR_SCALE,
        ),
    )
    println("=== K-TURN PLANNER A/B (${scenarios.size} scenarios) ===\n")
    println("%-8s %-13s %8s %10s %10s %8s %7s %7s".format(
        "planner", "pose", "score", "headErr°", "lateral", "scrub", "time", "%done"))
    for ((name, prm) in configs) {
        for (strat in listOf(PoseStrategy.RAW, PoseStrategy.HEADING_PRED)) {
            val b = Behaviors.kturnWith(prm)
            var score = 0.0; var head = 0.0; var lat = 0.0; var scrub = 0.0; var time = 0.0; var done = 0
            for (s in scenarios) {
                val m = runner.run(b, s, strat).metrics
                score += m.balanced; head += Math.toDegrees(m.headingErrRad.toDouble())
                lat += m.placementErrM * 100; scrub += m.scrubFrac; time += m.timeSec
                if (m.completed) done++
            }
            val n = scenarios.size
            println("%-8s %-13s %8.2f %9.1f° %8.1fcm %8.2f %6.1fs %6.0f%%".format(
                name, strat, score / n, head / n, lat / n, scrub / n, time / n, 100.0 * done / n))
        }
        println()
    }
    // Plots for the worst-case-ish slope scenario, all four combos.
    val rep = scenarios.firstOrNull { it.name.startsWith("slope0.1_dir1.2") } ?: scenarios.first()
    OUT.mkdirs()
    for ((name, prm) in configs) for (strat in listOf(PoseStrategy.RAW, PoseStrategy.HEADING_PRED)) {
        val r = runner.run(Behaviors.kturnWith(prm), rep, strat)
        Plot.write(r, File(OUT, "plots/ab_kturn_${name}_${strat}.svg"))
    }
    println("wrote A/B plots to ${File(OUT, "plots").path}")
}

private fun assess() {
    val runner = Runner()
    val scenarios = Scenarios.sweep()
    val behaviors = Behaviors.build(TuneParams())
    println("=== ASSESS: ${behaviors.size} behaviors × ${STRATEGIES.size} pose sources × ${scenarios.size} scenarios ===\n")

    val csv = StringBuilder("behavior,strategy,scenario,score,headingDeg,placementCm,scrubFrac,crossRmsCm,crossMaxCm,timeSec,completed\n")
    println("%-13s %-12s %8s %10s %10s %9s %9s".format("behavior", "pose", "score", "headErr°", "place/cross", "scrub", "%done"))
    for (b in behaviors) {
        for (strat in STRATEGIES) {
            var score = 0.0; var head = 0.0; var place = 0.0; var tread = 0.0; var crossR = 0.0; var done = 0
            for (s in scenarios) {
                val r = runner.run(b, s, strat); val m = r.metrics
                score += m.balanced; head += Math.toDegrees(m.headingErrRad.toDouble())
                place += m.placementErrM * 100; tread += m.scrubFrac; crossR += m.crossTrackRmsM * 100
                if (m.completed) done++
                csv.append("%s,%s,%s,%.3f,%.2f,%.1f,%.3f,%.1f,%.1f,%.2f,%b\n".format(
                    b.name, strat, s.name, m.balanced, Math.toDegrees(m.headingErrRad.toDouble()),
                    m.placementErrM * 100, m.scrubFrac, m.crossTrackRmsM * 100, m.crossTrackMaxM * 100,
                    m.timeSec, m.completed))
            }
            val n = scenarios.size
            val placeOrCross = if (b.kind == BehaviorKind.KTURN) "%.0fcm pl".format(place / n) else "%.1fcm rms".format(crossR / n)
            println("%-13s %-12s %8.2f %9.1f° %10s %9.2f %8.0f%%".format(
                b.name, strat, score / n, head / n, placeOrCross, tread / n, 100.0 * done / n))
        }
        println()
    }
    OUT.mkdirs(); File(OUT, "report.csv").writeText(csv.toString())
    println("wrote ${File(OUT, "report.csv").path}")

    // Representative trajectory plots: a slope and a grip scenario, RAW vs ESTIMATOR.
    val reps = scenarios.filter { it.name in setOf("slope0.1_dir1.2", "grip0.8_0.95_imb0.06_terr1.2", "telem600.0") }
        .ifEmpty { scenarios.take(1) }
    var plots = 0
    for (b in behaviors) for (s in reps) for (strat in listOf(PoseStrategy.RAW, PoseStrategy.ESTIMATOR)) {
        val r = runner.run(b, s, strat)
        Plot.write(r, File(OUT, "plots/${b.name}_${strat}_${s.name}.svg")); plots++
    }
    println("wrote $plots SVG plots to ${File(OUT, "plots").path}")
}

private fun tune() {
    val runner = Runner()
    val scenarios = Scenarios.sweep()
    val base = TuneParams()
    // Tune under RAW — the shipping config (the estimator A/B did not justify adopting it).
    val strat = PoseStrategy.RAW
    println("=== TUNE under pose source: $strat ===\n")

    fun report(tag: String, p: TuneParams) {
        val k = Tuner.aggregate(Behaviors.kturn(p), scenarios, strat, runner)
        val c = Tuner.aggregate(Behaviors.cruise(p), scenarios, strat, runner)
        val h = Tuner.aggregate(Behaviors.headingHold(p), scenarios, strat, runner)
        println("%-8s  kturn=%.2f  cruise=%.2f  hold=%.2f  (total %.2f)".format(tag, k, c, h, k + c + h))
    }
    report("before", base)
    val res = Tuner.tune(base, scenarios, strat, runner)
    res.log.forEach { println("  $it") }
    report("after", res.params)
    println("\nrecommended TuneParams:\n  ${res.params}")
}
