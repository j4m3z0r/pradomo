package com.pradomo.protocol

import kotlin.io.encoding.Base64

object LymowProtocol {
    const val SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
    const val CONTROL_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"
    const val DEVICE_NAME_PREFIX = "Lymow_"
    const val DRIVE_LIMIT = 0.5f      // normal full-stick linear speed (conservative default)
    const val MAX_LINEAR = 1.0f       // hard safety ceiling for the linear field (allows Turbo > 0.5)
    const val TURN_LIMIT = 0.6f

    private val JOYSTICK_HEADER =
        byteArrayOf(0x10, 0x31, 0x38, 0x02, 0x52, 0x0a, 0x0d)
    private const val TURN_TAG: Byte = 0x15

    fun encodeJoystick(drive: Float, turn: Float): ByteArray {
        val d = drive.coerceIn(-MAX_LINEAR, MAX_LINEAR)
        val t = turn.coerceIn(-TURN_LIMIT, TURN_LIMIT)
        return JOYSTICK_HEADER + floatLe(d) + byteArrayOf(TURN_TAG) + floatLe(t)
    }

    private fun floatLe(v: Float): ByteArray {
        val bits = v.toRawBits()
        return byteArrayOf(
            (bits and 0xFF).toByte(),
            ((bits ushr 8) and 0xFF).toByte(),
            ((bits ushr 16) and 0xFF).toByte(),
            ((bits ushr 24) and 0xFF).toByte(),
        )
    }

    /**
     * Build a "set cutting deck height + blade speed" PbInput:
     *
     *   PbInput{ f2=49 version, f13{ f2=bladeSpeed, f3=cutHeight }, f16=32 (only when blade on) }
     *
     * bladeSpeed: 0=off, 3=Co, 4=standard, 5=power, 6=turbo (see [BladeSpeed]).
     * cutHeight: device value (see [DeckHeights]; 0..127). One frame sets both;
     * the device latches them, so it is sent once per change.
     */
    fun encodeDeckBlade(bladeSpeed: Int, cutHeight: Int): ByteArray {
        require(bladeSpeed in 0..127) { "bladeSpeed out of range" }
        require(cutHeight in 0..127) { "cutHeight out of range" }
        val sub = byteArrayOf(0x10, bladeSpeed.toByte(), 0x18, cutHeight.toByte())
        var out = byteArrayOf(0x10, 0x31, 0x6a, sub.size.toByte()) + sub
        if (bladeSpeed > 0) out += byteArrayOf(0x80.toByte(), 0x01, 0x20)
        return out
    }

    val INIT_FRAMES: List<ByteArray> = listOf(
        byteArrayOf(0x10, 0x31, 0x4a, 0x02, 0x58, 0x01),
        byteArrayOf(0x38, 0x02, 0xda.toByte(), 0x01, 0x00),
        byteArrayOf(0x10, 0x31, 0x28, 0x35),
        byteArrayOf(0x10, 0x31, 0x4a, 0x02, 0x10, 0x01),
        byteArrayOf(0x10, 0x31, 0x28, 0x14, 0x38, 0x02, 0x4a, 0x02, 0x28, 0x01),
    )

    private val KEEPALIVE_PREFIX = byteArrayOf(0x38, 0x02, 0xda.toByte(), 0x01)

    fun keepaliveFrame(clientId: String): ByteArray {
        val cid = clientId.encodeToByteArray()
        require(cid.size <= 255) { "client_id too long" }
        return KEEPALIVE_PREFIX + byteArrayOf(cid.size.toByte()) + cid
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun toBle(payload: ByteArray): ByteArray = Base64.encode(payload).encodeToByteArray()
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun fromBle(data: ByteArray): ByteArray = Base64.decode(data.decodeToString())

    fun makeClientId(model: String = "Android", host: String = "phone"): String {
        val rand = (kotlin.random.Random.nextLong() and Long.MAX_VALUE)
            .toString(16).padStart(16, '0')
        return "${model}_${host}_$rand"
    }
}
