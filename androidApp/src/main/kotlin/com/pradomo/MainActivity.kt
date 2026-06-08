package com.pradomo

import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pradomo.input.UsbGamepadSource
import com.pradomo.ui.theme.PradomoTheme

class MainActivity : ComponentActivity() {
    private val inputManager: InputManager? by lazy { getSystemService(InputManager::class.java) }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshGamepad()
        override fun onInputDeviceChanged(deviceId: Int) = refreshGamepad()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepad()
    }

    /** Scan for a connected gamepad/joystick so the UI notices hot-plug/unplug immediately. */
    private fun refreshGamepad() {
        var name: String? = null
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.supportsSource(InputDevice.SOURCE_JOYSTICK) ||
                d.supportsSource(InputDevice.SOURCE_GAMEPAD)
            ) {
                name = d.name
                break
            }
        }
        if (name == null) UsbGamepadSource.onDeviceRemoved() // none present -> clear + stop
        else UsbGamepadSource.setDeviceName(name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PradomoTheme { PradomoApp() } }
    }

    override fun onResume() {
        super.onResume()
        inputManager?.registerInputDeviceListener(deviceListener, null)
        refreshGamepad()
    }

    override fun onPause() {
        super.onPause()
        inputManager?.unregisterInputDeviceListener(deviceListener)
    }

    // Intercept joystick motion before Compose so gamepad axes always reach the source.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (UsbGamepadSource.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // Swallow ALL key events from the joystick/gamepad device. Its buttons are
    // unused in v1 (mapping is Phase 2), and left alone they trigger app/system
    // navigation (back, activating the focused button) — which must not happen
    // mid-drive. Keys from other devices (volume, etc.) pass through untouched.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device
        if (dev != null &&
            (dev.supportsSource(InputDevice.SOURCE_JOYSTICK) ||
                dev.supportsSource(InputDevice.SOURCE_GAMEPAD))
        ) {
            // Route gamepad buttons to the input source (momentary speed overrides etc.).
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) UsbGamepadSource.onButton(event.keyCode, true)
                KeyEvent.ACTION_UP -> UsbGamepadSource.onButton(event.keyCode, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
