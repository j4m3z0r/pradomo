package com.pradomo.sim

import com.pradomo.control.maneuver.CruiseManeuver
import com.pradomo.control.maneuver.MultiPointTurn
import com.pradomo.control.maneuver.Pose
import com.pradomo.control.maneuver.TurnDirection
import com.pradomo.control.maneuver.TurnStyle

/** The tunable knobs across the three algorithms; defaults mirror the shared code today. */
// Defaults mirror the shared code's (now sim-tuned) defaults, so the tuner's "before"
// baseline reflects what actually ships.
data class TuneParams(
    // MultiPointTurn
    val turnRadius: Float = 0.45f,
    val kTrim: Float = 2.0f,
    val settleTicks: Int = 2,
    val minTrimTurn: Float = 0.3f,
    val approachHorizon: Float = 0.25f,
    val kAcquireCross: Float = 4.0f,
)

object Behaviors {
    private const val PITCH = 0.18f

    fun build(p: TuneParams): List<Behavior> = listOf(kturn(p), cruise(p))

    fun kturn(p: TuneParams) = kturnWith(
        MultiPointTurn.Params(
            turnScale = TURN_SCALE, linearScale = LINEAR_SCALE,
            turnRadius = p.turnRadius, kTrim = p.kTrim, settleTicks = p.settleTicks,
            minTrimTurn = p.minTrimTurn, approachHorizon = p.approachHorizon,
            kAcquireCross = p.kAcquireCross,
        ),
    )

    /** Turn behavior with explicit planner params + style (used by the finish A/B). */
    fun kturnWith(prm: MultiPointTurn.Params, style: TurnStyle = TurnStyle.K_TURN) = Behavior(
        "kturn", BehaviorKind.KTURN, start = Pose(2f, 1f, 0.7f), maxTimeSec = 45f, pitch = PITCH, dir = 1,
    ) {
        val m = MultiPointTurn(TurnDirection.LEFT, PITCH, prm, style)
        Controller { pose, dt -> m.step(pose, dt) }
    }

    fun cruise(p: TuneParams) = Behavior(
        "cruise", BehaviorKind.CRUISE, start = Pose(0f, 0f, 0.5f), maxTimeSec = 12f, pitch = 0f, dir = 1,
    ) {
        val c = CruiseManeuver(0.6f)
        Controller { pose, dt -> c.step(pose, dt) }
    }
}
