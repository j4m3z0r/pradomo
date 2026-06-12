#!/usr/bin/env bash
# Pradomo Android test VM helper.
#
# Spins up an emulator that matches the target phone (Android 15 / API 35, arm64) and
# deploys the debug build to it. Good for fast UI / Demo-Mode iteration — including the
# touch maneuver triggers — but it can't do BLE or USB-gamepad input, so the physical
# mower + controller still need the real phone.
#
# Usage:
#   scripts/emulator.sh up       # ensure image+AVD, boot, then build+install+launch (default)
#   scripts/emulator.sh boot     # just ensure image+AVD and boot the emulator
#   scripts/emulator.sh deploy   # build+install+launch on the already-running emulator
set -euo pipefail

AVD_NAME="pradomo"
API="35"
IMAGE="system-images;android-${API};google_apis;arm64-v8a"
DEVICE="pixel_5"
SERIAL="emulator-5554"          # always target the emulator, never a real phone on adb

cd "$(dirname "$0")/.."

# --- locate the SDK (env first, then local.properties) ---
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
fi
SDK="${SDK:-$HOME/Library/Android/sdk}"
# Pin every tool to this SDK root (avdmanager/sdkmanager otherwise default to their own
# install location, which may lack the system image).
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
# Prefer this SDK's own cmdline-tools over whatever is on PATH (a Homebrew sdkmanager on
# PATH resolves to a different SDK root that may lack the system image).
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"; [[ -x "$SDKMANAGER" ]] || SDKMANAGER="$(command -v sdkmanager)"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"; [[ -x "$AVDMANAGER" ]] || AVDMANAGER="$(command -v avdmanager)"

ensure_image() {
  if [[ ! -d "$SDK/system-images/android-${API}/google_apis/arm64-v8a" ]]; then
    echo "Installing $IMAGE (one-time download)…"
    yes | "$SDKMANAGER" --sdk_root="$SDK" --install "$IMAGE" >/dev/null
  fi
}

ensure_avd() {
  if ! "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
    echo "Creating AVD '$AVD_NAME' on ${IMAGE}…"
    echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE" -d "$DEVICE" --force
  fi
}

running() { "$ADB" devices | grep -q "^${SERIAL}[[:space:]]*device$"; }

boot() {
  ensure_image
  ensure_avd
  if running; then echo "Emulator already booted."; return; fi
  echo "Booting '$AVD_NAME'…"
  nohup "$EMULATOR" "@$AVD_NAME" -no-snapshot -no-boot-anim -gpu auto \
    >/tmp/pradomo-emulator.log 2>&1 &
  "$ADB" -s "$SERIAL" wait-for-device
  until [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
  done
  echo "Booted."
}

deploy() {
  echo "Building debug APK…"
  ./gradlew :androidApp:assembleDebug
  "$ADB" -s "$SERIAL" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
  "$ADB" -s "$SERIAL" shell am start -n com.pradomo/.MainActivity
  echo "Launched on $SERIAL."
}

case "${1:-up}" in
  boot)   boot ;;
  deploy) deploy ;;
  up)     boot; deploy ;;
  *) echo "usage: $0 [up|boot|deploy]" >&2; exit 2 ;;
esac
