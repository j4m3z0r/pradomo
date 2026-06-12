package com.pradomo.sim

import com.pradomo.control.maneuver.ManeuverCommand
import com.pradomo.control.maneuver.Pose

const val CONTROL_DT = 0.08f          // matches the app's 80 ms resend loop
const val LINEAR_SCALE = 0.5f         // SpeedMode.NORMAL maxLinear
const val TURN_SCALE = 0.6f           // LymowProtocol.TURN_LIMIT

enum class BehaviorKind { KTURN, CRUISE, HEADING_HOLD }

/** A stateful controller for one run: maps the (estimated) pose to a command. */
fun interface Controller { fun step(pose: Pose, dt: Float): ManeuverCommand }

/** What to run: a fresh controller per run, plus how to score it. */
class Behavior(
    val name: String,
    val kind: BehaviorKind,
    val start: Pose,
    val maxTimeSec: Float,
    val pitch: Float,
    val dir: Int,                     // +1 left / −1 right (K-turn)
    val newController: () -> Controller,
)

data class RunResult(
    val behavior: String,
    val scenario: String,
    val strategy: PoseStrategy,
    val truePath: List<Pose>,
    val estPath: List<Pose>,
    val metrics: Metrics,
)

class Runner(private val linearScale: Float = LINEAR_SCALE, private val turnScale: Float = TURN_SCALE) {

    fun run(behavior: Behavior, scenario: Scenario, strategy: PoseStrategy): RunResult {
        val plant = MowerPlant(scenario.env, linearScale, turnScale, seed = scenario.seed)
        plant.setPose(behavior.start)
        val sampler = TelemetrySampler(
            periodMs = scenario.telemetryPeriodMs, latencyMs = scenario.telemetryLatencyMs,
            seed = scenario.seed + 1000,
        )
        val src = PoseSource.of(strategy, linearScale, turnScale)
        src.reset(behavior.start)
        val controller = behavior.newController()

        val truePath = ArrayList<Pose>()
        val estPath = ArrayList<Pose>()
        val treads = ArrayList<Pair<Float, Float>>()
        val turns = ArrayList<Float>()
        val drives = ArrayList<Float>()

        var t = 0f
        var completed = false
        sampler.tick(0f, plant.pose())
        val maxIter = (behavior.maxTimeSec / CONTROL_DT).toInt()
        repeat(maxIter) {
            sampler.fresh()?.let { src.onFresh(it, t) }
            val pose = src.pose()
            val cmd = controller.step(pose, CONTROL_DT)
            src.predict(t, cmd.drive, cmd.turn, CONTROL_DT)
            if (cmd.done) { completed = true; return@repeat }
            plant.step(cmd.drive, cmd.turn, CONTROL_DT)
            t += CONTROL_DT
            sampler.tick(t, plant.pose())
            truePath.add(plant.pose()); estPath.add(src.pose()); treads.add(plant.lastVL to plant.lastVR)
            turns.add(cmd.turn); drives.add(cmd.drive)
        }

        val jerk = meanAbsDelta(turns)
        val wiggle = driveReversals(drives)
        val metrics = when (behavior.kind) {
            BehaviorKind.KTURN -> Scoring.kturn(behavior.start, behavior.pitch, behavior.dir,
                truePath, treads, plant.maxTreadSpeed(), t, completed, jerk, wiggle)
            BehaviorKind.CRUISE, BehaviorKind.HEADING_HOLD -> Scoring.lineHold(behavior.start, truePath, t, jerk)
        }
        return RunResult(behavior.name, scenario.name, strategy, truePath, estPath, metrics)
    }

    private fun meanAbsDelta(xs: List<Float>): Float {
        if (xs.size < 2) return 0f
        var s = 0f
        for (i in 1 until xs.size) s += kotlin.math.abs(xs[i] - xs[i - 1])
        return s / (xs.size - 1)
    }

    /** Count drive-direction reversals — the "wiggle" the user complained about. A clean
     * K-turn has exactly 3 (rev/fwd/rev/fwd); extra reversals = back-and-forth finishing. */
    private fun driveReversals(drives: List<Float>): Int {
        var reversals = 0; var last = 0
        for (d in drives) {
            val sign = if (d > 0.01f) 1 else if (d < -0.01f) -1 else 0
            if (sign != 0) { if (last != 0 && sign != last) reversals++; last = sign }
        }
        return reversals
    }
}
