package id.au.james.lymow.ui.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.au.james.lymow.ble.AndroidMowerScanner
import id.au.james.lymow.transport.DiscoveredDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConnectUiState(
    val scanning: Boolean = false,
    val devices: List<DiscoveredDevice> = emptyList(),
)

class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = AndroidMowerScanner(app)
    private val _ui = MutableStateFlow(ConnectUiState())
    val ui: StateFlow<ConnectUiState> = _ui.asStateFlow()
    private var scanJob: Job? = null

    fun toggleScan() = if (_ui.value.scanning) stopScan() else startScan()

    fun startScan() {
        _ui.value = _ui.value.copy(scanning = true, devices = emptyList())
        scanJob = viewModelScope.launch {
            scanner.scan().collect { list -> _ui.value = _ui.value.copy(devices = list) }
        }
    }

    fun stopScan() {
        scanJob?.cancel(); scanJob = null
        _ui.value = _ui.value.copy(scanning = false)
    }

    override fun onCleared() { stopScan() }
}
