package id.au.james.lymow.protocol

object LymowProtocol {
    const val SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
    const val CONTROL_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"
    const val DEVICE_NAME_PREFIX = "Lymow_"
    const val DRIVE_LIMIT = 0.5f
    const val TURN_LIMIT = 0.6f

    private val JOYSTICK_HEADER =
        byteArrayOf(0x10, 0x31, 0x38, 0x02, 0x52, 0x0a, 0x0d)
    private const val TURN_TAG: Byte = 0x15

    fun encodeJoystick(drive: Float, turn: Float): ByteArray {
        val d = drive.coerceIn(-DRIVE_LIMIT, DRIVE_LIMIT)
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

    fun makeClientId(model: String = "Android", host: String = "phone"): String {
        val rand = (kotlin.random.Random.nextLong() and Long.MAX_VALUE)
            .toString(16).padStart(16, '0')
        return "${model}_${host}_$rand"
    }
}
