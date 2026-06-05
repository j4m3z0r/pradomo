package id.au.james.lymow.control

import id.au.james.lymow.protocol.LymowProtocol
import id.au.james.lymow.protocol.Telemetry
import id.au.james.lymow.protocol.decodeTelemetry
import id.au.james.lymow.transport.MowerTransport
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

/** Scales normalised input before the protocol clamp. v1 ships only NORMAL. */
data class SpeedProfile(val driveScale: Float = 1f, val turnScale: Float = 1f) {
    companion object { val NORMAL = SpeedProfile() }
}

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

    var speedProfile: SpeedProfile = SpeedProfile.NORMAL
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

    /** drive/turn are normalised [-1,1]; scaled by the speed profile then the protocol limits. */
    suspend fun drive(drive: Float, turn: Float) {
        val d = (drive * speedProfile.driveScale).coerceIn(-1f, 1f) * LymowProtocol.DRIVE_LIMIT
        val t = (turn * speedProfile.turnScale).coerceIn(-1f, 1f) * LymowProtocol.TURN_LIMIT
        transport.write(LymowProtocol.toBle(LymowProtocol.encodeJoystick(d, t)))
    }

    suspend fun stop() = drive(0f, 0f)

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
        val t = decodeTelemetry(value)
        _state.update { it.copy(telemetry = t) }
    }
}
