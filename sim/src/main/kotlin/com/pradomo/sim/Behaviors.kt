package com.pradomo.sim

import com.pradomo.control.HeadingHold
import com.pradomo.control.maneuver.CruiseManeuver
import com.pradomo.control.maneuver.ManeuverCommand
import com.pradomo.control.maneuver.MultiPointTurn
import com.pradomo.control.maneuver.Pose
import com.pradomo.control.maneuver.TurnDirection

/** The tunable knobs across the three algorithms; defaults mirror the shared code today. */
// Defaults mirror the shared code's (now sim-tuned) defaults, so the tuner's "before"
// baseline reflects what actually ships.
data class TuneParams(
    // MultiPointTurn
    val turnRadius: Float = 0.45f,
    val kTrim: Float = 2.0f,
    val settleTicks: Int = 3,
    val minTrimTurn: Float = 0.15f,
    // CruiseManeuver.Gains
    val cKCross: Float = 4.0f,
    val cLeanCap: Float = 0.8f,
    val cKHead: Float = 1.6f,
    val cTurnCap: Float = 0.6f,
    // HeadingHold
    val hKHead: Float = 2.5f,
    val hTurnCap: Float = 0.6f,
)

object Behaviors {
    private const val PITCH = 0.18f

    fun build(p: TuneParams): List<Behavior> = listOf(kturn(p), cruise(p), headingHold(p))

    fun kturn(p: TuneParams) = Behavior(
        "kturn", BehaviorKind.KTURN, start = Pose(2f, 1f, 0.7f), maxTimeSec = 30f, pitch = PITCH, dir = 1,
    ) {
        val m = MultiPointTurn(
            TurnDirection.LEFT, PITCH,
            MultiPointTurn.Params(
                turnScale = TURN_SCALE, linearScale = LINEAR_SCALE,
                turnRadius = p.turnRadius, kTrim = p.kTrim, settleTicks = p.settleTicks,
                minTrimTurn = p.minTrimTurn,
            ),
        )
        Controller { pose, dt -> m.step(pose, dt) }
    }

    fun cruise(p: TuneParams) = Behavior(
        "cruise", BehaviorKind.CRUISE, start = Pose(0f, 0f, 0.5f), maxTimeSec = 12f, pitch = 0f, dir = 1,
    ) {
        val c = CruiseManeuver(
            0.6f, correct = true,
            CruiseManeuver.Gains(kCross = p.cKCross, leanCap = p.cLeanCap, kHead = p.cKHead, turnCap = p.cTurnCap),
        )
        Controller { pose, dt -> c.step(pose, dt) }
    }

    fun headingHold(p: TuneParams) = Behavior(
        "heading_hold", BehaviorKind.HEADING_HOLD, start = Pose(0f, 0f, 0.5f), maxTimeSec = 12f, pitch = 0f, dir = 1,
    ) {
        val hh = HeadingHold(kHead = p.hKHead, turnCap = p.hTurnCap)
        var latched = false
        Controller { pose, _ ->
            if (!latched) { hh.latch(pose.heading); latched = true }
            ManeuverCommand(0.6f, hh.correction(pose.heading), done = false)
        }
    }
}
