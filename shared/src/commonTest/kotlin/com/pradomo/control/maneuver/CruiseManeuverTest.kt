package com.pradomo.control.maneuver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CruiseManeuverTest {
    @Test fun holds_a_constant_straight_command_and_never_self_completes() {
        val cruise = CruiseManeuver(0.6f)
        repeat(50) { i ->
            val cmd = cruise.step(Pose(i * 0.1f, 0.3f, 0.2f), 0.08f)
            assertTrue(!cmd.done)
            assertEquals(0.6f, cmd.drive)
            assertEquals(0f, cmd.turn, 0f) // equal motor effort — no auto steering
        }
    }

    @Test fun reverse_cruise_and_clamping() {
        assertEquals(-0.5f, CruiseManeuver(-0.5f).step(Pose(0f, 0f, 0f), 0.08f).drive)
        assertEquals(1f, CruiseManeuver(9f).step(Pose(0f, 0f, 0f), 0.08f).drive)
    }
}
