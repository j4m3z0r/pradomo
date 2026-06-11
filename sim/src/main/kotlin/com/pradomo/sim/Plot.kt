package com.pradomo.sim

import com.pradomo.control.maneuver.Pose
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Tiny dependency-free SVG plotter for run trajectories (true vs estimated path). */
object Plot {
    fun write(result: RunResult, file: File) {
        val W = 640; val H = 640; val pad = 40
        val pts = (result.truePath + result.estPath).ifEmpty { listOf(Pose(0f, 0f, 0f)) }
        var minX = pts.minOf { it.x }; var maxX = pts.maxOf { it.x }
        var minY = pts.minOf { it.y }; var maxY = pts.maxOf { it.y }
        // keep aspect square
        val spanX = max(0.5f, maxX - minX); val spanY = max(0.5f, maxY - minY)
        val span = max(spanX, spanY) * 1.15f
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        minX = cx - span / 2; maxX = cx + span / 2; minY = cy - span / 2; maxY = cy + span / 2
        fun sx(x: Float) = pad + (x - minX) / (maxX - minX) * (W - 2 * pad)
        // SVG y is down; flip so +y world is up
        fun sy(y: Float) = H - (pad + (y - minY) / (maxY - minY) * (H - 2 * pad))

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$W" height="$H" viewBox="0 0 $W $H">""")
        sb.append("""<rect width="$W" height="$H" fill="#111813"/>""")
        fun poly(path: List<Pose>, color: String, w: Float) {
            if (path.isEmpty()) return
            val d = path.joinToString(" ") { "${sx(it.x)},${sy(it.y)}" }
            sb.append("""<polyline points="$d" fill="none" stroke="$color" stroke-width="$w"/>""")
        }
        poly(result.estPath, "#5b6f5b", 1.5f)      // estimated (what the controller saw)
        poly(result.truePath, "#4ade80", 2.5f)     // true path

        fun arrow(p: Pose, color: String) {
            val L = (maxX - minX) * 0.05f
            val hx = p.x + L * cos(p.heading); val hy = p.y + L * sin(p.heading)
            sb.append("""<line x1="${sx(p.x)}" y1="${sy(p.y)}" x2="${sx(hx)}" y2="${sy(hy)}" stroke="$color" stroke-width="3"/>""")
            sb.append("""<circle cx="${sx(p.x)}" cy="${sy(p.y)}" r="4" fill="$color"/>""")
        }
        val start = result.truePath.firstOrNull() ?: Pose(0f, 0f, 0f)
        val end = result.truePath.lastOrNull() ?: start
        arrow(start, "#8aa0ff")
        arrow(end, "#ff5b5b")

        val m = result.metrics
        val label = buildString {
            append("${result.behavior} · ${result.strategy} · ${result.scenario}\n")
            if (m.headingErrRad != 0f || result.behavior == "kturn")
                append("headErr=%.1f°  place=%.0fcm  minTread=%.2f  t=%.1fs%s".format(
                    Math.toDegrees(m.headingErrRad.toDouble()), m.placementErrM * 100, m.minTreadFrac, m.timeSec,
                    if (!m.completed) "  INCOMPLETE" else ""))
            else
                append("crossRMS=%.0fcm  max=%.0fcm  headErr=%.1f°".format(
                    m.crossTrackRmsM * 100, m.crossTrackMaxM * 100, Math.toDegrees(m.headingErrRad.toDouble())))
            append("  score=%.2f".format(m.balanced))
        }
        label.split("\n").forEachIndexed { i, line ->
            sb.append("""<text x="10" y="${20 + i * 18}" fill="#cfd8cf" font-family="monospace" font-size="13">${esc(line)}</text>""")
        }
        sb.append("</svg>")
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
