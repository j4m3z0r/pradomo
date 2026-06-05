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
