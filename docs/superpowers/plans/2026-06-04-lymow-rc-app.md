# Lymow RC App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A KMP Android app that manually drives a Lymow mower over BLE with a single 2-axis joystick (forward/back + left/right), showing battery + status, and stopping safely on release/background/disconnect.

**Architecture:** A Kotlin Multiplatform `shared` library holds the pure protocol port, telemetry decoder, `MowerController` (coroutines), and the `MowerTransport` / `InputSource` seams — all platform-agnostic and unit-tested. An `androidApp` module provides the `android.bluetooth` transport, the on-screen joystick as a `TouchJoystickSource`, and the Compose UI per the "Site Rugged" design.

**Tech Stack:** Kotlin 2.1, Gradle (KMP + Android), Jetpack Compose / Material 3, kotlinx-coroutines, `android.bluetooth` BLE. JDK 21, `compileSdk 35`, `minSdk 26`.

**Reference docs:**
- Spec: `docs/superpowers/specs/2026-06-04-lymow-rc-app-design.md`
- UI spec (authoritative for visuals): `docs/superpowers/specs/2026-06-04-lymow-rc-app-ui-design.md`
- Source of truth for protocol: `../lymow-rc/lymow_rc/{protocol,mower,telemetry}.py` and `../lymow-rc/tests/`

**Golden vectors** (lifted from `../lymow-rc/tests/`, used throughout):
- `encodeJoystick(0,0)` → `10313802520a0d000000001500000000`
- `encodeJoystick(0.5,0)` → `10313802520a0d0000003f1500000000`
- `encodeJoystick(-0.5,0)` → `10313802520a0d000000bf1500000000`
- `encodeJoystick(0,0.6)` → `10313802520a0d00000000159a99193f`  (left)
- `encodeJoystick(0,-0.6)` → `10313802520a0d00000000159a9919bf`  (right)
- `keepaliveFrame("ASUS_AI2302_Android_2b3f7b75a62d548a")` → `3802da0124415355535f4149323330325f416e64726f69645f32623366376237356136326435343861`

**Conventions:** TDD (failing test → minimal impl → commit). Commit after each task. End commit messages with the `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer. The repo is not yet a git repo — Task 0 initialises it.

---

## File structure

```
lymow-rc-app/
├── settings.gradle.kts                 # modules + plugin/dependency repos
├── build.gradle.kts                    # root (plugins declared, not applied)
├── gradle.properties                   # AndroidX, JVM args
├── gradle/libs.versions.toml           # version catalog
├── gradle/wrapper/…                    # Gradle wrapper 8.10.2
│
├── shared/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/id/au/james/lymow/
│       │   ├── protocol/LymowProtocol.kt
│       │   ├── protocol/Telemetry.kt
│       │   ├── transport/MowerTransport.kt   # MowerTransport, MowerScanner, DiscoveredDevice
│       │   ├── input/InputSource.kt          # InputSource, ControlVector, ButtonEvent
│       │   └── control/MowerController.kt     # MowerController, MowerState, ConnectionState, SpeedProfile
│       └── commonTest/kotlin/id/au/james/lymow/
│           ├── protocol/LymowProtocolTest.kt
│           ├── protocol/TelemetryTest.kt
│           └── control/MowerControllerTest.kt # incl. FakeTransport
│
└── androidApp/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/id/au/james/lymow/
            ├── MainActivity.kt
            ├── LymowApp.kt                     # NavHost
            ├── ble/AndroidBle.kt               # AndroidMowerScanner + AndroidMowerTransport
            ├── input/TouchJoystickSource.kt
            ├── ui/theme/Theme.kt               # LymowTheme, colors, type, dimens
            ├── ui/connect/ConnectScreen.kt + ConnectViewModel.kt
            ├── ui/drive/DriveScreen.kt + DriveViewModel.kt + VirtualJoystick.kt
            └── ui/settings/SettingsScreen.kt   # shell only
```

---

## Task 0: Project scaffold (KMP shared + Android app)

**Files:** all the Gradle/wrapper/manifest files listed above. This task ends with an empty-but-building project.

- [ ] **Step 1: Install the missing Android SDK pieces**

The SDK at `~/Library/Android/sdk` has only platforms 28/29. Install platform 35 + build-tools 35 and accept licenses:

Run:
```bash
SDK="$HOME/Library/Android/sdk"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses 2>/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```
Expected: downloads complete; `ls "$SDK/platforms"` now includes `android-35`.
> If `cmdline-tools/latest` is absent, install it first: `sdkmanager "cmdline-tools;latest"` via the existing `tools/bin/sdkmanager`, or install from Android Studio's SDK Manager. JDK 21 is already present (`java -version` → 21).

- [ ] **Step 2: Initialise git and the local.properties SDK pointer**

Run:
```bash
cd /Users/james/src/lymow-rc-app
git init
printf 'sdk.dir=%s/Library/Android/sdk\n' "$HOME" > local.properties
cat > .gitignore <<'EOF'
.gradle/
build/
local.properties
.idea/
*.iml
.DS_Store
EOF
```

- [ ] **Step 3: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
coroutines = "1.9.0"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
composeBom = "2024.12.01"
lifecycle = "2.8.7"
navigationCompose = "2.8.5"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 4: Create root `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "lymow-rc-app"
include(":shared", ":androidApp")
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 5: Install the Gradle wrapper (8.10.2)**

Run (uses any Gradle on PATH, or Android Studio's bundled one; if none, download per note):
```bash
cd /Users/james/src/lymow-rc-app
gradle wrapper --gradle-version 8.10.2 --distribution-type bin 2>/dev/null \
  || echo "No 'gradle' on PATH — create the wrapper from Android Studio, or copy gradle/wrapper from any 8.10.2 project."
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` exist. Verify: `./gradlew --version` shows Gradle 8.10.2, JVM 21.

- [ ] **Step 6: Create `shared/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm() // fast pure-Kotlin unit tests: ./gradlew :shared:jvmTest
    // iosX64(); iosArm64(); iosSimulatorArm64()  // Phase 2: iOS

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "id.au.james.lymow.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

- [ ] **Step 7: Create `androidApp/build.gradle.kts`**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "id.au.james.lymow"
    compileSdk = 35
    defaultConfig {
        applicationId = "id.au.james.lymow"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 8: Create `androidApp/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- API 31+: scan without location -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <!-- API <= 30 legacy -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

    <application
        android:allowBackup="true"
        android:label="Lymow RC"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 9: Create a minimal `MainActivity.kt` so the app compiles**

`androidApp/src/main/kotlin/id/au/james/lymow/MainActivity.kt`:
```kotlin
package id.au.james.lymow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Lymow RC") }
    }
}
```

- [ ] **Step 10: Build to verify the scaffold**

Run:
```bash
cd /Users/james/src/lymow-rc-app
./gradlew :shared:jvmTest :androidApp:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. `:shared:jvmTest` runs (0 tests). The debug APK appears at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
> If you hit an AGP/Kotlin/Compose version incompatibility, align versions using the official AGP↔Kotlin↔Compose-compiler matrix and re-run; do not downgrade `compileSdk` below 35.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "chore: scaffold KMP shared + Android app, building skeleton

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 1: Protocol — `encodeJoystick` (golden TDD)

**Files:**
- Create: `shared/src/commonMain/kotlin/id/au/james/lymow/protocol/LymowProtocol.kt`
- Test: `shared/src/commonTest/kotlin/id/au/james/lymow/protocol/LymowProtocolTest.kt`

- [ ] **Step 1: Write the failing test**

`LymowProtocolTest.kt`:
```kotlin
package id.au.james.lymow.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

class LymowProtocolTest {
    @Test fun joystick_stop() =
        assertEquals("10313802520a0d000000001500000000", LymowProtocol.encodeJoystick(0f, 0f).hex())

    @Test fun joystick_forward() =
        assertEquals("10313802520a0d0000003f1500000000", LymowProtocol.encodeJoystick(0.5f, 0f).hex())

    @Test fun joystick_backward() =
        assertEquals("10313802520a0d000000bf1500000000", LymowProtocol.encodeJoystick(-0.5f, 0f).hex())

    @Test fun joystick_left() =
        assertEquals("10313802520a0d00000000159a99193f", LymowProtocol.encodeJoystick(0f, 0.6f).hex())

    @Test fun joystick_right() =
        assertEquals("10313802520a0d00000000159a9919bf", LymowProtocol.encodeJoystick(0f, -0.6f).hex())

    @Test fun joystick_clamps_drive() =
        assertEquals(LymowProtocol.encodeJoystick(0.5f, 0f).hex(), LymowProtocol.encodeJoystick(9f, 0f).hex())

    @Test fun joystick_clamps_turn() =
        assertEquals(LymowProtocol.encodeJoystick(0f, -0.6f).hex(), LymowProtocol.encodeJoystick(0f, -9f).hex())
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest`
Expected: FAIL — `LymowProtocol` unresolved.

- [ ] **Step 3: Write minimal implementation**

`LymowProtocol.kt`:
```kotlin
package id.au.james.lymow.protocol

object LymowProtocol {
    const val SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
    const val CONTROL_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"
    const val DEVICE_NAME_PREFIX = "Lymow_"
    const val DRIVE_LIMIT = 0.5f
    const val TURN_LIMIT = 0.6f

    private val JOYSTICK_HEADER =
        byteArrayOf(0x10, 0x31, 0x38, 0x02, 0x52, 0x0a, 0x0d)
    private const val TURN_TAG: Byte = 0x15

    fun encodeJoystick(drive: Float, turn: Float): ByteArray {
        val d = drive.coerceIn(-DRIVE_LIMIT, DRIVE_LIMIT)
        val t = turn.coerceIn(-TURN_LIMIT, TURN_LIMIT)
        return JOYSTICK_HEADER + floatLe(d) + byteArrayOf(TURN_TAG) + floatLe(t)
    }

    private fun floatLe(v: Float): ByteArray {
        val bits = v.toRawBits()
        return byteArrayOf(
            (bits and 0xFF).toByte(),
            ((bits ushr 8) and 0xFF).toByte(),
            ((bits ushr 16) and 0xFF).toByte(),
            ((bits ushr 24) and 0xFF).toByte(),
        )
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:jvmTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/
git commit -m "feat(protocol): encodeJoystick with golden vectors

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Protocol — init frames, keepalive, client id (TDD)

**Files:**
- Modify: `shared/.../protocol/LymowProtocol.kt`
- Modify: `shared/.../protocol/LymowProtocolTest.kt`

- [ ] **Step 1: Add failing tests**

Append to `LymowProtocolTest.kt` (inside the class):
```kotlin
    @Test fun init_frames_match_capture() {
        assertEquals(
            listOf(
                "10314a025801",
                "3802da0100",
                "10312835",
                "10314a021001",
                "1031281438024a022801",
            ),
            LymowProtocol.INIT_FRAMES.map { it.hex() },
        )
    }

    @Test fun keepalive_golden() {
        val frame = LymowProtocol.keepaliveFrame("ASUS_AI2302_Android_2b3f7b75a62d548a")
        assertEquals(
            "3802da0124415355535f4149323330325f416e64726f69645f32623366376237356136326435343861",
            frame.hex(),
        )
    }

    @Test fun keepalive_length_prefix() {
        val frame = LymowProtocol.keepaliveFrame("abc")
        assertEquals("3802da01", frame.copyOfRange(0, 4).hex())
        assertEquals(3, frame[4].toInt())
        assertEquals("abc", frame.copyOfRange(5, frame.size).decodeToString())
    }

    @Test fun make_client_id_format() {
        val cid = LymowProtocol.makeClientId(model = "Android", host = "pixel")
        val rand = cid.substringAfterLast("_")
        assertEquals("Android_pixel", cid.removeSuffix("_$rand"))
        assertEquals(16, rand.length)
        rand.toLong(16) // parses as hex, else throws
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest`
Expected: FAIL — `INIT_FRAMES`/`keepaliveFrame`/`makeClientId` unresolved.

- [ ] **Step 3: Add implementation to `LymowProtocol`**

Add inside the `object LymowProtocol`:
```kotlin
    val INIT_FRAMES: List<ByteArray> = listOf(
        byteArrayOf(0x10, 0x31, 0x4a, 0x02, 0x58, 0x01),
        byteArrayOf(0x38, 0x02, 0xda.toByte(), 0x01, 0x00),
        byteArrayOf(0x10, 0x31, 0x28, 0x35),
        byteArrayOf(0x10, 0x31, 0x4a, 0x02, 0x10, 0x01),
        byteArrayOf(0x10, 0x31, 0x28, 0x14, 0x38, 0x02, 0x4a, 0x02, 0x28, 0x01),
    )

    private val KEEPALIVE_PREFIX = byteArrayOf(0x38, 0x02, 0xda.toByte(), 0x01)

    fun keepaliveFrame(clientId: String): ByteArray {
        val cid = clientId.encodeToByteArray()
        require(cid.size <= 255) { "client_id too long" }
        return KEEPALIVE_PREFIX + byteArrayOf(cid.size.toByte()) + cid
    }

    fun makeClientId(model: String = "Android", host: String = "phone"): String {
        val rand = (0 until 8).joinToString("") {
            kotlin.random.Random.nextInt(0, 256).toString(16).padStart(2, '0')
        }
        return "${model}_${host}_$rand"
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/
git commit -m "feat(protocol): init frames, keepalive, client id

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Protocol — base64 wire codec (TDD)

**Files:** modify `LymowProtocol.kt` and `LymowProtocolTest.kt`.

- [ ] **Step 1: Add failing test**

Append to the test class:
```kotlin
    @Test fun to_ble_from_ble_roundtrip() {
        val payload = LymowProtocol.encodeJoystick(-0.5f, 0f)
        val wire = LymowProtocol.toBle(payload)
        assertEquals("EDE4AlIKDQAAAL8VAAAAAA==", wire.decodeToString())
        assertEquals(payload.hex(), LymowProtocol.fromBle(wire).hex())
    }
```
(The base64 string is `base64("10313802520a0d000000bf1500000000" bytes)`.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest`
Expected: FAIL — `toBle`/`fromBle` unresolved.

- [ ] **Step 3: Implement**

Add to `LymowProtocol` (and the import at top of file):
```kotlin
// at top of file, below package:
import kotlin.io.encoding.Base64

// inside object LymowProtocol:
    fun toBle(payload: ByteArray): ByteArray = Base64.encode(payload).encodeToByteArray()
    fun fromBle(data: ByteArray): ByteArray = Base64.decode(data.decodeToString())
```
> `kotlin.io.encoding.Base64` is stable as of Kotlin 2.1. If the compiler still requests opt-in, add `@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)` to the two functions.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/
git commit -m "feat(protocol): base64 wire codec

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Telemetry decoder (status + battery, golden TDD)

**Files:**
- Create: `shared/src/commonMain/kotlin/id/au/james/lymow/protocol/Telemetry.kt`
- Create: `shared/src/commonTest/kotlin/id/au/james/lymow/protocol/TelemetryTest.kt`

- [ ] **Step 1: Write the failing test**

`TelemetryTest.kt` (base64 frames copied verbatim from `../lymow-rc/tests/test_telemetry.py`):
```kotlin
package id.au.james.lymow.protocol

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelemetryTest {
    private val CHARGING =
        "KiIIBRBLGMT//////////wEgzf//////////ATABQAFIAVABMhAIChVNFQw8Hb4wmTwg" +
        "AygBSgQwAFgBYgoVjVu7Qy0AAAAAcg8NguyzwBV6qNfAHVrGvD+yAQUyAy01N4ICAggB"
    private val REMOTE =
        "KiAIBhBLGMP//////////wEgzf//////////ATABSAFQATIQCAoV4ukVPB28dJM8IAMoA0o" +
        "EMABYAWIKFY1bu0MtAAAAAHIPDXpqt8AVkETzwB22cLE/ggICCAE="
    private val NET = "sgEgCgdzdWNjZXNzEg4xOTIuMTY4LjEzNi42MBoFMTAwMDI="

    @Test fun decode_charging() {
        val t = decodeTelemetry(CHARGING.encodeToByteArray())
        assertEquals(RobotStatus.CHARGING, t.robotStatus)
        assertEquals("charging", t.statusName)
        assertEquals(75, t.battery)
    }

    @Test fun decode_remote_control() {
        val t = decodeTelemetry(REMOTE.encodeToByteArray())
        assertEquals(RobotStatus.REMOTE_CONTROL, t.robotStatus)
        assertEquals("remote_control", t.statusName)
        assertEquals(75, t.battery)
    }

    @Test fun decode_accepts_raw_bytes() {
        val raw = Base64.decode(CHARGING)
        assertEquals(75, decodeTelemetry(raw).battery)
    }

    @Test fun network_frame_has_no_status() {
        val t = decodeTelemetry(NET.encodeToByteArray())
        assertNull(t.robotStatus)
        assertNull(t.battery)
    }

    @Test fun garbage_is_empty_not_exception() {
        val t = decodeTelemetry(byteArrayOf(0x00, 0x01, 0x02))
        assertNull(t.robotStatus)
        assertNull(t.battery)
    }

    @Test fun unknown_status_name() = assertEquals("unknown_99", RobotStatus.name(99))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest`
Expected: FAIL — `decodeTelemetry`/`RobotStatus`/`Telemetry` unresolved.

- [ ] **Step 3: Implement `Telemetry.kt`**

```kotlin
package id.au.james.lymow.protocol

import kotlin.io.encoding.Base64

class Telemetry(val robotStatus: Int?, val battery: Int?) {
    val statusName: String? get() = robotStatus?.let { RobotStatus.name(it) }
}

object RobotStatus {
    const val CHARGING = 5
    const val REMOTE_CONTROL = 6
    private val NAMES = mapOf(
        0 to "none", 1 to "waiting", 2 to "cleaning", 3 to "paused", 4 to "docking",
        5 to "charging", 6 to "remote_control", 7 to "error", 8 to "resuming",
        9 to "zone_partition", 10 to "paused_docking", 11 to "updating",
        12 to "charging_full", 13 to "emergency_stop",
    )
    fun name(value: Int): String = NAMES[value] ?: "unknown_$value"
}

private const val WT_VARINT = 0
private const val WT_LEN = 2

/** Decode a PbOutput frame (base64 text OR raw protobuf bytes). Never throws. */
fun decodeTelemetry(payload: ByteArray): Telemetry {
    val raw = coerceRaw(payload)
    var status: Int? = null
    var battery: Int? = null
    val r = ProtoReader(raw)
    while (r.hasNext()) {
        val f = r.readField() ?: break
        if (f.number == 5 && f.wireType == WT_LEN && f.bytes != null) { // PbRobotInfo
            val sub = ProtoReader(f.bytes)
            while (sub.hasNext()) {
                val g = sub.readField() ?: break
                if (g.wireType == WT_VARINT) when (g.number) {
                    1 -> status = g.varint?.toInt()
                    2 -> battery = g.varint?.toInt()
                }
            }
        }
    }
    return Telemetry(status, battery)
}

private val B64_CHARS =
    (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('+', '/', '=')).toSet()

private fun coerceRaw(payload: ByteArray): ByteArray {
    if (payload.isNotEmpty() && payload.all { (it.toInt() and 0xFF).toChar() in B64_CHARS }) {
        return try { Base64.decode(payload.decodeToString()) } catch (_: Exception) { payload }
    }
    return payload
}

private class Field(
    val number: Int,
    val wireType: Int,
    val varint: Long? = null,
    val bytes: ByteArray? = null,
)

private class ProtoReader(private val b: ByteArray) {
    private var i = 0
    fun hasNext() = i < b.size

    private fun readVarint(): Long? {
        var shift = 0
        var result = 0L
        while (i < b.size) {
            val x = b[i].toInt() and 0xFF
            i++
            result = result or ((x.toLong() and 0x7F) shl shift)
            if (x and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    fun readField(): Field? {
        val key = readVarint() ?: return null
        val number = (key shr 3).toInt()
        return when ((key and 7).toInt()) {
            0 -> Field(number, 0, varint = readVarint() ?: return null)
            2 -> {
                val len = (readVarint() ?: return null).toInt()
                if (len < 0 || i + len > b.size) return null
                val bytes = b.copyOfRange(i, i + len); i += len
                Field(number, 2, bytes = bytes)
            }
            1 -> { if (i + 8 > b.size) return null; i += 8; Field(number, 1) }
            5 -> { if (i + 4 > b.size) return null; i += 4; Field(number, 5) }
            else -> null
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/
git commit -m "feat(protocol): telemetry decode (status + battery)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Transport, input, and state types (interfaces only)

**Files:**
- Create: `shared/src/commonMain/kotlin/id/au/james/lymow/transport/MowerTransport.kt`
- Create: `shared/src/commonMain/kotlin/id/au/james/lymow/input/InputSource.kt`

No tests (pure declarations; exercised by Task 6).

- [ ] **Step 1: Create `MowerTransport.kt`**

```kotlin
package id.au.james.lymow.transport

import kotlinx.coroutines.flow.Flow

/** A Lymow device seen while scanning. */
data class DiscoveredDevice(val id: String, val name: String, val rssi: Int)

/** Platform BLE scanner. Android/iOS provide implementations. */
interface MowerScanner {
    /** Emits the current set of matching devices as they are (re)discovered. */
    fun scan(): Flow<List<DiscoveredDevice>>
}

/**
 * Dumb GATT transport for ONE device: connect, (un)subscribe to the control
 * characteristic, and write raw values. The controller owns all protocol logic.
 */
interface MowerTransport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun startNotify(onValue: (ByteArray) -> Unit)
    suspend fun stopNotify()
    /** Write [value] to the control characteristic (Write-Without-Response). */
    suspend fun write(value: ByteArray)
}
```

- [ ] **Step 2: Create `InputSource.kt`**

```kotlin
package id.au.james.lymow.input

import kotlinx.coroutines.flow.Flow

/** Normalised control: drive/turn each in [-1f, 1f]. */
data class ControlVector(val drive: Float, val turn: Float) {
    companion object { val ZERO = ControlVector(0f, 0f) }
}

/** Discrete button press from a physical controller (Phase 2). */
data class ButtonEvent(val buttonId: Int, val pressed: Boolean)

/**
 * A source of control input. v1 has one implementation (the on-screen
 * joystick); a USB-C gamepad source plugs in here later with no other changes.
 */
interface InputSource {
    val vector: Flow<ControlVector>
    val buttons: Flow<ButtonEvent>
}
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :shared:compileKotlinJvm`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add shared/
git commit -m "feat: transport, input, and discovery interfaces

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: `MowerController` + `FakeTransport` (coroutines TDD)

**Files:**
- Create: `shared/src/commonMain/kotlin/id/au/james/lymow/control/MowerController.kt`
- Create: `shared/src/commonTest/kotlin/id/au/james/lymow/control/MowerControllerTest.kt`

- [ ] **Step 1: Write the failing test (incl. FakeTransport)**

`MowerControllerTest.kt`:
```kotlin
package id.au.james.lymow.control

import id.au.james.lymow.protocol.LymowProtocol
import id.au.james.lymow.protocol.RobotStatus
import id.au.james.lymow.transport.MowerTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private class FakeTransport : MowerTransport {
    val writes = mutableListOf<ByteArray>()
    var connected = false
    var notifying = false
    private var cb: ((ByteArray) -> Unit)? = null
    override suspend fun connect() { connected = true }
    override suspend fun disconnect() { connected = false }
    override suspend fun startNotify(onValue: (ByteArray) -> Unit) { notifying = true; cb = onValue }
    override suspend fun stopNotify() { notifying = false }
    override suspend fun write(value: ByteArray) { writes.add(value) }
    fun feed(value: ByteArray) = cb?.invoke(value)
    /** Decoded raw protobuf frames (un-base64) the controller wrote. */
    fun framesHex() = writes.map { LymowProtocol.fromBle(it).hex() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MowerControllerTest {

    @Test fun connect_sends_init_frames_then_starts_keepalive() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        assertTrue(t.notifying)
        assertEquals(LymowProtocol.INIT_FRAMES.map { it.hex() }, t.framesHex().take(5))
        assertEquals(ConnectionState.Connected, c.state.value.connection)
    }

    @Test fun drive_normalised_maps_to_clamped_payloads() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        t.writes.clear()
        c.drive(1f, 0f)    // full forward
        c.drive(-1f, 0f)   // full backward
        c.drive(0f, -1f)   // full right
        c.stop()
        assertEquals(
            listOf(
                "10313802520a0d0000003f1500000000",
                "10313802520a0d000000bf1500000000",
                "10313802520a0d00000000159a9919bf",
                "10313802520a0d000000001500000000",
            ),
            t.framesHex(),
        )
    }

    @Test fun keepalive_fires_after_interval() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", keepaliveIntervalMs = 3000, scope = backgroundScope)
        c.connect()
        t.writes.clear()
        advanceTimeBy(3100)
        assertTrue(LymowProtocol.keepaliveFrame("abc").hex() in t.framesHex())
    }

    @Test fun disconnect_sends_stop_then_disconnects() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        t.writes.clear()
        c.disconnect()
        assertTrue("10313802520a0d000000001500000000" in t.framesHex())
        assertFalse(t.connected)
        assertEquals(ConnectionState.Disconnected, c.state.value.connection)
    }

    @Test fun notification_updates_state() = runTest {
        val t = FakeTransport()
        val c = MowerController(t, clientId = "abc", scope = backgroundScope)
        c.connect()
        val charging =
            ("KiIIBRBLGMT//////////wEgzf//////////ATABQAFIAVABMhAIChVNFQw8Hb4wmTwg" +
             "AygBSgQwAFgBYgoVjVu7Qy0AAAAAcg8NguyzwBV6qNfAHVrGvD+yAQUyAy01N4ICAggB")
        t.feed(charging.encodeToByteArray())
        assertEquals(RobotStatus.CHARGING, c.state.value.telemetry?.robotStatus)
        assertEquals(75, c.state.value.telemetry?.battery)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest`
Expected: FAIL — `MowerController`/`ConnectionState` unresolved.

- [ ] **Step 3: Implement `MowerController.kt`**

```kotlin
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
        transport.disconnect()
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

    private fun onNotify(value: ByteArray) {
        val t = decodeTelemetry(value)
        _state.update { it.copy(telemetry = t) }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:jvmTest`
Expected: PASS (all shared tests green).

- [ ] **Step 5: Commit**

```bash
git add shared/
git commit -m "feat(control): MowerController with keepalive and safe stop

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Android BLE — scanner + transport

**Files:**
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/ble/AndroidBle.kt`

No unit test (requires the platform Bluetooth stack + a real device — verified manually in Task 12). Keep this file the *only* place `android.bluetooth` is touched.

- [ ] **Step 1: Implement `AndroidBle.kt`**

```kotlin
package id.au.james.lymow.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import id.au.james.lymow.protocol.LymowProtocol
import id.au.james.lymow.transport.DiscoveredDevice
import id.au.james.lymow.transport.MowerScanner
import id.au.james.lymow.transport.MowerTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val SERVICE = UUID.fromString(LymowProtocol.SERVICE_UUID)
private val CONTROL = UUID.fromString(LymowProtocol.CONTROL_CHAR_UUID)
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission") // callers gate on BLUETOOTH_SCAN/CONNECT before use
class AndroidMowerScanner(context: Context) : MowerScanner {
    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override fun scan(): Flow<List<DiscoveredDevice>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (!name.startsWith(LymowProtocol.DEVICE_NAME_PREFIX)) return
                found[result.device.address] =
                    DiscoveredDevice(result.device.address, name, result.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }
        }
        val scanner = adapter.bluetoothLeScanner ?: run { close(); return@callbackFlow }
        scanner.startScan(cb)
        awaitClose { scanner.stopScan(cb) }
    }
}

@SuppressLint("MissingPermission")
class AndroidMowerTransport(
    private val context: Context,
    private val deviceId: String,
) : MowerTransport {
    private val adapter: BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var onValue: ((ByteArray) -> Unit)? = null

    private var connectCont: ((Result<Unit>) -> Unit)? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED)
                connectCont?.invoke(Result.failure(IllegalStateException("disconnected")))
                    .also { connectCont = null }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            control = g.getService(SERVICE)?.getCharacteristic(CONTROL)
            connectCont?.invoke(
                if (control != null) Result.success(Unit)
                else Result.failure(IllegalStateException("control characteristic not found"))
            ).also { connectCont = null }
        }
        @Deprecated("Deprecated in API 33; value arg added in the newer overload")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == CONTROL) onValue?.invoke(ch.value)
        }
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) { if (ch.uuid == CONTROL) onValue?.invoke(value) }
    }

    override suspend fun connect() = suspendCoroutine<Unit> { cont ->
        connectCont = { cont.resume(Unit).also { _ -> it.getOrThrow() } }
        // resume with the result: success → Unit, failure → throw
        connectCont = { result -> result.fold({ cont.resume(Unit) }, { cont.resumeWith(Result.failure(it)) }) }
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceId)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override suspend fun startNotify(onValue: (ByteArray) -> Unit) {
        this.onValue = onValue
        val g = gatt ?: error("not connected")
        val ch = control ?: error("no control characteristic")
        g.setCharacteristicNotification(ch, true)
        ch.getDescriptor(CCCD)?.let { d ->
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    override suspend fun stopNotify() {
        val g = gatt ?: return
        val ch = control ?: return
        g.setCharacteristicNotification(ch, false)
    }

    override suspend fun write(value: ByteArray) {
        val g = gatt ?: error("not connected")
        val ch = control ?: error("no control characteristic")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION") run { ch.value = value; g.writeCharacteristic(ch) }
        }
    }

    override suspend fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; control = null; onValue = null
    }
}
```
> Note the two `onCharacteristicChanged` overloads: API 33+ delivers `value` directly; older Androids use the deprecated `ch.value`. Both forward to `onValue`. The `connect()` continuation is resumed once from `onServicesDiscovered` (success) or an early disconnect (failure).

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (warnings about deprecated BLE APIs are fine).

- [ ] **Step 3: Commit**

```bash
git add androidApp/
git commit -m "feat(ble): Android scanner + GATT transport

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Theme / design system ("Site Rugged")

**Files:**
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/ui/theme/Theme.kt`

Implements the color/type/dimens tokens from UI spec §2–§3. No test (visual).

- [ ] **Step 1: Implement `Theme.kt`**

```kotlin
package id.au.james.lymow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Site Rugged — dark-first (UI spec §2)
object LymowColors {
    val bgBase = Color(0xFF0B0F0E)
    val surface1 = Color(0xFF15201D)
    val surface2 = Color(0xFF1E2C28)
    val hairline = Color(0xFF3A4A45)
    val textPrimary = Color(0xFFF2F7F5)
    val textSecondary = Color(0xFFA9B8B2)
    val textDisabled = Color(0xFF5C6A65)
    val connected = Color(0xFF22E06B)
    val connecting = Color(0xFFFFC02E)
    val disconnected = Color(0xFFFF3B30)
    val warning = Color(0xFFFF8A1E)
    val danger = Color(0xFFFF1E1E)
    val driveAccent = Color(0xFF3DD6FF)
    val hazard = Color(0xFFFFD400)
}

object Dimens {
    val minTarget = 56.dp
    val primaryButton = 64.dp
    val eStop = 72.dp
    val joystickGate = 300.dp
    val joystickKnob = 108.dp
    val screenMargin = 20.dp
    val cardRadius = 16.dp
}

private val DarkScheme = darkColorScheme(
    primary = LymowColors.connected,
    background = LymowColors.bgBase,
    surface = LymowColors.surface1,
    onPrimary = LymowColors.bgBase,
    onBackground = LymowColors.textPrimary,
    onSurface = LymowColors.textPrimary,
    error = LymowColors.danger,
)

private val LymowType = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun LymowTheme(content: @Composable () -> Unit) {
    // Dark default & recommended; we ignore system light per UI spec (outdoor use).
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, typography = LymowType, content = content)
}
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add androidApp/
git commit -m "feat(ui): Site Rugged theme tokens

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: Connect screen (scan + pick + permissions)

**Files:**
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/ui/connect/ConnectViewModel.kt`
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/ui/connect/ConnectScreen.kt`

Implements UI spec §4.1. No unit test (UI + platform); verified in Task 12.

- [ ] **Step 1: Implement `ConnectViewModel.kt`**

```kotlin
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
```

- [ ] **Step 2: Implement `ConnectScreen.kt`**

```kotlin
package id.au.james.lymow.ui.connect

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.au.james.lymow.transport.DiscoveredDevice
import id.au.james.lymow.ui.theme.Dimens
import id.au.james.lymow.ui.theme.LymowColors

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun ConnectScreen(
    onConnect: (DiscoveredDevice) -> Unit,
    onOpenSettings: () -> Unit,
    vm: ConnectViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.all { it }; if (granted) vm.startScan() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("LYMOW RC") }, actions = {
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }) },
        bottomBar = {
            Button(
                onClick = { if (granted) vm.toggleScan() else launcher.launch(blePermissions()) },
                modifier = Modifier.fillMaxWidth().padding(Dimens.screenMargin).height(Dimens.primaryButton),
            ) { Text(if (ui.scanning) "STOP SCAN" else "SCAN") }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = Dimens.screenMargin)) {
            Spacer(Modifier.height(12.dp))
            Text("Connect your mower", style = MaterialTheme.typography.headlineMedium)
            Text("Bring the phone near the mower and scan.",
                color = LymowColors.textSecondary)
            Spacer(Modifier.height(16.dp))
            if (ui.devices.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                    Text(
                        if (ui.scanning) "Scanning for Lymow_…" else "No mowers yet. Tap SCAN.",
                        color = LymowColors.textSecondary,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ui.devices, key = { it.id }) { d -> DeviceRow(d) { onConnect(d) } }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().height(Dimens.minTarget + 16.dp)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(device.name, style = MaterialTheme.typography.titleLarge)
                Text("${device.rssi} dBm", color = LymowColors.textSecondary)
            }
            Button(onClick = onClick) { Text("CONNECT") }
        }
    }
}
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add androidApp/
git commit -m "feat(ui): connect screen with scan, pick, and BLE permissions

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 10: Drive screen — joystick, source, safety wiring

**Files:**
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/input/TouchJoystickSource.kt`
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/ui/drive/VirtualJoystick.kt`
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/ui/drive/DriveViewModel.kt`
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/ui/drive/DriveScreen.kt`

Implements UI spec §4.2 + §5 + §6 (key behaviour; full visual polish iterates against the UI spec).

- [ ] **Step 1: Implement `TouchJoystickSource.kt`**

```kotlin
package id.au.james.lymow.input

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** The on-screen joystick as an InputSource. A USB-C gamepad source replaces this in Phase 2. */
class TouchJoystickSource : InputSource {
    private val _vector = MutableStateFlow(ControlVector.ZERO)
    override val vector: StateFlow<ControlVector> = _vector.asStateFlow()
    override val buttons: Flow<ButtonEvent> = emptyFlow()
    fun set(drive: Float, turn: Float) { _vector.value = ControlVector(drive, turn) }
}
```

- [ ] **Step 2: Implement `DriveViewModel.kt`** (throttle + immediate stop + lifecycle safety)

```kotlin
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
        val transport = AndroidMowerTransport(context, deviceId)
        val c = MowerController(transport, scope = viewModelScope)
        controller = c
        viewModelScope.launch { c.state.collect { _state.value = it } }
        viewModelScope.launch {
            input.vector.collect { v -> sendControl(v) }
        }
        viewModelScope.launch { runCatching { c.connect() } }
    }

    /** Called by the joystick on drag (drive/turn in [-1,1]) and on release (0,0). */
    fun onJoystick(drive: Float, turn: Float) = input.set(drive, turn)

    private fun sendControl(v: ControlVector) {
        val c = controller ?: return
        if (v.drive == 0f && v.turn == 0f) {           // release / centre → stop NOW
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

    fun disconnect() = onAppBackgrounded()

    val driving: Boolean get() = state.value.connection == ConnectionState.Connected
}
```

- [ ] **Step 3: Implement `VirtualJoystick.kt`** (gate/knob/deadzone, spring-return, stop-on-release)

```kotlin
package id.au.james.lymow.ui.drive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import id.au.james.lymow.ui.theme.Dimens
import id.au.james.lymow.ui.theme.LymowColors
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.launch

@Composable
fun VirtualJoystick(
    enabled: Boolean,
    onVector: (drive: Float, turn: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gatePx = with(density) { Dimens.joystickGate.toPx() }
    val knobRadiusPx = with(density) { (Dimens.joystickKnob / 2).toPx() }
    val travel = gatePx / 2f - knobRadiusPx
    val deadzone = travel * 0.15f
    val scope = rememberCoroutineScope()
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    fun emit(offset: Offset) {
        val dist = offset.getDistance()
        val clamped = if (dist > travel) offset * (travel / dist) else offset
        if (clamped.getDistance() <= deadzone) { onVector(0f, 0f); return }
        val drive = (-clamped.y / travel).coerceIn(-1f, 1f)
        val turn = (-clamped.x / travel).coerceIn(-1f, 1f) // +x (right) → negative turn (= turn right)
        onVector(drive, turn)
    }

    Canvas(
        modifier
            .size(Dimens.joystickGate)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { /* knob follows below */ },
                    onDragEnd = {
                        onVector(0f, 0f)                     // STOP immediately on release
                        scope.launch { knob.animateTo(Offset.Zero, tween(120)) }
                    },
                    onDragCancel = {
                        onVector(0f, 0f)
                        scope.launch { knob.animateTo(Offset.Zero, tween(120)) }
                    },
                ) { change, drag ->
                    change.consume()
                    val raw = knob.value + drag
                    val dist = raw.getDistance()
                    val clamped = if (dist > travel) raw * (travel / dist) else raw
                    scope.launch { knob.snapTo(clamped) }
                    emit(clamped)
                }
            }
    ) {
        val c = center
        // gate
        drawCircle(
            color = if (enabled) LymowColors.surface2 else LymowColors.surface2.copy(alpha = 0.4f),
            radius = gatePx / 2f, center = c,
        )
        drawCircle(
            color = if (enabled) LymowColors.hairline else LymowColors.textDisabled,
            radius = gatePx / 2f, center = c, style = Stroke(width = 2.dp.toPx()),
        )
        // active vector
        if (enabled && knob.value.getDistance() > deadzone) {
            drawLine(LymowColors.driveAccent, c, c + knob.value, strokeWidth = 4.dp.toPx())
        }
        // knob
        drawCircle(
            color = if (enabled) LymowColors.textPrimary else LymowColors.textDisabled,
            radius = knobRadiusPx, center = c + knob.value,
        )
    }
}
```

- [ ] **Step 4: Implement `DriveScreen.kt`** (status header, E-stop, magnitude readout, joystick, lifecycle stop)

```kotlin
package id.au.james.lymow.ui.drive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.au.james.lymow.control.ConnectionState
import id.au.james.lymow.ui.theme.Dimens
import id.au.james.lymow.ui.theme.LymowColors

@Composable
fun DriveScreen(
    deviceId: String,
    onDisconnected: () -> Unit,
    vm: DriveViewModel = viewModel(),
) {
    LaunchedEffect(deviceId) { vm.connect(deviceId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var drive by remember { mutableStateOf(0f) }
    var turn by remember { mutableStateOf(0f) }

    // Lifecycle safety: stop + disconnect when backgrounded.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onAppBackgrounded() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val connected = state.connection == ConnectionState.Connected
    val statusColor = when (state.connection) {
        ConnectionState.Connected -> LymowColors.connected
        ConnectionState.Connecting -> LymowColors.connecting
        else -> LymowColors.disconnected
    }

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(Dimens.screenMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        when (state.connection) {
                            ConnectionState.Connected -> "● CONNECTED"
                            ConnectionState.Connecting -> "◔ CONNECTING…"
                            ConnectionState.Error -> "● ERROR"
                            ConnectionState.Disconnected -> "● DISCONNECTED"
                        },
                        color = statusColor, style = MaterialTheme.typography.titleLarge,
                    )
                    Text("STATUS: ${state.telemetry?.statusName ?: "—"}",
                        color = LymowColors.textSecondary)
                }
                Text("${state.telemetry?.battery ?: "—"}%",
                    style = MaterialTheme.typography.headlineMedium)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.emergencyStop(); drive = 0f; turn = 0f },
                modifier = Modifier.align(Alignment.End).height(Dimens.eStop),
                colors = ButtonDefaults.buttonColors(containerColor = LymowColors.danger),
            ) { Text("E-STOP") }

            Spacer(Modifier.weight(1f))
            Text(
                "DRIVE %+.2f   TURN %+.2f".format(drive, turn),
                color = LymowColors.driveAccent, style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            VirtualJoystick(
                enabled = connected,
                onVector = { d, t -> drive = d; turn = t; vm.onJoystick(d, t) },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (connected) "release = stop" else "DISCONNECTED — not driving",
                color = if (connected) LymowColors.textSecondary else LymowColors.disconnected,
            )
            Spacer(Modifier.weight(1f))

            if (state.connection == ConnectionState.Disconnected) {
                // UI spec §6.1: alarming disconnect. Minimal v1: banner + reconnect.
                Surface(color = LymowColors.danger, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⚠ LINK LOST — mower may still be moving", color = Color.White)
                        TextButton(onClick = onDisconnected) { Text("BACK TO SCAN", color = Color.White) }
                    }
                }
            }
        }
    }
}
```
> v1 implements the functional core of the disconnect treatment (banner + "not driving" + reconnect). The pulsing edge border, haptic alarm pattern, and full state styling from UI spec §5–§7 are layered on during polish; they do not change the wiring above.

- [ ] **Step 5: Build to verify**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add androidApp/
git commit -m "feat(ui): drive screen, joystick, throttle + safe-stop wiring

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 11: Settings shell + navigation + app entry

**Files:**
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/ui/settings/SettingsScreen.kt`
- Create: `androidApp/src/main/kotlin/id/au/james/lymow/LymowApp.kt`
- Modify: `androidApp/src/main/kotlin/id/au/james/lymow/MainActivity.kt`

- [ ] **Step 1: Implement `SettingsScreen.kt`** (shell only — UI spec §4.4)

```kotlin
package id.au.james.lymow.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.au.james.lymow.ui.theme.LymowColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("SETTINGS") },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), Arrangement.spacedBy(12.dp)) {
            Text("CONTROLLER", color = LymowColors.textSecondary)
            ListItem(
                headlineContent = { Text("Gamepad / USB-C joystick") },
                supportingContent = { Text("Not connected — coming soon") },
            )
            Text("ABOUT", color = LymowColors.textSecondary)
            ListItem(headlineContent = { Text("App version") }, trailingContent = { Text("1.0.0") })
        }
    }
}
```

- [ ] **Step 2: Implement `LymowApp.kt`** (NavHost)

```kotlin
package id.au.james.lymow

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.au.james.lymow.ui.connect.ConnectScreen
import id.au.james.lymow.ui.drive.DriveScreen
import id.au.james.lymow.ui.settings.SettingsScreen

@Composable
fun LymowApp() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "connect") {
        composable("connect") {
            ConnectScreen(
                onConnect = { d -> nav.navigate("drive/${d.id}") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("drive/{deviceId}") { entry ->
            DriveScreen(
                deviceId = entry.arguments?.getString("deviceId").orEmpty(),
                onDisconnected = { nav.popBackStack("connect", inclusive = false) },
            )
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
```

- [ ] **Step 3: Replace `MainActivity.kt`**

```kotlin
package id.au.james.lymow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import id.au.james.lymow.ui.theme.LymowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LymowTheme { LymowApp() } }
    }
}
```

- [ ] **Step 4: Build the full APK**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

- [ ] **Step 5: Commit**

```bash
git add androidApp/
git commit -m "feat(ui): settings shell, navigation, app entry

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 12: Install and verify (emulator UI + physical-device driving)

No code. Verifies the build end-to-end. Real driving is a James-with-the-mower step.

- [ ] **Step 1: Verify all shared tests still pass**

Run: `./gradlew :shared:jvmTest`
Expected: PASS (all protocol + controller tests).

- [ ] **Step 2: Install on the emulator and smoke-test the UI**

Run:
```bash
"$HOME/Library/Android/sdk/emulator/emulator" -avd lymow-mitm -no-snapshot -netdelay none &
"$HOME/Library/Android/sdk/platform-tools/adb" wait-for-device
./gradlew :androidApp:installDebug
"$HOME/Library/Android/sdk/platform-tools/adb" shell am start -n id.au.james.lymow/.MainActivity
```
Expected: app launches to the Connect screen; tapping SCAN shows the permission prompt; Settings opens. **No real devices appear (emulator has no BLE) — that is expected.**

- [ ] **Step 3: Drive the real mower (physical phone — manual, James present)**

> Safety: mower powered on, on open ground, clear of people/pets/obstacles; be ready to release the joystick (stop) or hit E-STOP.

1. Plug in the Android 15 phone with USB debugging enabled; confirm `adb devices` lists it.
2. `./gradlew :androidApp:installDebug` then launch the app.
3. Grant Bluetooth permissions; tap SCAN; the `Lymow_*` mower appears; tap CONNECT.
4. Header shows CONNECTED + battery % + `remote_control` status.
5. Gentle joystick pushes drive the mower; **releasing stops it**; backgrounding the app stops + disconnects; E-STOP stops it.
6. Confirm a keepalive holds the session for >10s idle without dropping.

- [ ] **Step 4: Tag the working build**

```bash
git tag v1.0.0-drive
```

---

## Self-review

**Spec coverage** (against `2026-06-04-lymow-rc-app-design.md`):
- §3 protocol port → Tasks 1–4 (golden-tested). ✓
- §4 controller + keepalive + safe stop + speed-profile hook + FakeTransport tests → Task 6. ✓
- §5 Android BLE scan/connect/notify/write-no-response + permissions → Tasks 7, 9 (+ manifest in Task 0). ✓
- §6 three screens, Site Rugged theme, joystick stop-on-pointer-up, ~50ms throttle, lifecycle + disconnect safety → Tasks 8–11. ✓
- §7 `InputSource` seam + `TouchJoystickSource` (USB/`MowerAction`/config deferred) → Tasks 5, 10. ✓
- §8 SDK setup, build, install, verification boundary → Tasks 0, 12. ✓
- §9 scope: Settings shell only, no blade/deck/start-mow → Task 11; not implemented elsewhere. ✓

**Placeholder scan:** No TODO/TBD steps; every code step shows complete code; golden bytes are concrete. The only deliberately-deferred items (pulsing-edge/haptic disconnect polish, SLOW/TURBO profiles, USB gamepad) are explicitly Phase 2 per the spec, not gaps in v1.

**Type consistency:** `MowerTransport` (`connect/disconnect/startNotify/stopNotify/write`) is implemented identically by `FakeTransport` (Task 6) and `AndroidMowerTransport` (Task 7). `MowerController(transport, scope, clientId, keepaliveIntervalMs)` is constructed the same way in tests (Task 6) and `DriveViewModel` (Task 10). `controller.drive` takes normalised `[-1,1]`; the joystick (Task 10) and tests (Task 6) both feed normalised values; the scaling-to-limits lives only in the controller. `InputSource.vector: StateFlow<ControlVector>` produced by `TouchJoystickSource` and consumed by `DriveViewModel`. Names align across tasks.

> **Known execution risk:** exact AGP/Kotlin/Compose/AndroidX versions (Task 0) may need a minor bump to a mutually-compatible set on first build; align via the official compatibility matrix without lowering `compileSdk` below 35. This does not affect any application code above.
