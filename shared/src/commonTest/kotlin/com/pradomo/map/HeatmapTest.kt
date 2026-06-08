package com.pradomo.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeatmapTest {
    private fun s(t: Long, x: Float, y: Float, blade: Boolean = false, cmd: Float = 0f) =
        MapSample(t, x, y, heading = 0f, bladeOn = blade, cmdLinear = cmd, cmdAngular = 0f)

    @Test fun cellIndexing() {
        assertEquals(Cell(0, 0), cellOf(0.1f, 0.2f, 0.25f))
        assertEquals(Cell(1, -1), cellOf(0.3f, -0.1f, 0.25f))
    }

    @Test fun mowTimeKeepsLatestPerCellAndIgnoresBladeOff() {
        val cells = mowTimeCells(
            listOf(
                s(100, 0.1f, 0.1f, blade = true),
                s(300, 0.12f, 0.12f, blade = true), // same cell, later
                s(200, 9f, 9f, blade = false),       // ignored (blade off)
            ),
            size = 0.25f,
        )
        assertEquals(1, cells.size)
        assertEquals(300L, cells[Cell(0, 0)])
    }

    @Test fun tractionFullWhenActualMatchesCommanded() {
        // 0.5 m/s for 0.2s = 0.10 m commanded; moved 0.10 m → ratio 1.0
        val cells = tractionCells(listOf(s(0, 0f, 0f, cmd = 0.5f), s(200, 0.10f, 0f, cmd = 0.5f)), 0.25f)
        assertEquals(1f, cells[Cell(0, 0)]!!, 1e-3f)
    }

    @Test fun tractionDropsWhenSlipping() {
        // commanded 0.10 m but only moved 0.04 m → ratio 0.4
        val cells = tractionCells(listOf(s(0, 0f, 0f, cmd = 0.5f), s(200, 0.04f, 0f, cmd = 0.5f)), 0.25f)
        assertTrue(cells[Cell(0, 0)]!! in 0.39f..0.41f)
    }

    @Test fun tractionSkipsSessionGap() {
        val cells = tractionCells(listOf(s(0, 0f, 0f, cmd = 0.5f), s(5000, 5f, 0f, cmd = 0.5f)), 0.25f)
        assertTrue(cells.isEmpty())
    }
}
