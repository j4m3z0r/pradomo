package com.pradomo.control

import com.pradomo.protocol.LymowProtocol
import com.pradomo.protocol.Telemetry
import com.pradomo.protocol.decodeTelemetry
import com.pradomo.transport.MowerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConnectionState { Disconnected, Connecting, Connected, Error }

data class MowerState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val telemetry: Telemetry? = null,
)

/**
 * Owns the protocol session over a [MowerTransport]: init handshake, 3s
 * keepalive, normalised drive, and stop-on-every-exit. Mirrors mower.py.
 */
class MowerController(
    private val transport: MowerTransport,
    private val scope: CoroutineScope,
    private val clientId: String = LymowProtocol.makeClientId(),
    private val keepaliveIntervalMs: Long = 3000L,
) {
    private val _state = MutableStateFlow(MowerState())
    val state: StateFlow<MowerState> = _state.asStateFlow()

    /** Full-stick linear speed; set from the current [SpeedMode]. Default = NORMAL (0.5). */
    var maxLinear: Float = LymowProtocol.DRIVE_LIMIT
    private var keepaliveJob: Job? = null

    suspend fun connect() {
        _state.update { it.copy(connection = ConnectionState.Connecting) }
        try {
            transport.connect()
            transport.startNotify(::onNotify)
            transport.onDisconnect { onConnectionLost() }
            for (frame in LymowProtocol.INIT_FRAMES) transport.write(LymowProtocol.toBle(frame))
            startKeepalive()
            _state.update { it.copy(connection = ConnectionState.Connected) }
        } catch (e: Exception) {
            _state.update { it.copy(connection = ConnectionState.Error) }
            throw e
        }
    }

    /** drive/turn are normalised [-1,1]; drive scales to [maxLinear], turn to the protocol limit. */
    suspend fun drive(drive: Float, turn: Float) {
        val d = drive.coerceIn(-1f, 1f) * maxLinear
        val t = turn.coerceIn(-1f, 1f) * LymowProtocol.TURN_LIMIT
        transport.write(LymowProtocol.toBle(LymowProtocol.encodeJoystick(d, t)))
    }

    suspend fun stop() = drive(0f, 0f)

    /** Set cutting-deck height + blade speed (latched by the device; send once per change). */
    suspend fun setDeckBlade(bladeSpeed: Int, cutHeight: Int) {
        transport.write(LymowProtocol.toBle(LymowProtocol.encodeDeckBlade(bladeSpeed, cutHeight)))
    }

    suspend fun disconnect() {
        keepaliveJob?.cancelAndJoin()
        keepaliveJob = null
        runCatching { stop() }
        runCatching { transport.stopNotify() }
        transport.close()
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    /** Synchronous teardown for ViewModel.onCleared and other non-suspending callers. */
    fun close() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        transport.close()
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    private fun startKeepalive() {
        val frame = LymowProtocol.toBle(LymowProtocol.keepaliveFrame(clientId))
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(keepaliveIntervalMs)
                runCatching { transport.write(frame) }
            }
        }
    }

    private fun onConnectionLost() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        transport.close()
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    private fun onNotify(value: ByteArray) {
        val new = decodeTelemetry(value)
        _state.update { st ->
            // Different notifications carry different field subsets — keep last-known per field.
            val old = st.telemetry
            st.copy(
                telemetry = Telemetry(
                    robotStatus = new.robotStatus ?: old?.robotStatus,
                    battery = new.battery ?: old?.battery,
                    position = new.position ?: old?.position,
                    heading = new.heading ?: old?.heading,
                    rssi = new.rssi ?: old?.rssi,
                ),
            )
        }
    }
}
