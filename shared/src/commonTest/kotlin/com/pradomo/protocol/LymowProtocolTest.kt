package com.pradomo.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

class LymowProtocolTest {
    @Test fun deck_blade_golden() {
        // Golden frames for the deck-height / blade-speed encoding.
        assertEquals("10316a0410001860", LymowProtocol.encodeDeckBlade(0, 96).hex())   // off, height 96
        assertEquals("10316a0410001823", LymowProtocol.encodeDeckBlade(0, 35).hex())   // off, height 35
        assertEquals("10316a0410031860800120", LymowProtocol.encodeDeckBlade(3, 96).hex()) // Co + f16
        assertEquals("10316a0410061860800120", LymowProtocol.encodeDeckBlade(6, 96).hex()) // Turbo + f16
    }

    @Test fun deck_height_table() {
        assertEquals(75, DeckHeights.encoded(75))
        assertEquals(79, DeckHeights.encoded(80))
        assertEquals(96, DeckHeights.encoded(100))
        assertEquals(BladeSpeed.TURBO, BladeSpeed.fromCode(6))
        assertEquals(BladeSpeed.OFF, BladeSpeed.fromCode(99))
    }

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

    @Test fun joystick_clamps_drive() = // linear clamps at the MAX_LINEAR ceiling (1.0), not 0.5
        assertEquals(LymowProtocol.encodeJoystick(1.0f, 0f).hex(), LymowProtocol.encodeJoystick(9f, 0f).hex())

    @Test fun joystick_clamps_turn() =
        assertEquals(LymowProtocol.encodeJoystick(0f, -0.6f).hex(), LymowProtocol.encodeJoystick(0f, -9f).hex())

    @Test fun init_frames_match_capture() {
        assertEquals(
            listOf(
                "10314a025801",
                "3802da0100",
                "10312835",
                "10314a021001",
                "1031281438024a022801",
            ),
            LymowProtocol.INIT_FRAMES.map { it.hex() },
        )
    }

    @Test fun keepalive_golden() {
        val frame = LymowProtocol.keepaliveFrame("Android_phone_2b3f7b75a62d548a")
        assertEquals(
            "3802da011e416e64726f69645f70686f6e655f32623366376237356136326435343861",
            frame.hex(),
        )
    }

    @Test fun keepalive_length_prefix() {
        val frame = LymowProtocol.keepaliveFrame("abc")
        assertEquals("3802da01", frame.copyOfRange(0, 4).hex())
        assertEquals(3, frame[4].toInt())
        assertEquals("abc", frame.copyOfRange(5, frame.size).decodeToString())
    }

    @Test fun make_client_id_format() {
        val cid = LymowProtocol.makeClientId(model = "Android", host = "pixel")
        val rand = cid.substringAfterLast("_")
        assertEquals("Android_pixel", cid.removeSuffix("_$rand"))
        assertEquals(16, rand.length)
        rand.toLong(16) // parses as hex, else throws
    }

    @Test fun to_ble_from_ble_roundtrip() {
        val payload = LymowProtocol.encodeJoystick(-0.5f, 0f)
        val wire = LymowProtocol.toBle(payload)
        assertEquals("EDE4AlIKDQAAAL8VAAAAAA==", wire.decodeToString())
        assertEquals(payload.hex(), LymowProtocol.fromBle(wire).hex())
    }
}
