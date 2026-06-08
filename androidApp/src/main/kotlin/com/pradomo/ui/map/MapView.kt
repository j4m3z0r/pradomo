@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pradomo.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pradomo.map.CELL_SIZE
import com.pradomo.map.MapSample
import com.pradomo.map.mowTimeCells
import com.pradomo.map.tractionCells
import com.pradomo.protocol.Telemetry
import com.pradomo.ui.theme.PradomoColors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private enum class Layer(val label: String) { PATH("Path"), MOWTIME("Mow-time"), TRACTION("Traction") }

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val OVERDUE_MS = 7 * DAY_MS // fresh (0) → overdue (7 days)

@Composable
fun MapView(pad: PaddingValues, trail: List<MapSample>, telemetry: Telemetry?, onClear: () -> Unit) {
    val curPos = telemetry?.position
    var layer by remember { mutableStateOf(Layer.PATH) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val mowCells = remember(trail) { mowTimeCells(trail) }
    val tracCells = remember(trail) { tractionCells(trail) }

    Box(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
        if (trail.isEmpty() && curPos == null) {
            Text(
                "Drive the mower to start mapping its yard.\nPath, mow-time and traction layers build up as you go.",
                color = PradomoColors.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(0.5f, 25f)
                        // Keep the pinch centroid fixed while scaling, then apply the drag.
                        pan = centroid - (centroid - pan) * (newZoom / zoom) + panChange
                        zoom = newZoom
                    }
                }
                .graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                    transformOrigin = TransformOrigin(0f, 0f)
                    clip = true
                },
        ) {
            val xs = trail.map { it.x } + listOfNotNull(curPos?.first)
            val ys = trail.map { it.y } + listOfNotNull(curPos?.second)
            val minX = xs.min(); val maxX = xs.max()
            val minY = ys.min(); val maxY = ys.max()
            val spanX = max(maxX - minX, 1f)
            val spanY = max(maxY - minY, 1f)
            val margin = 32.dp.toPx()
            val usableW = size.width - 2 * margin
            val usableH = size.height - 2 * margin
            val scale = minOf(usableW / spanX, usableH / spanY)
            val offX = margin + (usableW - spanX * scale) / 2f
            val offY = margin + (usableH - spanY * scale) / 2f

            fun toScreen(x: Float, y: Float) = Offset(
                offX + (x - minX) * scale,
                size.height - (offY + (y - minY) * scale), // flip Y: world north = screen up
            )

            fun drawCell(gx: Int, gy: Int, color: Color) {
                val c1 = toScreen(gx * CELL_SIZE, gy * CELL_SIZE)
                val c2 = toScreen((gx + 1) * CELL_SIZE, (gy + 1) * CELL_SIZE)
                val tl = Offset(min(c1.x, c2.x), min(c1.y, c2.y))
                drawRect(color, tl, Size(kotlin.math.abs(c2.x - c1.x), kotlin.math.abs(c2.y - c1.y)))
            }

            when (layer) {
                Layer.PATH -> {
                    val w = 3.dp.toPx()
                    for (i in 1 until trail.size) {
                        val a = trail[i - 1]; val b = trail[i]
                        val color = if (b.bladeOn) PradomoColors.connected else PradomoColors.hairline
                        drawLine(color, toScreen(a.x, a.y), toScreen(b.x, b.y), w, StrokeCap.Round)
                    }
                    trail.firstOrNull()?.let { drawCircle(PradomoColors.textSecondary, 4.dp.toPx(), toScreen(it.x, it.y)) }
                }
                Layer.MOWTIME -> {
                    val now = System.currentTimeMillis()
                    for ((cell, t) in mowCells) {
                        val frac = ((now - t).toFloat() / OVERDUE_MS).coerceIn(0f, 1f)
                        drawCell(cell.gx, cell.gy, lerp(PradomoColors.connected, PradomoColors.danger, frac).copy(alpha = 0.6f))
                    }
                }
                Layer.TRACTION -> {
                    for ((cell, ratio) in tracCells) {
                        drawCell(cell.gx, cell.gy, lerp(PradomoColors.danger, PradomoColors.connected, ratio).copy(alpha = 0.6f))
                    }
                }
            }

            // Live mower marker: arrowhead along heading (east=0, CCW).
            val mx = curPos?.first ?: trail.last().x
            val my = curPos?.second ?: trail.last().y
            val heading = telemetry?.heading ?: trail.lastOrNull()?.heading ?: 0f
            val c = toScreen(mx, my)
            val r = 10.dp.toPx()
            val dirX = cos(heading); val dirY = -sin(heading)
            val perpX = -dirY; val perpY = dirX
            val tip = Offset(c.x + dirX * r, c.y + dirY * r)
            val left = Offset(c.x - dirX * r * 0.6f + perpX * r * 0.6f, c.y - dirY * r * 0.6f + perpY * r * 0.6f)
            val right = Offset(c.x - dirX * r * 0.6f - perpX * r * 0.6f, c.y - dirY * r * 0.6f - perpY * r * 0.6f)
            val arrow = Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }
            drawPath(arrow, PradomoColors.driveAccent)
            drawCircle(PradomoColors.driveAccent, 2.5.dp.toPx(), c)
        }

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Layer.entries.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = layer == l,
                        onClick = { layer = l },
                        shape = SegmentedButtonDefaults.itemShape(i, Layer.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = PradomoColors.mossDeep,
                            activeContentColor = PradomoColors.textPrimary,
                        ),
                        icon = {},
                    ) { Text(l.label, maxLines = 1) }
                }
            }
            Spacer(Modifier.size(6.dp))
            LegendFor(layer, mowCells.isEmpty(), tracCells.isEmpty())
        }

        if (zoom != 1f || pan != Offset.Zero) {
            TextButton(
                onClick = { zoom = 1f; pan = Offset.Zero },
                modifier = Modifier.align(Alignment.BottomStart),
            ) { Text("Fit", color = PradomoColors.driveAccent) }
        }
        if (trail.isNotEmpty()) {
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.BottomEnd)) {
                Text("Clear map", color = PradomoColors.danger)
            }
        }
    }
}

@Composable
private fun LegendFor(layer: Layer, mowEmpty: Boolean, tracEmpty: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (layer) {
            Layer.PATH -> {
                LegendDot(PradomoColors.connected); Text("mowed", color = PradomoColors.textSecondary)
                Spacer(Modifier.width(12.dp))
                LegendDot(PradomoColors.hairline); Text("transit", color = PradomoColors.textSecondary)
            }
            Layer.MOWTIME -> {
                LegendDot(PradomoColors.connected); Text("fresh", color = PradomoColors.textSecondary)
                Spacer(Modifier.width(12.dp))
                LegendDot(PradomoColors.danger); Text("overdue", color = PradomoColors.textSecondary)
                if (mowEmpty) {
                    Spacer(Modifier.width(12.dp)); Text("— mow with blades on", color = PradomoColors.textSecondary)
                }
            }
            Layer.TRACTION -> {
                LegendDot(PradomoColors.connected); Text("grip", color = PradomoColors.textSecondary)
                Spacer(Modifier.width(12.dp))
                LegendDot(PradomoColors.danger); Text("slip", color = PradomoColors.textSecondary)
                if (tracEmpty) {
                    Spacer(Modifier.width(12.dp)); Text("— drive to measure", color = PradomoColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
    Spacer(Modifier.width(4.dp))
}
