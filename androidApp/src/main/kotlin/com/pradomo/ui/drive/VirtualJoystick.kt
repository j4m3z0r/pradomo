package com.pradomo.ui.drive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.gestures.detectDragGestures
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
import kotlinx.coroutines.launch

@Composable
fun VirtualJoystick(
    enabled: Boolean,
    drive: Float,
    turn: Float,
    onVector: (drive: Float, turn: Float) -> Unit,
    modifier: Modifier = Modifier,
    gateSize: Dp = Dimens.joystickGate,
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
        drawCircle(color = PradomoColors.hairline, radius = r, center = c, style = Stroke(1.5.dp.toPx()))
        // Four direction ticks.
        val tickColor = if (enabled) PradomoColors.textSecondary else PradomoColors.textDisabled
        val tw = 4.dp.toPx()
        val tOut = r * 0.93f
        val tIn = r * 0.80f
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
