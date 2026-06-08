package com.pradomo.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pradomo.ui.theme.PradomoColors

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
            Text("CONTROLLER", color = PradomoColors.textSecondary)
            ListItem(
                headlineContent = { Text("Gamepad / USB-C joystick") },
                supportingContent = { Text("Not connected — coming soon") },
            )
            Text("ABOUT", color = PradomoColors.textSecondary)
            ListItem(headlineContent = { Text("App version") }, trailingContent = { Text("1.0.0") })
        }
    }
}
