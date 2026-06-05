package id.au.james.lymow.input

import kotlinx.coroutines.flow.Flow

/** Normalised control: drive/turn each in [-1f, 1f]. */
data class ControlVector(val drive: Float, val turn: Float) {
    companion object { val ZERO = ControlVector(0f, 0f) }
}

/** Discrete button press from a physical controller (Phase 2). */
data class ButtonEvent(val buttonId: Int, val pressed: Boolean)

/**
 * A source of control input. v1 has one implementation (the on-screen
 * joystick); a USB-C gamepad source plugs in here later with no other changes.
 */
interface InputSource {
    val vector: Flow<ControlVector>
    val buttons: Flow<ButtonEvent>
}
