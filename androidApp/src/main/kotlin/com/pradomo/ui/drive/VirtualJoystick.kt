package com.pradomo.ui.drive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pradomo.ui.theme.Dimens
import com.pradomo.ui.theme.PradomoColors
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Which edge of the joystick's outer ring was tapped (Auto-mode maneuver triggers). */
enum class EdgeZone { LEFT, RIGHT, UP, DOWN }

@Composable
fun VirtualJoystick(
    enabled: Boolean,
    drive: Float,
    turn: Float,
    onVector: (drive: Float, turn: Float) -> Unit,
    modifier: Modifier = Modifier,
    gateSize: Dp = Dimens.joystickGate,
    autoMode: Boolean = false,
    onEdgeTap: (EdgeZone) -> Unit = {},
    maneuverActive: Boolean = false,
) {
    val density = LocalDensity.current
    val gatePx = with(density) { gateSize.toPx() }
    val knobRadiusPx = with(density) { (gateSize * 0.18f).toPx() } // knob ≈ 36% of gate diameter
    val travel = gatePx / 2f - knobRadiusPx
    val deadzone = travel * 0.15f
    val scope = rememberCoroutineScope()
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var touched by remember { mutableStateOf(false) }

    // Mirror the commanded vector (e.g. from the physical stick) when not touching the screen.
    LaunchedEffect(drive, turn, touched, travel) {
        if (!touched && travel > 0f) {
            var target = Offset(-turn * travel, -drive * travel)
            val d = target.getDistance()
            if (d > travel) target *= (travel / d)
            knob.animateTo(target, tween(90))
        }
    }

    fun emit(offset: Offset) {
        val dist = offset.getDistance()
        val clamped = if (dist > travel) offset * (travel / dist) else offset
        if (clamped.getDistance() <= deadzone) { onVector(0f, 0f); return }
        val d = (-clamped.y / travel).coerceIn(-1f, 1f)
        val t = (-clamped.x / travel).coerceIn(-1f, 1f) // +x (right) -> negative turn (= turn right)
        onVector(d, t)
    }

    Canvas(
        modifier
            .size(gateSize)
            // Auto mode: a tap on the outer ring fires a maneuver (no drive). Separate
            // pointerInput from the drag below — one pointerInput can't host two gesture
            // loops. A tap isn't a drag, so the two don't fight.
            .pointerInput(enabled, autoMode) {
                if (!enabled || !autoMode) return@pointerInput
                detectTapGestures(onTap = { off ->
                    val r = size.width / 2f
                    val v = off - Offset(r, size.height / 2f)
                    if (v.getDistance() < r * 0.5f) return@detectTapGestures // centre: ignore
                    val zone = if (abs(v.x) >= abs(v.y)) {
                        if (v.x >= 0f) EdgeZone.RIGHT else EdgeZone.LEFT
                    } else {
                        if (v.y < 0f) EdgeZone.UP else EdgeZone.DOWN
                    }
                    onEdgeTap(zone)
                })
            }
            // Drag drives the mower (both Speed and Auto modes).
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { touched = true },
                    onDragEnd = { touched = false; onVector(0f, 0f) },   // STOP + let mirror return to centre
                    onDragCancel = { touched = false; onVector(0f, 0f) },
                ) { change, drag ->
                    change.consume()
                    val raw = knob.value + drag
                    val dist = raw.getDistance()
                    val clamped = if (dist > travel) raw * (travel / dist) else raw
                    scope.launch { knob.snapTo(clamped) }
                    emit(clamped)
                }
            }
    ) {
        val c = center
        val r = gatePx / 2f
        // Gate: dark dish with a subtle radial shade + hairline rim.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PradomoColors.surface2, PradomoColors.surface1),
                center = c, radius = r,
            ),
            radius = r, center = c,
        )
        val auto = autoMode && enabled
        // In Auto mode, make the tappable edge zones visible: a soft band segment at each
        // of the four edges (these are the maneuver "buttons").
        if (auto) {
            val band = r * 0.40f
            val bandR = r - band / 2f - 1.dp.toPx()
            val zone = (if (maneuverActive) PradomoColors.warning else PradomoColors.connected).copy(alpha = 0.13f)
            for (centerDeg in listOf(0f, 90f, 180f, 270f)) {
                drawArc(
                    color = zone,
                    startAngle = centerDeg - 27f,
                    sweepAngle = 54f,
                    useCenter = false,
                    topLeft = Offset(c.x - bandR, c.y - bandR),
                    size = androidx.compose.ui.geometry.Size(bandR * 2f, bandR * 2f),
                    style = Stroke(width = band),
                )
            }
        }
        // Rim: amber while a maneuver is driving the mower, bright in Auto mode (tappable).
        val rimColor = when {
            maneuverActive -> PradomoColors.warning
            auto -> PradomoColors.connected
            else -> PradomoColors.hairline
        }
        drawCircle(color = rimColor, radius = r, center = c, style = Stroke((if (auto || maneuverActive) 2.5 else 1.5).dp.toPx()))
        // Four direction ticks (accent + longer in Auto mode: these mark the tap zones).
        val tickColor = when {
            maneuverActive -> PradomoColors.warning
            auto -> PradomoColors.connected
            enabled -> PradomoColors.textSecondary
            else -> PradomoColors.textDisabled
        }
        val tw = (if (auto) 6 else 4).dp.toPx()
        val tOut = r * 0.95f
        val tIn = r * (if (auto) 0.70f else 0.80f)
        drawLine(tickColor, Offset(c.x, c.y - tOut), Offset(c.x, c.y - tIn), tw, StrokeCap.Round)
        drawLine(tickColor, Offset(c.x, c.y + tIn), Offset(c.x, c.y + tOut), tw, StrokeCap.Round)
        drawLine(tickColor, Offset(c.x - tOut, c.y), Offset(c.x - tIn, c.y), tw, StrokeCap.Round)
        drawLine(tickColor, Offset(c.x + tIn, c.y), Offset(c.x + tOut, c.y), tw, StrokeCap.Round)
        // Knob: dark cap with a bright green ring.
        val kc = c + knob.value
        drawCircle(color = Color(0xFF17201A), radius = knobRadiusPx, center = kc)
        drawCircle(
            color = if (enabled) PradomoColors.connected else PradomoColors.textDisabled,
            radius = knobRadiusPx, center = kc, style = Stroke(width = 5.dp.toPx()),
        )
    }
}
