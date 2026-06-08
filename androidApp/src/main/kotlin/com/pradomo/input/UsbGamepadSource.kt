package com.pradomo.input

import android.view.InputDevice
import android.view.MotionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * A USB-C / Bluetooth gamepad as an [InputSource]. Gamepad input on Android
 * arrives as joystick [MotionEvent]s dispatched to the focused window, so the
 * Activity forwards them here via [onMotionEvent]. The **left stick** drives the
 * mower: X = turn (right = turn right), Y = drive (up = forward).
 *
 * Implemented as a process singleton because the Activity (which receives the
 * input events) and the DriveViewModel (which consumes the vector) need to share
 * one instance, and a gamepad is a process-global device, not a screen concern.
 * This is the Phase-2 `UsbGamepadSource` the [InputSource] seam was designed for.
 */
object UsbGamepadSource : InputSource {
    private val _vector = MutableStateFlow(ControlVector.ZERO)
    override val vector: StateFlow<ControlVector> = _vector.asStateFlow()

    private val _buttons = MutableSharedFlow<ButtonEvent>(extraBufferCapacity = 32)
    override val buttons: Flow<ButtonEvent> = _buttons

    /** Called by the Activity on a gamepad button down/up (keyCode). */
    fun onButton(buttonId: Int, pressed: Boolean) {
        _buttons.tryEmit(ButtonEvent(buttonId, pressed))
    }

    /** Name of the gamepad currently present, or null if none / unplugged. */
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /** Set by the Activity when it detects a gamepad (e.g. hot-plugged), so the UI notices it
     *  without waiting for the first stick movement. */
    fun setDeviceName(name: String?) {
        _deviceName.value = name
    }

    /** Minimum centre deadzone, used if the device reports a smaller (or no) flat range. */
    private const val MIN_DEADZONE = 0.12f

    /**
     * Per-axis sensitivity: a deadzone-normalised axis value of this much (or
     * more) commands 100% output, then clamps. The test stick has a circular
     * gate — full straight throw reaches ~0.7 on one axis, but full *diagonal*
     * only ~0.45 per axis. Setting full-scale to the diagonal value lets the
     * corners reach full drive + full turn together (straight pushes just clamp
     * a little sooner).
     */
    private const val FULL_SCALE = 0.45f

    /**
     * Process a joystick [MotionEvent] and update [vector]. Returns true if this
     * was a joystick move we handled (so the Activity can stop dispatching it).
     */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device
        _deviceName.value = device?.name
        val x = centeredAxis(event, device, MotionEvent.AXIS_X)
        val y = centeredAxis(event, device, MotionEvent.AXIS_Y)
        // Android: AXIS_Y is -1 up / +1 down, AXIS_X is -1 left / +1 right.
        // up = forward (positive drive); right = turn right (negative turn, matching the protocol).
        // Divide by FULL_SCALE so a moderate push reaches 100% output, then clamp.
        _vector.value = ControlVector(
            drive = (-y / FULL_SCALE).coerceIn(-1f, 1f),
            turn = (-x / FULL_SCALE).coerceIn(-1f, 1f),
        )
        return true
    }

    /**
     * A device was unplugged/removed — centre the stick so the mower stops.
     * Safety: an analog stick can't spring back if it's been physically yanked,
     * and with no protocol watchdog a stale non-zero value would keep driving.
     */
    fun onDeviceRemoved() {
        _vector.value = ControlVector.ZERO
        _deviceName.value = null
    }

    /** Read an axis, apply the device's deadzone, and rescale so motion starts smoothly from 0. */
    private fun centeredAxis(event: MotionEvent, device: InputDevice?, axis: Int): Float {
        val flat = device?.getMotionRange(axis, event.source)?.flat ?: 0f
        val deadzone = maxOf(flat, MIN_DEADZONE)
        val value = event.getAxisValue(axis)
        if (abs(value) <= deadzone) return 0f
        val sign = if (value > 0f) 1f else -1f
        return (sign * (abs(value) - deadzone) / (1f - deadzone)).coerceIn(-1f, 1f)
    }
}
