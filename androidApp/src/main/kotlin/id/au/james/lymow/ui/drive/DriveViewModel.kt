package id.au.james.lymow.ui.drive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.au.james.lymow.ble.AndroidMowerTransport
import id.au.james.lymow.control.ConnectionState
import id.au.james.lymow.control.MowerController
import id.au.james.lymow.control.MowerState
import id.au.james.lymow.input.ControlVector
import id.au.james.lymow.input.TouchJoystickSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DriveViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    private val input = TouchJoystickSource()
    private var controller: MowerController? = null

    private val _state = MutableStateFlow(MowerState())
    val state: StateFlow<MowerState> = _state.asStateFlow()

    private var lastSend = 0L
    private val throttleMs = 50L

    fun connect(deviceId: String) {
        if (controller != null) return
        val transport = AndroidMowerTransport(context, deviceId)
        val c = MowerController(transport, scope = viewModelScope)
        controller = c
        viewModelScope.launch { c.state.collect { _state.value = it } }
        viewModelScope.launch { input.vector.collect { v -> sendControl(v) } }
        viewModelScope.launch { runCatching { c.connect() } }
    }

    /** Called by the joystick on drag (drive/turn in [-1,1]) and on release (0,0). */
    fun onJoystick(drive: Float, turn: Float) = input.set(drive, turn)

    private fun sendControl(v: ControlVector) {
        val c = controller ?: return
        if (v.drive == 0f && v.turn == 0f) {            // release / centre -> stop NOW
            lastSend = 0L
            viewModelScope.launch { runCatching { c.stop() } }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSend >= throttleMs) {
            lastSend = now
            viewModelScope.launch { runCatching { c.drive(v.drive, v.turn) } }
        }
    }

    fun emergencyStop() { viewModelScope.launch { runCatching { controller?.stop() } } }

    /** Lifecycle ON_STOP: stop then drop the link. */
    fun onAppBackgrounded() {
        viewModelScope.launch { runCatching { controller?.disconnect() } }
    }

    override fun onCleared() {
        controller?.close()
        controller = null
    }
}
