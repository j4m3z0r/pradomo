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
