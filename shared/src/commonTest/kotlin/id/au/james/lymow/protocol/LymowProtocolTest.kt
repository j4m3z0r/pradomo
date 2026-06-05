package id.au.james.lymow.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

class LymowProtocolTest {
    @Test fun joystick_stop() =
        assertEquals("10313802520a0d000000001500000000", LymowProtocol.encodeJoystick(0f, 0f).hex())

    @Test fun joystick_forward() =
        assertEquals("10313802520a0d0000003f1500000000", LymowProtocol.encodeJoystick(0.5f, 0f).hex())

    @Test fun joystick_backward() =
        assertEquals("10313802520a0d000000bf1500000000", LymowProtocol.encodeJoystick(-0.5f, 0f).hex())

    @Test fun joystick_left() =
        assertEquals("10313802520a0d00000000159a99193f", LymowProtocol.encodeJoystick(0f, 0.6f).hex())

    @Test fun joystick_right() =
        assertEquals("10313802520a0d00000000159a9919bf", LymowProtocol.encodeJoystick(0f, -0.6f).hex())

    @Test fun joystick_clamps_drive() =
        assertEquals(LymowProtocol.encodeJoystick(0.5f, 0f).hex(), LymowProtocol.encodeJoystick(9f, 0f).hex())

    @Test fun joystick_clamps_turn() =
        assertEquals(LymowProtocol.encodeJoystick(0f, -0.6f).hex(), LymowProtocol.encodeJoystick(0f, -9f).hex())
}
