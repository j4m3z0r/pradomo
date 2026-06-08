package com.pradomo.ui.drive

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pradomo.ble.AndroidMowerTransport
import com.pradomo.ble.DEMO_DEVICE_ID
import com.pradomo.ble.FakeMowerTransport
import com.pradomo.control.ButtonAction
import com.pradomo.control.MowerController
import com.pradomo.control.MowerState
import com.pradomo.control.SmoothLevel
import com.pradomo.control.SpeedMode
import com.pradomo.control.SpeedSmoother
import com.pradomo.data.MapRepository
import com.pradomo.data.SettingsStore
import com.pradomo.input.ButtonEvent
import com.pradomo.input.ControlVector
import com.pradomo.input.TouchJoystickSource
import com.pradomo.input.UsbGamepadSource
import com.pradomo.map.MapSample
import com.pradomo.protocol.BladeSpeed
import com.pradomo.protocol.DeckHeights
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DriveViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    private val input = TouchJoystickSource()
    private var controller: MowerController? = null

    private val _state = MutableStateFlow(MowerState())
    val state: StateFlow<MowerState> = _state.asStateFlow()

    /** Latest commanded control vector (touch OR gamepad), for the readout and the resend loop. */
    private val _control = MutableStateFlow(ControlVector.ZERO)
    val control: StateFlow<ControlVector> = _control.asStateFlow()

    /** Cutting-deck height (mm) + blade speed. Latched by the device, sent once per change. */
    private val _deck = MutableStateFlow(DeckBladeUi())
    val deck: StateFlow<DeckBladeUi> = _deck.asStateFlow()

    /** Effective drive speed mode (base selection, or a held-button override). */
    private val _speedMode = MutableStateFlow(SpeedMode.NORMAL)
    val speedMode: StateFlow<SpeedMode> = _speedMode.asStateFlow()

    /** Driven path (map frame), accumulated from telemetry; persisted per mower (Epic 3). */
    private val _trail = MutableStateFlow<List<MapSample>>(emptyList())
    val trail: StateFlow<List<MapSample>> = _trail.asStateFlow()
    private val mapRepo = MapRepository(app)
    private var mowerId = DEMO_DEVICE_ID // persistence key (BLE address; "DEMO" for the sim)
    private var lastCmdLinear = 0f
    private var lastCmdAngular = 0f

    // Base mode (from the on-screen chips) vs momentary overrides from held remote buttons.
    private var baseSpeedMode = SpeedMode.NORMAL
    private val heldButtons = mutableSetOf<Int>()

    private val settings = SettingsStore(app)

    /** Persisted, user-configurable controller-button actions (held = momentary speed). */
    private val _topButton = MutableStateFlow(ButtonAction.SLOW)
    val topButton: StateFlow<ButtonAction> = _topButton.asStateFlow()
    private val _bottomButton = MutableStateFlow(ButtonAction.TURBO)
    val bottomButton: StateFlow<ButtonAction> = _bottomButton.asStateFlow()

    /** Controlled deceleration: ease the sent drive/turn toward the target. */
    private val _smoothEnabled = MutableStateFlow(false)
    val smoothEnabled: StateFlow<Boolean> = _smoothEnabled.asStateFlow()
    private val _smoothLevel = MutableStateFlow(SmoothLevel.MEDIUM)
    val smoothLevel: StateFlow<SmoothLevel> = _smoothLevel.asStateFlow()
    private val smoother = SpeedSmoother(SmoothLevel.MEDIUM.ratePerSec)
    private var lastSentNonzero = false

    private var driveLoop: Job? = null

    init {
        // Load persisted preferences; deck height shows the last value instead of 50mm.
        viewModelScope.launch {
            _deck.value = _deck.value.copy(heightMm = settings.deckHeightMm.first())
            _topButton.value = settings.topButton.first()
            _bottomButton.value = settings.bottomButton.first()
            _smoothEnabled.value = settings.smoothEnabled.first()
            _smoothLevel.value = settings.smoothLevel.first()
            smoother.ratePerSec = _smoothLevel.value.ratePerSec
        }
    }

    fun connect(deviceId: String) {
        if (controller != null) return
        val transport = if (deviceId == DEMO_DEVICE_ID) FakeMowerTransport()
            else AndroidMowerTransport(context, deviceId)
        val c = MowerController(transport, scope = viewModelScope)
        c.maxLinear = _speedMode.value.maxLinear
        controller = c
        smoother.reset()
        lastSentNonzero = false
        // Load this mower's saved track (its own bucket — demo's "DEMO" key stays
        // separate from any real mower's map).
        mowerId = deviceId
        _trail.value = emptyList()
        viewModelScope.launch { runCatching { _trail.value = mapRepo.track(mowerId) } }
        viewModelScope.launch { c.state.collect { _state.value = it; recordTrail(it) } }
        // Both input sources feed the same sink; last input wins.
        viewModelScope.launch { input.vector.collect { v -> onControl(v) } }
        viewModelScope.launch { UsbGamepadSource.vector.collect { v -> onControl(v) } }
        viewModelScope.launch { UsbGamepadSource.buttons.collect { onButton(it) } }
        // Continuously re-send the current command so a *steady* stick keeps the
        // mower moving — the mower stops on its own if joystick frames stop
        // arriving, and Android only emits input events on change. With controlled
        // deceleration on, we ramp the sent values toward the target each tick (so
        // release coasts to a stop); otherwise we send the raw command and stop
        // immediately on release (see onControl).
        driveLoop = viewModelScope.launch {
            val dt = RESEND_MS / 1000f
            while (isActive) {
                val target = _control.value
                // Effective drive/turn to send: smoothed toward target, or raw.
                val (dr, tu) = if (_smoothEnabled.value) {
                    smoother.step(target.drive, target.turn, dt)
                } else {
                    smoother.reset(target.drive, target.turn) // keep synced for seamless enable
                    target.drive to target.turn
                }
                if (dr != 0f || tu != 0f) {
                    runCatching { controller?.drive(dr, tu) }
                    lastSentNonzero = true
                } else if (lastSentNonzero) {
                    runCatching { controller?.stop() } // reached zero
                    lastSentNonzero = false
                }
                // Remember what we commanded, for the map's traction layer (Epic 3.3).
                lastCmdLinear = dr * _speedMode.value.maxLinear
                lastCmdAngular = tu * TURN_LIMIT
                delay(RESEND_MS)
            }
        }
        viewModelScope.launch { runCatching { c.connect() } }
    }

    /** Append a path point when the mower has moved enough, and persist it (Epic 3). */
    private fun recordTrail(s: MowerState) {
        val pos = s.telemetry?.position ?: return
        val sample = MapSample(
            tMillis = System.currentTimeMillis(),
            x = pos.first, y = pos.second, heading = s.telemetry?.heading ?: 0f,
            bladeOn = _deck.value.blade != BladeSpeed.OFF,
            cmdLinear = lastCmdLinear, cmdAngular = lastCmdAngular,
        )
        val cur = _trail.value
        val last = cur.lastOrNull()
        if (last != null && hypot(sample.x - last.x, sample.y - last.y) < TRAIL_MIN_MOVE) return
        _trail.value = (cur + sample).takeLast(TRAIL_MAX)
        persist { mapRepo.append(mowerId, sample) }
    }

    /** Wipe the current mower's saved map (and the on-screen path). */
    fun clearMap() {
        _trail.value = emptyList()
        persist { mapRepo.clear(mowerId) }
    }

    /** Touch joystick callback (drive/turn in [-1,1]); 0,0 on release. */
    fun onJoystick(drive: Float, turn: Float) = input.set(drive, turn)

    private fun onControl(v: ControlVector) {
        _control.value = v
        // With controlled deceleration on, release coasts to a stop via the loop;
        // otherwise stop immediately.
        if (v.drive == 0f && v.turn == 0f && !_smoothEnabled.value) {
            viewModelScope.launch { runCatching { controller?.stop() } }
        }
    }

    /** On-screen speed selector: sets the base mode (held buttons still override). */
    fun setSpeedMode(mode: SpeedMode) {
        baseSpeedMode = mode
        applyEffectiveSpeed()
    }

    /** Momentary remote-button speed override (held). */
    private fun onButton(e: ButtonEvent) {
        if (e.pressed) heldButtons.add(e.buttonId) else heldButtons.remove(e.buttonId)
        applyEffectiveSpeed()
    }

    private fun actionFor(keycode: Int): ButtonAction = when (keycode) {
        SettingsStore.KEYCODE_TOP -> _topButton.value
        SettingsStore.KEYCODE_BOTTOM -> _bottomButton.value
        else -> ButtonAction.NONE
    }

    private fun applyEffectiveSpeed() {
        val held = heldButtons.map { actionFor(it) }
        val override = when {
            held.any { it == ButtonAction.TURBO } -> SpeedMode.TURBO
            held.any { it == ButtonAction.SLOW } -> SpeedMode.SLOW
            else -> null
        }
        val effective = override ?: baseSpeedMode
        _speedMode.value = effective
        controller?.maxLinear = effective.maxLinear
    }

    fun setTopButton(a: ButtonAction) {
        _topButton.value = a
        persist { settings.setTopButton(a) }
        applyEffectiveSpeed()
    }

    fun setBottomButton(a: ButtonAction) {
        _bottomButton.value = a
        persist { settings.setBottomButton(a) }
        applyEffectiveSpeed()
    }

    fun setSmoothEnabled(on: Boolean) {
        _smoothEnabled.value = on
        if (!on) smoother.reset(_control.value.drive, _control.value.turn)
        persist { settings.setSmoothEnabled(on) }
    }

    fun setSmoothLevel(level: SmoothLevel) {
        _smoothLevel.value = level
        smoother.ratePerSec = level.ratePerSec
        persist { settings.setSmoothLevel(level) }
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

    /** Blade-speed selector. */
    fun setBlade(speed: BladeSpeed) {
        _deck.value = _deck.value.copy(blade = speed)
        pushDeckBlade()
    }

    /** Deck-height selector (displayed mm). Persisted so it survives restarts. */
    fun setHeightMm(mm: Int) {
        _deck.value = _deck.value.copy(heightMm = mm)
        pushDeckBlade()
        persist { settings.setDeckHeightMm(mm) }
    }

    private fun pushDeckBlade() {
        val d = _deck.value
        val enc = DeckHeights.encoded(d.heightMm)
        viewModelScope.launch { runCatching { controller?.setDeckBlade(d.blade.code, enc) } }
    }

    /** Force the blade off and send it (used by E-stop and every exit path). */
    private fun bladeOff() {
        if (_deck.value.blade != BladeSpeed.OFF) _deck.value = _deck.value.copy(blade = BladeSpeed.OFF)
    }

    fun emergencyStop() {
        _control.value = ControlVector.ZERO
        smoother.reset() // bypass controlled deceleration — E-STOP is always instant
        lastSentNonzero = false
        bladeOff()
        val enc = DeckHeights.encoded(_deck.value.heightMm)
        viewModelScope.launch {
            runCatching { controller?.stop() }
            runCatching { controller?.setDeckBlade(0, enc) }
        }
    }

    /** Lifecycle ON_STOP / leaving the screen: blade off, stop, drop the link. */
    fun onAppBackgrounded() {
        _control.value = ControlVector.ZERO
        smoother.reset() // instant stop on the way out
        lastSentNonzero = false
        bladeOff()
        val enc = DeckHeights.encoded(_deck.value.heightMm)
        viewModelScope.launch {
            runCatching { controller?.setDeckBlade(0, enc) }
            runCatching { controller?.disconnect() }
        }
    }

    override fun onCleared() {
        controller?.close()
        controller = null
    }

    private companion object {
        const val TAG = "PradomoDrive"
        const val RESEND_MS = 80L
        const val TRAIL_MIN_MOVE = 0.05f // metres between recorded path points
        const val TRAIL_MAX = 4000       // cap the in-memory path
        const val TURN_LIMIT = 0.6f      // matches the protocol's angular clamp
    }
}

/** Deck-height (mm) + blade speed UI state. */
data class DeckBladeUi(
    val blade: BladeSpeed = BladeSpeed.OFF,
    val heightMm: Int = 50,
)
