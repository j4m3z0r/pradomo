package com.pradomo.control

import com.pradomo.protocol.LymowProtocol
import com.pradomo.protocol.RobotStatus
import com.pradomo.transport.MowerTransport
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
    /** Fail every Nth write (0 = reliable) — models GATT-busy drops on real BLE. */
    var failEveryNthWrite = 0
    private var writeAttempts = 0
    private var cb: ((ByteArray) -> Unit)? = null
    private var disconnectCb: (() -> Unit)? = null
    override suspend fun connect() { connected = true }
    var closed = false
    override fun close() { connected = false; closed = true }
    override suspend fun startNotify(onValue: (ByteArray) -> Unit) { notifying = true; cb = onValue }
    override suspend fun stopNotify() { notifying = false }
    override suspend fun write(value: ByteArray) {
        writeAttempts++
        if (failEveryNthWrite > 0 && writeAttempts % failEveryNthWrite == 0) error("gatt busy")
        writes.add(value)
    }
    override fun onDisconnect(callback: () -> Unit) { disconnectCb = callback }
    fun feed(value: ByteArray) = cb?.invoke(value)
    fun loseConnection() { connected = false; disconnectCb?.invoke() }
    fun framesHex() = writes.map { LymowProtocol.fromBle(it).hex() }
}

private const val STOP_FRAME = "10313802520a0d000000001500000000"

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

    @Test fun lost_connection_updates_state_to_disconnected() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        t.loseConnection()
        assertEquals(ConnectionState.Disconnected, c.state.value.connection)
    }

    @Test fun lost_connection_closes_transport() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        t.loseConnection()
        assertEquals(ConnectionState.Disconnected, c.state.value.connection)
        assertTrue(t.closed)
    }

    // ---- E-STOP: these protect the safety-critical path; they run on every app build. --

    @Test fun emergency_stop_sends_a_burst_of_stop_and_blade_off_frames() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        t.writes.clear()
        c.emergencyStop(cutHeight = 96)
        val frames = t.framesHex()
        val stops = frames.count { it == STOP_FRAME }
        val bladeOff = frames.count { it == LymowProtocol.encodeDeckBlade(0, 96).hex() }
        assertTrue(stops >= 10, "expected a burst of stop frames, got $stops")
        assertTrue(bladeOff >= 2, "expected repeated blade-off frames, got $bladeOff")
    }

    @Test fun emergency_stop_survives_a_flaky_transport() = runTest {
        // Real BLE drops writes when the GATT is busy. Even with every 3rd write failing,
        // the burst must keep going and still land plenty of stop frames + a blade-off.
        val t = FakeTransport().apply { failEveryNthWrite = 3 }
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        runCatching { c.connect() } // init frames may "fail" too — irrelevant here
        t.writes.clear()
        c.emergencyStop(cutHeight = 96) // must not throw
        val frames = t.framesHex()
        assertTrue(frames.count { it == STOP_FRAME } >= 7,
            "burst should ride out dropped writes, got ${frames.count { it == STOP_FRAME }} stops")
        assertTrue(frames.any { it == LymowProtocol.encodeDeckBlade(0, 96).hex() },
            "at least one blade-off must get through")
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
