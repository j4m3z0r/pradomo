package id.au.james.lymow.protocol

import kotlin.io.encoding.Base64

class Telemetry(val robotStatus: Int?, val battery: Int?) {
    val statusName: String? get() = robotStatus?.let { RobotStatus.name(it) }
}

object RobotStatus {
    const val CHARGING = 5
    const val REMOTE_CONTROL = 6
    private val NAMES = mapOf(
        0 to "none", 1 to "waiting", 2 to "cleaning", 3 to "paused", 4 to "docking",
        5 to "charging", 6 to "remote_control", 7 to "error", 8 to "resuming",
        9 to "zone_partition", 10 to "paused_docking", 11 to "updating",
        12 to "charging_full", 13 to "emergency_stop",
    )
    fun name(value: Int): String = NAMES[value] ?: "unknown_$value"
}

private const val WT_VARINT = 0
private const val WT_LEN = 2

/** Decode a PbOutput frame (base64 text OR raw protobuf bytes). Never throws. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun decodeTelemetry(payload: ByteArray): Telemetry {
    val raw = coerceRaw(payload)
    var status: Int? = null
    var battery: Int? = null
    val r = ProtoReader(raw)
    while (r.hasNext()) {
        val f = r.readField() ?: break
        if (f.number == 5 && f.wireType == WT_LEN && f.bytes != null) { // PbRobotInfo
            val sub = ProtoReader(f.bytes)
            while (sub.hasNext()) {
                val g = sub.readField() ?: break
                if (g.wireType == WT_VARINT) when (g.number) {
                    1 -> status = g.varint?.toInt()
                    2 -> battery = g.varint?.toInt()
                }
            }
        }
    }
    return Telemetry(status, battery)
}

private val B64_CHARS =
    (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('+', '/', '=')).toSet()

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun coerceRaw(payload: ByteArray): ByteArray {
    if (payload.isNotEmpty() && payload.all { (it.toInt() and 0xFF).toChar() in B64_CHARS }) {
        return try { Base64.decode(payload.decodeToString()) } catch (_: Exception) { payload }
    }
    return payload
}

private class Field(
    val number: Int,
    val wireType: Int,
    val varint: Long? = null,
    val bytes: ByteArray? = null,
)

private class ProtoReader(private val b: ByteArray) {
    private var i = 0
    fun hasNext() = i < b.size

    private fun readVarint(): Long? {
        var shift = 0
        var result = 0L
        while (i < b.size) {
            val x = b[i].toInt() and 0xFF
            i++
            result = result or ((x.toLong() and 0x7F) shl shift)
            if (x and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    fun readField(): Field? {
        val key = readVarint() ?: return null
        val number = (key shr 3).toInt()
        return when ((key and 7).toInt()) {
            0 -> Field(number, 0, varint = readVarint() ?: return null)
            2 -> {
                val len = (readVarint() ?: return null).toInt()
                if (len < 0 || i + len > b.size) return null
                val bytes = b.copyOfRange(i, i + len); i += len
                Field(number, 2, bytes = bytes)
            }
            1 -> { if (i + 8 > b.size) return null; i += 8; Field(number, 1) }
            5 -> { if (i + 4 > b.size) return null; i += 4; Field(number, 5) }
            else -> null
        }
    }
}
