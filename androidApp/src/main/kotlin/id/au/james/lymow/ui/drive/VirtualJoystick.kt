package id.au.james.lymow.ui.drive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import id.au.james.lymow.ui.theme.Dimens
import id.au.james.lymow.ui.theme.LymowColors
import kotlinx.coroutines.launch

@Composable
fun VirtualJoystick(
    enabled: Boolean,
    onVector: (drive: Float, turn: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gatePx = with(density) { Dimens.joystickGate.toPx() }
    val knobRadiusPx = with(density) { (Dimens.joystickKnob / 2).toPx() }
    val travel = gatePx / 2f - knobRadiusPx
    val deadzone = travel * 0.15f
    val scope = rememberCoroutineScope()
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    fun emit(offset: Offset) {
        val dist = offset.getDistance()
        val clamped = if (dist > travel) offset * (travel / dist) else offset
        if (clamped.getDistance() <= deadzone) { onVector(0f, 0f); return }
        val drive = (-clamped.y / travel).coerceIn(-1f, 1f)
        val turn = (-clamped.x / travel).coerceIn(-1f, 1f) // +x (right) -> negative turn (= turn right)
        onVector(drive, turn)
    }

    Canvas(
        modifier
            .size(Dimens.joystickGate)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragEnd = {
                        onVector(0f, 0f)                     // STOP immediately on release
                        scope.launch { knob.animateTo(Offset.Zero, tween(120)) }
                    },
                    onDragCancel = {
                        onVector(0f, 0f)
                        scope.launch { knob.animateTo(Offset.Zero, tween(120)) }
                    },
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
        drawCircle(
            color = if (enabled) LymowColors.surface2 else LymowColors.surface2.copy(alpha = 0.4f),
            radius = gatePx / 2f, center = c,
        )
        drawCircle(
            color = if (enabled) LymowColors.hairline else LymowColors.textDisabled,
            radius = gatePx / 2f, center = c, style = Stroke(width = 2.dp.toPx()),
        )
        if (enabled && knob.value.getDistance() > deadzone) {
            drawLine(LymowColors.driveAccent, c, c + knob.value, strokeWidth = 4.dp.toPx())
        }
        drawCircle(
            color = if (enabled) LymowColors.textPrimary else LymowColors.textDisabled,
            radius = knobRadiusPx, center = c + knob.value,
        )
    }
}
