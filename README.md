# Lymow RC

A Kotlin Multiplatform (Android-first) app to manually drive a Lymow robotic
mower over Bluetooth LE with a single 2-axis joystick. Reverse-engineered BLE
protocol ported from the Python `lymow-rc` library.

## ⚠️ Safety

This drives **real equipment with spinning blades**. Read before using:

- The joystick is spring-loaded: **releasing it sends stop**. Releasing the
  joystick, backgrounding the app, and the E-STOP button all stop the mower.
- **There is no dead-man watchdog in the protocol.** If the Bluetooth link drops
  while driving, the mower holds its last command until the link returns. The app
  makes a dropped link loud and obvious (red banner, locked joystick), but it
  cannot stop the mower without a connection. **Always stay ready to use the
  mower's own physical stop / power switch**, and only drive on open ground clear
  of people, pets, and obstacles.
- v1 is **drive-only** — it sends no blade-motor command (none was in the capture).

## Project layout

- `shared/` — Kotlin Multiplatform library (`commonMain`): the protocol port
  (`LymowProtocol`), telemetry decoder (`decodeTelemetry`), the `MowerController`
  (connect handshake, 3s keepalive, safe stop), and the `MowerTransport` /
  `InputSource` seams. Pure, golden-tested against real captured frames.
- `androidApp/` — the Android app: `android.bluetooth` transport, the on-screen
  joystick (`TouchJoystickSource` / `VirtualJoystick`), and the Compose UI.
- `docs/superpowers/specs/` — design + UI design specs.
- `docs/superpowers/plans/` — the implementation plan.

## Build & run

Requires JDK 17+ (JDK 21 used here), the Android SDK with `platforms;android-35`
and `build-tools;35.0.0`, and `local.properties` pointing at the SDK
(`sdk.dir=/path/to/Android/sdk`).

```bash
# Run the shared-module unit tests (no hardware needed)
./gradlew :shared:jvmTest

# Build the debug APK
./gradlew :androidApp:assembleDebug

# Install on a connected phone (Bluetooth driving needs a REAL device —
# the emulator has no BLE radio)
./gradlew :androidApp:installDebug
```

## Driving the mower

1. Power on the mower on open ground, clear of obstacles.
2. Launch the app, grant the Bluetooth permissions, tap **SCAN**.
3. Tap your `Lymow_…` device to connect. The header shows connection state,
   mower status, and battery %.
4. Push the joystick to drive (up = forward, left/right = turn). **Release to
   stop.** Use **E-STOP** or background the app to stop and disconnect.

## Status & roadmap

**v1 (this build):** scan + pick + connect, single-joystick drive, battery +
status readout, safe-stop on release/background/disconnect, the "Site Rugged" UI.

**Phase 2 (designed-for, not built):** USB-C physical gamepad input with
user-configurable button mappings (e.g. slow/turbo speed profiles, raise/lower
cutting deck); iOS target. The deck/blade actions are **not yet verified over
BLE** — they need a fresh btsnoop capture of the official app first, the same way
the joystick was reverse-engineered.

## Tests

`./gradlew :shared:jvmTest` runs the protocol and controller tests (golden byte
vectors from real captured frames + a fake transport). The BLE and UI layers are
verified on a physical device.
