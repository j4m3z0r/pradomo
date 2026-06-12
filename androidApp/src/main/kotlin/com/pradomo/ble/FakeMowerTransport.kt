package com.pradomo.ble

import com.pradomo.protocol.LymowProtocol
import com.pradomo.transport.MowerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Device id that routes the connection to the simulated mower instead of BLE. */
const val DEMO_DEVICE_ID = "DEMO"

/** Simulated heading drift while moving (rad/s) — see the sim loop comment. */
private const val DRIFT_RAD_PER_S = 0.06f

/**
 * A simulated mower for demo mode. Accepts joystick commands (integrates them into a
 * moving pose) and streams synthetic PbOutput telemetry — status, battery, position,
 * heading, RSSI — so the full connected UI works without a real mower or BLE.
 */
class FakeMowerTransport : MowerTransport {
    private var onValue: ((ByteArray) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loop: Job? = null

    // Simulated state
    private var x = 4.0f
    private var y = 3.0f
    private var heading = 0.7f      // radians
    private var battery = 88
    private var linear = 0f         // last commanded linear speed (m/s-ish)
    private var angular = 0f        // last commanded angular speed
    private var ticks = 0

    override suspend fun connect() { /* always succeeds */ }

    override fun onDisconnect(callback: () -> Unit) { /* sim never drops */ }

    override suspend fun startNotify(onValue: (ByteArray) -> Unit) {
        this.onValue = onValue
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                delay(120)
                val dt = 0.12f
                heading += angular * dt
                // Like real grass: the mower drifts while translating (slope/tread slip),
                // so it does NOT track straight on constant commands. This makes cruise
                // correction, heading hold, and the K-turn trim phase visible in demo.
                if (abs(linear) > 0.01f) heading += DRIFT_RAD_PER_S * dt
                x += linear * cos(heading) * dt
                y += linear * sin(heading) * dt
                ticks++
                if (ticks % 250 == 0 && battery > 1) battery--   // ~½%/min, just for life
                val rssi = -50 - (ticks % 12)
                onValue.invoke(LymowProtocol.toBle(simTelemetry(battery, x, y, heading, rssi)))
            }
        }
    }

    override suspend fun stopNotify() {}

    override suspend fun write(value: ByteArray) {
        val raw = LymowProtocol.fromBle(value)
        // Joystick frame: 16 bytes, header 10313802520a0d (f10 tag 0x52 at index 4),
        // linear float at offset 7, 0x15 tag at 11, angular float at 12.
        if (raw.size == 16 && raw[0] == 0x10.toByte() && raw[4] == 0x52.toByte()) {
            linear = floatLe(raw, 7)
            angular = floatLe(raw, 12)
        }
        // init / keepalive / deck-blade frames: ignored by the sim.
    }

    override fun close() {
        loop?.cancel()
        loop = null
        onValue = null
        scope.cancel()
    }
}

private fun floatLe(b: ByteArray, off: Int): Float {
    val bits = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    return Float.fromBits(bits)
}

private fun encodeFloatLe(v: Float): ByteArray {
    val bits = v.toRawBits()
    return byteArrayOf(
        (bits and 0xFF).toByte(), ((bits ushr 8) and 0xFF).toByte(),
        ((bits ushr 16) and 0xFF).toByte(), ((bits ushr 24) and 0xFF).toByte(),
    )
}

/** Build a PbOutput: f5{f1 status=6, f2 battery}, f14{f1 x, f2 y, f3 heading}, f22{f6 rssi-string}. */
private fun simTelemetry(battery: Int, x: Float, y: Float, heading: Float, rssi: Int): ByteArray {
    val f5 = byteArrayOf(0x2a, 0x04, 0x08, 0x06, 0x10, battery.toByte())
    val f14 = byteArrayOf(0x72, 0x0f, 0x0d) + encodeFloatLe(x) +
        byteArrayOf(0x15) + encodeFloatLe(y) + byteArrayOf(0x1d) + encodeFloatLe(heading)
    val s = rssi.toString().encodeToByteArray()
    val inner = byteArrayOf(0x32, s.size.toByte()) + s
    val f22 = byteArrayOf(0xb2.toByte(), 0x01, inner.size.toByte()) + inner
    return f5 + f14 + f22
}
