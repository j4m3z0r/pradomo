package com.pradomo.map

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/** Grid cell index for the heatmaps. */
data class Cell(val gx: Int, val gy: Int)

/** Default grid resolution (metres). */
const val CELL_SIZE = 0.25f

fun cellOf(x: Float, y: Float, size: Float = CELL_SIZE) =
    Cell(floor(x / size).toInt(), floor(y / size).toInt())

/**
 * Mow-time heatmap: each cell → the most recent time (millis) a blades-on sample
 * touched it. Render as time-since-mow (fresh → overdue).
 */
fun mowTimeCells(samples: List<MapSample>, size: Float = CELL_SIZE): Map<Cell, Long> {
    val out = HashMap<Cell, Long>()
    for (s in samples) {
        if (!s.bladeOn) continue
        val c = cellOf(s.x, s.y, size)
        val prev = out[c]
        if (prev == null || s.tMillis > prev) out[c] = s.tMillis
    }
    return out
}

/**
 * Traction heatmap: each cell → actual/commanded distance ratio in 0..1 (1 = no
 * slip, lower = the mower moved less than commanded → poor traction). Computed from
 * consecutive samples; session-break gaps (dt>2s) and idle samples are skipped.
 */
fun tractionCells(samples: List<MapSample>, size: Float = CELL_SIZE): Map<Cell, Float> {
    val commanded = HashMap<Cell, Float>()
    val actual = HashMap<Cell, Float>()
    for (i in 1 until samples.size) {
        val a = samples[i - 1]
        val b = samples[i]
        val dtSec = (b.tMillis - a.tMillis).coerceAtLeast(0L) / 1000f
        if (dtSec <= 0f || dtSec > 2f) continue
        val cmd = abs(b.cmdLinear) * dtSec
        if (cmd < 1e-3f) continue
        val c = cellOf(b.x, b.y, size)
        commanded[c] = (commanded[c] ?: 0f) + cmd
        actual[c] = (actual[c] ?: 0f) + hypot(b.x - a.x, b.y - a.y)
    }
    val out = HashMap<Cell, Float>()
    for ((c, cm) in commanded) {
        out[c] = ((actual[c] ?: 0f) / cm).coerceIn(0f, 1f)
    }
    return out
}
