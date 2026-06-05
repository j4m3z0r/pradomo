package id.au.james.lymow.input

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** The on-screen joystick as an InputSource. A USB-C gamepad source replaces this in Phase 2. */
class TouchJoystickSource : InputSource {
    private val _vector = MutableStateFlow(ControlVector.ZERO)
    override val vector: StateFlow<ControlVector> = _vector.asStateFlow()
    override val buttons: Flow<ButtonEvent> = emptyFlow()
    fun set(drive: Float, turn: Float) { _vector.value = ControlVector(drive, turn) }
}
