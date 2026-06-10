package com.pradomo.ui.drive

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pradomo.ble.AndroidMowerTransport
import com.pradomo.ble.DEMO_DEVICE_ID
import com.pradomo.ble.FakeMowerTransport
import com.pradomo.control.ButtonAction
import com.pradomo.control.ConnectionState
import com.pradomo.control.ControllerModeGroup
import com.pradomo.control.MowerController
import com.pradomo.control.MowerState
import com.pradomo.control.SmoothLevel
import com.pradomo.control.SpeedMode
import com.pradomo.control.SpeedSmoother
import com.pradomo.control.maneuver.CruiseManeuver
import com.pradomo.control.maneuver.Maneuver
import com.pradomo.control.maneuver.ManeuverCommand
import com.pradomo.control.maneuver.MultiPointTurn
import com.pradomo.control.maneuver.Pose
import com.pradomo.control.maneuver.TurnDirection
import com.pradomo.data.MapRepository
import com.pradomo.data.SettingsStore
import com.pradomo.input.ButtonEvent
import com.pradomo.input.ControlVector
import com.pradomo.input.TouchJoystickSource
import com.pradomo.input.UsbGamepadSource
import com.pradomo.map.MapSample
import com.pradomo.protocol.BladeSpeed
import com.pradomo.protocol.DeckHeights
import kotlin.math.abs
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

    // Controller mode group (what the buttons do) + the both-buttons mode-select gesture.
    private val _modeGroup = MutableStateFlow(ControllerModeGroup.SPEED)
    val modeGroup: StateFlow<ControllerModeGroup> = _modeGroup.asStateFlow()
    private val _modeSelecting = MutableStateFlow(false)
    val modeSelecting: StateFlow<Boolean> = _modeSelecting.asStateFlow()
    private var modeSelectArmed = true              // debounce: ready for the next L/R cycle
    private var autoTriggerArmed = true             // a maneuver needs a fresh big-button press

    // Semi-autonomous maneuver currently driving the mower (Auto group), if any.
    private var activeManeuver: Maneuver? = null
    private var maneuverArmed = false               // once true, a fresh stick touch cancels
    private val _maneuverLabel = MutableStateFlow<String?>(null)
    val maneuverLabel: StateFlow<String?> = _maneuverLabel.asStateFlow()

    // Mower geometry for placing auto-turns one row over (pitch = cutting width − overlap).
    private val _cuttingWidthMm = MutableStateFlow(SettingsStore.DEFAULT_CUTTING_WIDTH_MM)
    val cuttingWidthMm: StateFlow<Int> = _cuttingWidthMm.asStateFlow()
    private val _rowOverlapMm = MutableStateFlow(SettingsStore.DEFAULT_ROW_OVERLAP_MM)
    val rowOverlapMm: StateFlow<Int> = _rowOverlapMm.asStateFlow()

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
            _cuttingWidthMm.value = settings.cuttingWidthMm.first()
            _rowOverlapMm.value = settings.rowOverlapMm.first()
            _modeGroup.value = settings.modeGroup.first()
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
        viewModelScope.launch {
            c.state.collect {
                _state.value = it
                recordTrail(it)
                // Link dropped / out of range: abort any maneuver (we can no longer steer).
                if (it.connection == ConnectionState.Disconnected) cancelManeuver()
            }
        }
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
                when {
                    // Picking a mode group: hold still so the stick only cycles modes.
                    _modeSelecting.value -> {
                        if (lastSentNonzero) { runCatching { controller?.stop() }; lastSentNonzero = false }
                        smoother.reset()
                    }
                    // A maneuver is driving: command comes from telemetry-closed-loop, not the stick.
                    activeManeuver != null -> stepManeuver(dt)
                    // Normal manual driving (smoothed toward the stick, or raw).
                    else -> {
                        val target = _control.value
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
                    }
                }
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
        val mag = hypot(v.drive, v.turn)

        // Both buttons held: the stick cycles the mode group instead of driving.
        if (_modeSelecting.value) {
            if (modeSelectArmed && abs(v.turn) >= MODE_CYCLE_THRESHOLD && abs(v.turn) > abs(v.drive)) {
                _modeGroup.value = if (v.turn < 0f) _modeGroup.value.next() else _modeGroup.value.prev()
                modeSelectArmed = false
            } else if (mag < STICK_RECENTER) {
                modeSelectArmed = true
            }
            return
        }

        // A maneuver is running: ignore the stick until it recenters, then let any fresh
        // deflection cancel it (the user grabbing back control).
        if (activeManeuver != null) {
            if (!maneuverArmed) {
                if (mag < STICK_RECENTER) maneuverArmed = true
                return
            }
            if (mag >= STICK_DEADZONE) {
                cancelManeuver() // fall through and treat this push as a normal command
            } else {
                return
            }
        }

        // Auto group: a deliberate push while the big button is held starts a maneuver.
        maybeStartAuto()
        if (activeManeuver != null) return

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

    /**
     * Controller buttons. Holding *both* enters mode-select (stick cycles the group).
     * In the Speed group a single held button is a momentary speed override; in the Auto
     * group the big button arms a maneuver (started by a stick push in [maybeStartAuto]).
     */
    private fun onButton(e: ButtonEvent) {
        if (e.pressed) heldButtons.add(e.buttonId) else heldButtons.remove(e.buttonId)
        val top = SettingsStore.KEYCODE_TOP in heldButtons
        val bottom = SettingsStore.KEYCODE_BOTTOM in heldButtons
        val bothHeld = top && bottom

        if (bothHeld && !_modeSelecting.value) {
            _modeSelecting.value = true
            modeSelectArmed = true
            cancelManeuver() // dropping out of normal control
        } else if (!bothHeld && _modeSelecting.value) {
            _modeSelecting.value = false
            persist { settings.setModeGroup(_modeGroup.value) }
        }
        // Re-arm the Auto trigger when the big button is released (one maneuver per press).
        if (!bottom) autoTriggerArmed = true

        applyEffectiveSpeed()
        // Pressing the big button while the stick is already deflected can start a maneuver.
        maybeStartAuto()
    }

    private fun actionFor(keycode: Int): ButtonAction = when (keycode) {
        SettingsStore.KEYCODE_TOP -> _topButton.value
        SettingsStore.KEYCODE_BOTTOM -> _bottomButton.value
        else -> ButtonAction.NONE
    }

    private fun applyEffectiveSpeed() {
        // Held-button speed overrides only apply in the Speed group (not while selecting
        // a mode, not in the Auto group where the buttons do maneuvers).
        val override = if (_modeGroup.value == ControllerModeGroup.SPEED && !_modeSelecting.value) {
            val held = heldButtons.map { actionFor(it) }
            when {
                held.any { it == ButtonAction.TURBO } -> SpeedMode.TURBO
                held.any { it == ButtonAction.SLOW } -> SpeedMode.SLOW
                else -> null
            }
        } else {
            null
        }
        val effective = override ?: baseSpeedMode
        _speedMode.value = effective
        controller?.maxLinear = effective.maxLinear
    }

    // ---- Semi-autonomous maneuvers (Auto group) ----------------------------------------

    /** Row pitch in metres for auto-turns: cutting width − overlap, floored to a sane min. */
    private fun rowPitchMetres(): Float {
        val mm = (_cuttingWidthMm.value - _rowOverlapMm.value).coerceAtLeast(MIN_PITCH_MM)
        return mm / 1000f
    }

    /**
     * In the Auto group, with the big button held (and not the both-button chord), a
     * deliberate stick push starts a maneuver: left/right → K-turn into the adjacent row,
     * forward/back → cruise control. One maneuver per big-button press.
     */
    private fun maybeStartAuto() {
        if (_modeGroup.value != ControllerModeGroup.AUTO) return
        if (_modeSelecting.value || activeManeuver != null || !autoTriggerArmed) return
        val bigHeld = SettingsStore.KEYCODE_BOTTOM in heldButtons
        val topHeld = SettingsStore.KEYCODE_TOP in heldButtons
        if (!bigHeld || topHeld) return
        val v = _control.value
        if (hypot(v.drive, v.turn) < AUTO_TRIGGER_DEADZONE) return

        val maneuver = if (abs(v.turn) >= abs(v.drive)) {
            val dir = if (v.turn > 0f) TurnDirection.LEFT else TurnDirection.RIGHT
            _maneuverLabel.value = "K-turn ${if (dir == TurnDirection.LEFT) "left" else "right"}"
            MultiPointTurn(
                dir,
                rowPitchMetres(),
                MultiPointTurn.Params(
                    turnScale = TURN_LIMIT,
                    linearScale = (controller?.maxLinear ?: SpeedMode.NORMAL.maxLinear),
                ),
            )
        } else {
            _maneuverLabel.value = if (v.drive > 0f) "Cruise forward" else "Cruise back"
            CruiseManeuver(if (v.drive > 0f) CRUISE_DRIVE else -CRUISE_DRIVE)
        }
        startManeuver(maneuver)
    }

    private fun startManeuver(m: Maneuver) {
        activeManeuver = m
        maneuverArmed = false
        autoTriggerArmed = false
        smoother.reset()
    }

    /** Stop and clear the active maneuver. Safe to call when none is running. */
    private fun cancelManeuver() {
        if (activeManeuver == null) return
        activeManeuver = null
        maneuverArmed = false
        _maneuverLabel.value = null
        smoother.reset()
        lastSentNonzero = false
        viewModelScope.launch { runCatching { controller?.stop() } }
    }

    /** Synchronously drop any maneuver / mode-select (E-STOP and backgrounding paths). */
    private fun clearAutoState() {
        activeManeuver = null
        maneuverArmed = false
        _modeSelecting.value = false
        _maneuverLabel.value = null
    }

    /** One control tick while a maneuver is driving: feed it telemetry, send its command. */
    private suspend fun stepManeuver(dt: Float) {
        val m = activeManeuver ?: return
        val tel = _state.value.telemetry
        val pos = tel?.position
        if (pos == null) { cancelManeuver(); return } // no pose → can't close the loop safely
        val cmd = m.step(Pose(pos.first, pos.second, tel.heading ?: 0f), dt)
        if (cmd.done) { cancelManeuver(); return }
        runCatching { controller?.drive(cmd.drive, cmd.turn) }
        lastSentNonzero = true
        smoother.reset(cmd.drive, cmd.turn) // keep the smoother synced for a clean handoff
        lastCmdLinear = cmd.drive * _speedMode.value.maxLinear
        lastCmdAngular = cmd.turn * TURN_LIMIT
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

    /** Mower cutting width (mm), used with overlap to place auto-turns one row over. */
    fun setCuttingWidthMm(mm: Int) {
        _cuttingWidthMm.value = mm
        persist { settings.setCuttingWidthMm(mm) }
    }

    /** Row overlap (mm); row pitch = cutting width − overlap. */
    fun setRowOverlapMm(mm: Int) {
        _rowOverlapMm.value = mm
        persist { settings.setRowOverlapMm(mm) }
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
        clearAutoState() // any maneuver / mode-select drops instantly too
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
        clearAutoState() // instant stop on the way out
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
        const val MIN_PITCH_MM = 20      // floor for cutting width − overlap
        const val STICK_DEADZONE = 0.2f  // fresh deflection that cancels a maneuver
        const val STICK_RECENTER = 0.15f // stick treated as centered (re-arming)
        const val MODE_CYCLE_THRESHOLD = 0.6f // L/R push to step the mode group
        const val AUTO_TRIGGER_DEADZONE = 0.5f // deliberate push to start a maneuver
        const val CRUISE_DRIVE = 0.6f    // normalized cruise speed (~0.3 m/s in Normal)
    }
}

/** Deck-height (mm) + blade speed UI state. */
data class DeckBladeUi(
    val blade: BladeSpeed = BladeSpeed.OFF,
    val heightMm: Int = 50,
)
