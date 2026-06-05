package id.au.james.lymow.protocol

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class TelemetryTest {
    private val CHARGING =
        "KiIIBRBLGMT//////////wEgzf//////////ATABQAFIAVABMhAIChVNFQw8Hb4wmTwg" +
        "AygBSgQwAFgBYgoVjVu7Qy0AAAAAcg8NguyzwBV6qNfAHVrGvD+yAQUyAy01N4ICAggB"
    private val REMOTE =
        "KiAIBhBLGMP//////////wEgzf//////////ATABSAFQATIQCAoV4ukVPB28dJM8IAMoA0o" +
        "EMABYAWIKFY1bu0MtAAAAAHIPDXpqt8AVkETzwB22cLE/ggICCAE="
    private val NET = "sgEgCgdzdWNjZXNzEg4xOTIuMTY4LjEzNi42MBoFMTAwMDI="

    @Test fun decode_charging() {
        val t = decodeTelemetry(CHARGING.encodeToByteArray())
        assertEquals(RobotStatus.CHARGING, t.robotStatus)
        assertEquals("charging", t.statusName)
        assertEquals(75, t.battery)
    }

    @Test fun decode_remote_control() {
        val t = decodeTelemetry(REMOTE.encodeToByteArray())
        assertEquals(RobotStatus.REMOTE_CONTROL, t.robotStatus)
        assertEquals("remote_control", t.statusName)
        assertEquals(75, t.battery)
    }

    @Test fun decode_accepts_raw_bytes() {
        val raw = Base64.decode(CHARGING)
        assertEquals(75, decodeTelemetry(raw).battery)
    }

    @Test fun network_frame_has_no_status() {
        val t = decodeTelemetry(NET.encodeToByteArray())
        assertNull(t.robotStatus)
        assertNull(t.battery)
    }

    @Test fun garbage_is_empty_not_exception() {
        val t = decodeTelemetry(byteArrayOf(0x00, 0x01, 0x02))
        assertNull(t.robotStatus)
        assertNull(t.battery)
    }

    @Test fun unknown_status_name() = assertEquals("unknown_99", RobotStatus.name(99))
}
