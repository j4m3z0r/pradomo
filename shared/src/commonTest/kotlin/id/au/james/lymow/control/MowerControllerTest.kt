package id.au.james.lymow.control

import id.au.james.lymow.protocol.LymowProtocol
import id.au.james.lymow.protocol.RobotStatus
import id.au.james.lymow.transport.MowerTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private class FakeTransport : MowerTransport {
    val writes = mutableListOf<ByteArray>()
    var connected = false
    var notifying = false
    private var cb: ((ByteArray) -> Unit)? = null
    override suspend fun connect() { connected = true }
    override suspend fun disconnect() { connected = false }
    override suspend fun startNotify(onValue: (ByteArray) -> Unit) { notifying = true; cb = onValue }
    override suspend fun stopNotify() { notifying = false }
    override suspend fun write(value: ByteArray) { writes.add(value) }
    fun feed(value: ByteArray) = cb?.invoke(value)
    fun framesHex() = writes.map { LymowProtocol.fromBle(it).hex() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MowerControllerTest {

    @Test fun connect_sends_init_frames_then_starts_keepalive() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        assertTrue(t.notifying)
        assertEquals(LymowProtocol.INIT_FRAMES.map { it.hex() }, t.framesHex().take(5))
        assertEquals(ConnectionState.Connected, c.state.value.connection)
    }

    @Test fun drive_normalised_maps_to_clamped_payloads() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        t.writes.clear()
        c.drive(1f, 0f)
        c.drive(-1f, 0f)
        c.drive(0f, -1f)
        c.stop()
        assertEquals(
            listOf(
                "10313802520a0d0000003f1500000000",
                "10313802520a0d000000bf1500000000",
                "10313802520a0d00000000159a9919bf",
                "10313802520a0d000000001500000000",
            ),
            t.framesHex(),
        )
    }

    @Test fun keepalive_fires_after_interval() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", keepaliveIntervalMs = 3000, scope = backgroundScope)
        c.connect()
        t.writes.clear()
        advanceTimeBy(3100)
        assertTrue(LymowProtocol.keepaliveFrame("abc").hex() in t.framesHex())
    }

    @Test fun disconnect_sends_stop_then_disconnects() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        t.writes.clear()
        c.disconnect()
        assertTrue("10313802520a0d000000001500000000" in t.framesHex())
        assertFalse(t.connected)
        assertEquals(ConnectionState.Disconnected, c.state.value.connection)
    }

    @Test fun notification_updates_state() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        val charging =
            ("KiIIBRBLGMT//////////wEgzf//////////ATABQAFIAVABMhAIChVNFQw8Hb4wmTwg" +
             "AygBSgQwAFgBYgoVjVu7Qy0AAAAAcg8NguyzwBV6qNfAHVrGvD+yAQUyAy01N4ICAggB")
        t.feed(charging.encodeToByteArray())
        assertEquals(RobotStatus.CHARGING, c.state.value.telemetry?.robotStatus)
        assertEquals(75, c.state.value.telemetry?.battery)
    }
}
