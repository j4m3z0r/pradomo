package com.pradomo

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pradomo.ui.connect.ConnectScreen
import com.pradomo.ui.drive.DriveScreen
import com.pradomo.ui.settings.SettingsScreen

@Composable
fun PradomoApp() {
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
