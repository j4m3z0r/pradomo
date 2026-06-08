package com.pradomo.ui.connect

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pradomo.ble.DEMO_DEVICE_ID
import com.pradomo.input.UsbGamepadSource
import com.pradomo.transport.DiscoveredDevice
import com.pradomo.ui.theme.Dimens
import com.pradomo.ui.theme.PradomoColors

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@OptIn(ExperimentalMaterial3Api::class)
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
        topBar = { TopAppBar(title = { Text("Pradomo") }, actions = {
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
                color = PradomoColors.textSecondary)
            TextButton(onClick = {
                onConnect(DiscoveredDevice(DEMO_DEVICE_ID, "Demo Mower", -50))
            }) { Text("Try demo mode (no mower needed)") }
            Spacer(Modifier.height(8.dp))
            GamepadCheckCard()
            if (ui.devices.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                    Text(
                        if (ui.scanning) "Scanning for Lymow_…" else "No mowers yet. Tap SCAN.",
                        color = PradomoColors.textSecondary,
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

/**
 * Live gamepad readout so the stick mapping can be checked here — before
 * connecting to the mower, where the stick would actually drive it. Only shown
 * once a gamepad has sent events.
 */
@Composable
private fun GamepadCheckCard() {
    val name by UsbGamepadSource.deviceName.collectAsStateWithLifecycle()
    val v by UsbGamepadSource.vector.collectAsStateWithLifecycle()
    val gamepad = name ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🎮 $gamepad", style = MaterialTheme.typography.titleLarge)
            Text("DRIVE %+.2f   TURN %+.2f".format(v.drive, v.turn),
                color = PradomoColors.driveAccent, style = MaterialTheme.typography.titleLarge)
            Text("Push the stick to check: up = +DRIVE, right = −TURN. Safe here (not connected).",
                color = PradomoColors.textSecondary)
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().height(Dimens.minTarget + 16.dp)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${device.rssi} dBm", color = PradomoColors.textSecondary, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onClick) { Text("CONNECT", maxLines = 1, softWrap = false) }
        }
    }
}
