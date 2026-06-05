# Lymow RC App — Design Spec

**Date:** 2026-06-04
**Status:** Approved design, ready for implementation planning
**Platform:** Android first; Kotlin Multiplatform (KMP) for a clean path to iOS/desktop later
**Companion UI spec:** [`2026-06-04-lymow-rc-app-ui-design.md`](./2026-06-04-lymow-rc-app-ui-design.md) — the full "Site Rugged" visual/interaction design (colors, type, wireframes, joystick states, safety affordances, component inventory). This document is the architecture/scope spec; the UI spec is its visual counterpart and is authoritative for look-and-feel.

---

## 1. Purpose

A mobile app to **manually drive a Lymow robotic lawn mower over Bluetooth LE** using a single on-screen 2-axis joystick (forward/back + left/right in one control), unlike the official app's split controls. Built on KMP so iOS/desktop can follow, but **Android is the first and only target for v1**.

The BLE protocol is already reverse-engineered in the Python library at `../lymow-rc` (`lymow_rc/protocol.py`, `mower.py`, `telemetry.py`). This project ports that protocol to Kotlin and wraps it in a touch-driving app.

### Safety context (drives the whole design)

This controls **real equipment with spinning blades**, used **outdoors in bright sun**, **one-handed while watching the mower, not the screen**. Two hard facts shape everything:

1. **The joystick is spring-loaded: releasing it sends `stop(0,0)`.** A movement command persists until the next one is sent.
2. **There is no dead-man watchdog in the protocol.** If the BLE link drops mid-drive, the mower holds its last velocity until the link returns. This is unfixable app-side (same limitation as the Python library) — so the app makes connection state *impossible to misread* and treats a disconnect as an alarm, not a status change. The human is the watchdog.

The capture contains **no blade-motor command**, so v1 is **drive-only** (no start-mow, dock, blade-speed, or cut-height). That is safer and is the correct v1 scope.

---

## 2. Architecture overview

```
lymow-rc-app/
├── settings.gradle.kts, build.gradle.kts, gradle/  (Gradle wrapper, KMP + Compose plugins)
│
├── shared/                         # Kotlin Multiplatform library (no Android APIs in commonMain)
│   ├── commonMain/kotlin/
│   │   ├── protocol/LymowProtocol.kt    # pure byte encoding — port of protocol.py
│   │   ├── protocol/Telemetry.kt        # status+battery decode — subset of telemetry.py
│   │   ├── transport/MowerTransport.kt  # interface: scan / connect / write / notify
│   │   ├── input/InputSource.kt         # interface: continuous (drive,turn) + button events
│   │   └── MowerController.kt           # connect sequence, 3s keepalive, drive/stop, state flow
│   ├── commonTest/kotlin/               # golden-vector protocol tests + FakeTransport tests
│   └── androidMain/kotlin/              # (empty in v1; iosMain added later)
│
└── androidApp/                     # Android application (Compose, Material 3)
    ├── ble/AndroidMowerTransport.kt     # android.bluetooth implementation of MowerTransport
    ├── input/TouchJoystickSource.kt     # the on-screen joystick as an InputSource
    ├── ui/theme/                        # LymowTheme, colors, type, dimens ("Site Rugged")
    ├── ui/connect/DeviceListScreen.kt   # scan + pick + connect
    ├── ui/drive/DriveScreen.kt          # status header + joystick + safety affordances
    ├── ui/drive/Joystick.kt             # the VirtualJoystick composable
    └── ui/settings/SettingsScreen.kt    # nav shell only (forward-looking)
```

**The two seams that keep KMP and the future physical joystick clean:**

- **`MowerTransport`** (commonMain interface) is the platform seam. `commonMain` never touches `android.bluetooth`. iOS (CoreBluetooth) or a Kable-backed transport later just provides another implementation — the protocol and controller code are untouched.
- **`InputSource`** (commonMain interface) is the input seam. The DriveScreen drives the mower from an `InputSource`, not from the joystick composable directly, so a future USB-C `UsbGamepadSource` plugs in alongside the touch joystick with no controller/UI rework.

---

## 3. Protocol layer (`commonMain`, pure, golden-tested)

A direct Kotlin port of `protocol.py` plus the status/battery slice of `telemetry.py`. Pure functions over `ByteArray`, no I/O, no platform APIs — fully unit-testable.

### 3.1 `LymowProtocol`

| Constant / function | Behaviour (from `protocol.py`) |
|---|---|
| `SERVICE_UUID` | `12345678-1234-5678-1234-56789abcdef0` |
| `CONTROL_CHAR_UUID` | `12345678-1234-5678-1234-56789abcdef1` (canonical; the btsnoop capture showed it byte-reversed — always use canonical) |
| `DEVICE_NAME_PREFIX` | `Lymow_` |
| `DRIVE_LIMIT` / `TURN_LIMIT` | `0.5` / `0.6` |
| `encodeJoystick(drive, turn): ByteArray` | `header(7 bytes: 10313802520a0d) + LE float32 clamp(drive,±0.5) + 0x15 + LE float32 clamp(turn,±0.6)` → 16 bytes |
| `INIT_FRAMES: List<ByteArray>` | the 5 frames sent once, in order, on connect |
| `keepaliveFrame(clientId): ByteArray` | `3802da01 + len(clientId) + utf8(clientId)` (length-prefixed; clientId ≤ 255 bytes) |
| `makeClientId(): String` | `"<model>_<host>_<16 hex>"`, e.g. `"Android_pixel_<random>"` |
| `toBle(payload): ByteArray` | base64-encode (ASCII) for writing |
| `fromBle(data): ByteArray` | base64-decode an inbound notification value |

`drive` is +forward / −backward; `turn` is +left / −right. Clamping happens inside `encodeJoystick`.

### 3.2 `Telemetry` (status + battery only)

A minimal protobuf varint reader decodes the inbound `PbOutput`:
- `decodeTelemetry(payload: ByteArray): Telemetry` — base64-decodes if needed, then walks fields. **Never throws** on malformed input (unrecognised data leaves fields null), matching the Python decoder.
- Reads field 5 `PbRobotInfo` → sub-field 1 `status` (Int), sub-field 2 `battery` (Int %).
- `Telemetry(robotStatus: Int?, battery: Int?)` with `statusName: String?` via `RobotStatus.name(value)`.
- `RobotStatus` enum/name table ported verbatim: `0 none, 1 waiting, 2 cleaning, 3 paused, 4 docking, 5 charging, 6 remote_control, 7 error, 8 resuming, 9 zone_partition, 10 paused_docking, 11 updating, 12 charging_full, 13 emergency_stop`; unknown → `"unknown_<n>"`.

Position/heading/RSSI/error-codes are **out of scope for v1** (per the telemetry decision) but the field-walking decoder is structured so they can be added later without rework.

### 3.3 Golden tests (TDD)

Test vectors are lifted from the Python test suite in `../lymow-rc/tests/` (golden bytes captured from the real device). For each: write the failing Kotlin test asserting the exact bytes, then implement.
- `encodeJoystick` for representative (drive, turn) pairs incl. clamping at the limits and stop (0,0).
- `INIT_FRAMES` exact bytes and order.
- `keepaliveFrame` for a known clientId.
- `toBle`/`fromBle` round-trip.
- `decodeTelemetry` of a real captured frame → expected status + battery.

---

## 4. Controller (`commonMain`, coroutines)

`MowerController(transport: MowerTransport)` ties protocol to transport, mirroring `mower.py` exactly.

- `connect(device)`: `transport.connect` → `transport.startNotify` → send the 5 `INIT_FRAMES` in order → launch keepalive coroutine (every 3.0s, **sleep-first** to avoid racing the init frames / movement writes).
- `drive(forward, turn)`: write `encodeJoystick(...)` (Write-Without-Response).
- `stop()`: `drive(0, 0)`.
- `disconnect()`: cancel keepalive, **send `stop()`**, stop notify, disconnect. Stop is sent on **every** exit path (mirrors the Python lib's safety guarantee).
- `state: StateFlow<MowerState>` exposing connection state (`Disconnected / Connecting / Connected / Error`) and the latest `Telemetry`.
- Inbound notifications → `decodeTelemetry` → update `state`.

**Speed-profile hook (minimal):** `drive()` applies an optional `SpeedProfile(driveScale, turnScale)` (default `NORMAL = 1.0/1.0`) before the protocol clamp. v1 ships only the default and exposes no UI for it — but the hook exists so Phase-2 "slow/turbo" modes are a one-line addition rather than a refactor (see §7).

### 4.1 Controller tests (TDD, no hardware)

A `FakeTransport` (the Kotlin analogue of the Python fake BLE client) records writes and feeds synthetic notifications. Tests assert:
- On `connect`, the 5 init frames are written in exact order, then keepalive begins.
- Keepalive frames are emitted on cadence (using a test coroutine scheduler / virtual time).
- `disconnect` sends `stop()` before tearing down — and does so even after a simulated error.
- A fed telemetry notification updates `state` with the decoded status + battery.

---

## 5. Android BLE transport (`androidApp`)

`AndroidMowerTransport : MowerTransport` implements the seam with the platform Bluetooth stack.

- **Scan:** `BluetoothLeScanner` filtered to advertised names starting with `Lymow_`; emits discovered devices (name + RSSI) as a flow for the device-list screen. (User chose **scan + pick from a list**.)
- **Connect:** `BluetoothGatt` connect → `discoverServices` → enable notifications (CCCD) on `…cdef1`.
- **Write:** `writeCharacteristic` with `WRITE_TYPE_NO_RESPONSE` to `…cdef1`, carrying the base64 payload from `toBle`.
- **Notify:** characteristic-changed callback → forward raw bytes to the controller.
- **Permissions:** `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+, `neverForLocation` so no location rationale needed there); `ACCESS_FINE_LOCATION` on older APIs. A pre-permission explainer card precedes the OS prompt (per UI spec §4.1).
- **Targets:** `minSdk 26`, `compileSdk 35`.

> **BLE does not work on the Android emulator** (no real radio). The emulator can validate that the UI renders and navigates; **actually driving the mower requires a physical Android phone** with USB debugging. The emulator AVDs present (`Android_Accelerated_Oreo`, `lymow-mitm`) are UI-only for this project.

---

## 6. UI (Compose, Material 3) — "Site Rugged"

The full visual/interaction spec is the companion doc. Summary of the three screens v1 builds:

- **DeviceListScreen** — "Scan" pill (64dp), live list of `Lymow_*` device rows (name + 4-bar RSSI + Connect), with scanning / empty / Bluetooth-off / permission states. Tapping a device connects and navigates to Drive.
- **DriveScreen** — top status cluster (connection chip, battery %, mower status name), a hazard-bordered **E-stop** button top-right (72dp), a live cyan DRIVE/TURN magnitude readout, and the dominant **joystick** in the lower thumb zone. Portrait is the default and recommended orientation; landscape is supported but not forced; auto-rotate is locked while driving.
- **SettingsScreen** — **nav shell only** in v1 (controller/drive/about sections), leaving a clean IA home for the Phase-2 gamepad button-mapping screen.

### 6.1 Joystick (the heart of the app)

A single 2-axis spring-return virtual joystick — 300dp circular gate, 108dp knob, 96dp travel, ~15% deadzone. Y-up = +forward (→ drive), X-right = +turn-right (→ turn), each normalised to [−1, +1] and handed to the `InputSource`.

- **Output forces to 0/0 immediately on pointer-up** — the spring-return animation (~120ms) is cosmetic; release means stop *now*.
- Feedback states (idle / dragging / releasing / disabled-disconnected / error) are fully specified in the UI spec §5.
- Updates while dragging are **throttled (~50ms)** before reaching the controller to avoid flooding BLE; release sends stop without throttle.

### 6.2 Safety affordances

- Joystick release → `stop()`.
- App backgrounded (lifecycle `ON_STOP`) → `stop()` then disconnect.
- **Disconnect = alarm** (UI spec §6.1): red slam-down banner ("LINK LOST — mower may still be moving"), pulsing screen-edge border, joystick locked red, long-short-long haptic, prominent Reconnect button.
- **E-stop** sends stop-and-disengage and requires an explicit "Resume control" tap to re-arm.
- Mower `error` / `emergency_stop` status → joystick locked, red treatment.

---

## 7. Input seam & the Phase-2 physical joystick (designed-for, not built)

The user's stated end goal: a **USB-C physical gamepad/joystick** plugged into the phone, with **user-configurable buttons** (e.g. button 1 = "slow mode" for precision near flowers, button 2 = "turbo mode" for full speed, others = raise/lower cutting deck). v1 builds only the seam below; the rest is documented so the seam is correct.

**Built in v1:**
- **`InputSource`** interface (`commonMain`): exposes a continuous control vector `(drive: Float, turn: Float)` flow plus a discrete `ButtonEvent` flow.
- **`TouchJoystickSource`** (`androidApp`): the on-screen joystick implemented as an `InputSource` (emits the vector; emits no button events). DriveScreen wires `InputSource → MowerController`.

**Phase 2 (not built; the seam anticipates it):**
- **`UsbGamepadSource : InputSource`** — reads an Android USB-HID / game-controller device, mapping its analog stick to the control vector and its buttons to `ButtonEvent`s. Plugs in alongside (or instead of) the touch source.
- **`MowerAction`** sealed type the controller executes: `SetSpeedProfile(SLOW | NORMAL | TURBO)` (scales the drive/turn vector before the protocol clamp — see §4's speed-profile hook), and `DeckRaise` / `DeckLower`. **`DeckRaise`/`DeckLower` are BLE-unverified**: the `lymow-rc` notes flag deck/cut-height (and blade-speed, start-mow, dock) as visible in the schema but *not confirmed over BLE*. Implementing them requires a fresh btsnoop capture of the official app performing each action, decoded to exact bytes — the same method that nailed the joystick. Until then they remain unmapped placeholders.
- **`ButtonMappingConfig`** — persisted (Android DataStore) map of `buttonId → MowerAction`, edited via the button-mapping screen the UI spec already sketched (§4.4): live button-press highlight + fixed action-enum picker.

This keeps v1 lean (one input source, single default speed profile, no config UI) while guaranteeing the physical-joystick feature is additive: implement `UsbGamepadSource`, fill in `MowerAction` handlers, build the already-designed mapping UI — none of which disturbs the protocol, controller, transport, or touch-driving code.

---

## 8. Build & run

- **SDK setup (one-time):** install `platforms;android-35` + `build-tools;35.x` via `sdkmanager` and accept licenses (JDK 21 ✓, Android SDK at `~/Library/Android/sdk` ✓). Gradle wrapper + AGP/KMP plugins handle the rest.
- **Build/install:** Gradle assembles the debug APK; `adb install` to a connected device.
- **Verification boundary:** the agent can take this to "builds, installs, UI renders and navigates" (emulator or device). **Confirming actual mower movement is a James-with-the-mower-and-phone step** — BLE needs the physical phone, and driving needs the real mower powered on and clear of obstructions.

---

## 9. v1 scope boundary (explicit)

**In:** KMP project scaffold; Kotlin protocol port (joystick, init, keepalive, base64) with golden tests; status+battery telemetry decode; `MowerController` with keepalive + safe stop and FakeTransport tests; `MowerTransport` + Android BLE implementation; `InputSource` + `TouchJoystickSource`; the three Compose screens in the "Site Rugged" design (Settings as shell); permissions flow; safe-stop on release/background/disconnect; disconnect alarm.

**Out (Phase 2+):** USB-C gamepad input; configurable button mappings + persistence; speed-profile UI; start-mow / dock / pause-resume / blade-speed / cut-height (all BLE-unverified — need a capture first); full telemetry (position/heading/RSSI/map); iOS target; audible disconnect tone.

---

## 10. Known limitations & risks

- **No watchdog (inherent):** a mid-drive BLE drop leaves the mower moving. Mitigated only by stop-on-every-controlled-exit and a deliberately alarming disconnect UX; the human and the mower's own physical stop are the real safeguards. Documented prominently in the app and README.
- **Emulator can't do BLE:** real-mower validation requires the physical phone.
- **Phase-2 deck/blade actions are unverified over BLE:** must be captured and decoded before implementation; do not assume the cloud-MQTT field numbers match BLE.
- **Protocol fidelity:** the native BLE transport (Approach A) was chosen over a BLE library specifically so the exact Write-Without-Response / notification semantics are visible during first contact with the real mower.
