# Pradomo — Claude project guide

Pradomo is a **Kotlin Multiplatform (Android-first) app to manually drive a Lymow
robotic mower over Bluetooth LE** — single-stick touch + USB-C gamepad, deck/blade
control, live telemetry, and yard mapping.

## Build / test / run
- Build + unit tests: `./gradlew :shared:jvmTest :androidApp:assembleDebug`
- Pure logic lives in `shared/` (commonMain) and **is unit-tested** — add tests there
  for protocol/control/map math. `androidApp/` is the Android UI + BLE + Room.
- Deploy over **wireless adb** (the phone's single USB-C port is shared with the
  gamepad): `adb -s <ip>:5555 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`
  then `adb shell am start -n com.pradomo/.MainActivity`.
- **Demo Mode** is the fast UI loop: Connect screen → "Try demo mode" → a simulated
  mower you can drive (no BLE). Its data is isolated under the `DEMO` map key.

## Critical gotchas
- **Must `requestMtu(517)` on BLE connect** (`AndroidMowerTransport`). Without it,
  joystick writes truncate (turning silently dies) and telemetry fragments. Never
  regress this.
- The mower has **no dead-man watchdog** — a dropped link leaves the last command
  latched. E-STOP / disconnect / backgrounding must stay **instant** (they bypass the
  deceleration smoother). Keep that invariant.
- Joystick frames must be **resent continuously** (~80ms loop) or the mower stops.

## Conventions
- Package is `com.pradomo`. Keep the name **Lymow** only where it's genuinely the
  mower's (the `LymowProtocol`, the BLE `Lymow_` device-name prefix) — everything else
  is Pradomo.
- Commit messages end with: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- Screenshots from this phone are 1080×2400; the image tool rejects >2000px tall, so
  downscale first: `sips -Z 1500 in.png --out out.png`.
