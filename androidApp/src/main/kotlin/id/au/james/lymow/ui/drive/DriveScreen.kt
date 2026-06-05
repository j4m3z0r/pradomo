package id.au.james.lymow.ui.drive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onAppBackgrounded() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val connected = state.connection == ConnectionState.Connected
    val linkLost = state.connection == ConnectionState.Disconnected ||
        state.connection == ConnectionState.Error
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

            if (linkLost) {
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
