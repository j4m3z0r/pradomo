@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pradomo.ui.drive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Toys
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pradomo.control.ButtonAction
import com.pradomo.control.ConnectionState
import com.pradomo.control.SmoothLevel
import com.pradomo.control.SpeedMode
import com.pradomo.input.UsbGamepadSource
import com.pradomo.ui.map.MapView
import com.pradomo.protocol.BladeSpeed
import com.pradomo.protocol.DeckHeights
import com.pradomo.protocol.Telemetry
import com.pradomo.ui.theme.PradomoColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Tab(val label: String, val icon: ImageVector) {
    DRIVE("Drive", Icons.Outlined.SportsEsports),
    MAP("Map", Icons.Outlined.Map),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun DriveScreen(
    deviceId: String,
    onDisconnected: () -> Unit,
    vm: DriveViewModel = viewModel(),
) {
    LaunchedEffect(deviceId) { vm.connect(deviceId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val control by vm.control.collectAsStateWithLifecycle()
    val deck by vm.deck.collectAsStateWithLifecycle()
    val speedMode by vm.speedMode.collectAsStateWithLifecycle()
    val gamepad by UsbGamepadSource.deviceName.collectAsStateWithLifecycle()
    val topButton by vm.topButton.collectAsStateWithLifecycle()
    val bottomButton by vm.bottomButton.collectAsStateWithLifecycle()
    val smoothEnabled by vm.smoothEnabled.collectAsStateWithLifecycle()
    val smoothLevel by vm.smoothLevel.collectAsStateWithLifecycle()
    val trail by vm.trail.collectAsStateWithLifecycle()

    var pocketMode by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.DRIVE) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onAppBackgrounded() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler { vm.onAppBackgrounded(); onDisconnected() }

    val connected = state.connection == ConnectionState.Connected
    val linkLost = state.connection == ConnectionState.Disconnected ||
        state.connection == ConnectionState.Error

    LaunchedEffect(gamepad, linkLost) {
        if (pocketMode && (gamepad == null || linkLost)) pocketMode = false
    }

    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(pocketMode) {
        val activity = context.findActivity()
        val insets = activity?.window?.let { WindowInsetsControllerCompat(it, view) }
        if (pocketMode) {
            view.keepScreenOn = true
            activity?.let { setScreenBrightness(it, 0.02f) }
            if (Build.VERSION.SDK_INT >= 27) activity?.setShowWhenLocked(true)
            insets?.apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            view.keepScreenOn = false
            activity?.let { setScreenBrightness(it, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
            if (Build.VERSION.SDK_INT >= 27) activity?.setShowWhenLocked(false)
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = PradomoColors.surface1) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PradomoColors.connected,
                                selectedTextColor = PradomoColors.connected,
                                indicatorColor = PradomoColors.surface2,
                                unselectedIconColor = PradomoColors.textSecondary,
                                unselectedTextColor = PradomoColors.textSecondary,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            when (tab) {
                Tab.DRIVE -> DriveTab(
                    pad, state.connection, state.telemetry, control, deck, speedMode,
                    connected, linkLost, gamepad != null,
                    onJoystick = vm::onJoystick,
                    onEmergencyStop = vm::emergencyStop,
                    onMode = vm::setSpeedMode,
                    onBlade = vm::setBlade,
                    onHeight = vm::setHeightMm,
                    onEnterPocket = { pocketMode = true },
                    onBackToScan = onDisconnected,
                )
                Tab.MAP -> MapView(pad, trail, state.telemetry, onClear = vm::clearMap)
                Tab.SETTINGS -> SettingsTab(
                    pad, gamepad, topButton, bottomButton, smoothEnabled, smoothLevel,
                    onTop = vm::setTopButton, onBottom = vm::setBottomButton,
                    onSmooth = vm::setSmoothEnabled,
                    onSmoothLevel = vm::setSmoothLevel,
                )
            }
        }
        if (pocketMode) PocketOverlay(blade = deck.blade, onExit = { pocketMode = false })
    }
}

@Composable
private fun DriveTab(
    pad: PaddingValues,
    connection: ConnectionState,
    telemetry: Telemetry?,
    control: com.pradomo.input.ControlVector,
    deck: DeckBladeUi,
    speedMode: SpeedMode,
    connected: Boolean,
    linkLost: Boolean,
    hasGamepad: Boolean,
    onJoystick: (Float, Float) -> Unit,
    onEmergencyStop: () -> Unit,
    onMode: (SpeedMode) -> Unit,
    onBlade: (BladeSpeed) -> Unit,
    onHeight: (Int) -> Unit,
    onEnterPocket: () -> Unit,
    onBackToScan: () -> Unit,
) {
    val joySize = (LocalConfiguration.current.screenWidthDp - 96).coerceIn(220, 300).dp
    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        StatusHeader(connection, telemetry?.battery, telemetry?.rssi)

        if (linkLost) {
            Surface(color = PradomoColors.danger, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("⚠ LINK LOST — mower may still be moving", color = Color.White, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onBackToScan) { Text("BACK TO SCAN", color = Color.White) }
                }
            }
        }

        DriveCard(connected, control, speedMode, joySize, onJoystick, onEmergencyStop, onMode)

        // Blade + deck slider cards
        val blades = BladeSpeed.entries
        val bladeOn = deck.blade != BladeSpeed.OFF
        ControlSliderCard(
            icon = Icons.Outlined.Toys, label = "BLADE SPEED",
            valueText = deck.blade.label, valueSub = null,
            valueColor = if (bladeOn) PradomoColors.connected else PradomoColors.textPrimary,
            index = blades.indexOf(deck.blade).coerceAtLeast(0), count = blades.size,
            minLabel = "Off", maxLabel = "Turbo", enabled = connected,
            accent = PradomoColors.connected,
            onIndex = { onBlade(blades[it]) },
        )
        val mm = DeckHeights.MM
        ControlSliderCard(
            icon = Icons.Outlined.Height, label = "DECK HEIGHT",
            valueText = "${deck.heightMm} mm", valueSub = null, valueColor = PradomoColors.textPrimary,
            index = mm.indexOf(deck.heightMm).coerceAtLeast(0), count = mm.size,
            minLabel = "${mm.first()} mm", maxLabel = "${mm.last()} mm", enabled = connected,
            accent = PradomoColors.connected, onIndex = { onHeight(mm[it]) },
        )

        OutlinedButton(
            onClick = onEnterPocket,
            enabled = connected && hasGamepad,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (hasGamepad) "POCKET MODE — blank screen, drive by remote" else "POCKET MODE (plug in the remote)",
                maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusHeader(connection: ConnectionState, battery: Int?, rssi: Int?) {
    val (label, color) = when (connection) {
        ConnectionState.Connected -> "CONNECTED" to PradomoColors.connected
        ConnectionState.Connecting -> "CONNECTING…" to PradomoColors.connecting
        ConnectionState.Error -> "ERROR" to PradomoColors.disconnected
        ConnectionState.Disconnected -> "DISCONNECTED" to PradomoColors.disconnected
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        val sigColor = when {
            rssi == null -> PradomoColors.textSecondary
            rssi > -60 -> PradomoColors.connected
            rssi > -75 -> PradomoColors.warning
            else -> PradomoColors.disconnected
        }
        Icon(Icons.Outlined.SignalCellularAlt, "signal", tint = sigColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(3.dp))
        Text(rssi?.let { "$it" } ?: "—", color = sigColor, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(16.dp))
        val battColor = if (battery != null && battery <= 15) PradomoColors.warning else PradomoColors.connected
        Icon(Icons.Outlined.BatteryFull, "battery", tint = battColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(3.dp))
        Text(battery?.let { "$it%" } ?: "—", color = battColor,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DriveCard(
    connected: Boolean,
    control: com.pradomo.input.ControlVector,
    speedMode: SpeedMode,
    joySize: androidx.compose.ui.unit.Dp,
    onJoystick: (Float, Float) -> Unit,
    onEmergencyStop: () -> Unit,
    onMode: (SpeedMode) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = PradomoColors.surface1)) {
        Column(Modifier.padding(16.dp)) {
            SpeedSegmented(speedMode, connected, onMode)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                VirtualJoystick(
                    enabled = connected, drive = control.drive, turn = control.turn,
                    onVector = onJoystick, gateSize = joySize,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(if (connected) "RELEASE TO STOP" else "NOT CONNECTED",
                color = PradomoColors.textSecondary, modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onEmergencyStop,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PradomoColors.danger),
            ) {
                Icon(Icons.Outlined.Block, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("EMERGENCY STOP", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SpeedSegmented(mode: SpeedMode, enabled: Boolean, onMode: (SpeedMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SpeedMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onMode(m) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, SpeedMode.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = PradomoColors.mossDeep,
                    activeContentColor = PradomoColors.textPrimary,
                ),
                icon = {},
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    Text("${m.maxLinear}", style = MaterialTheme.typography.labelSmall,
                        color = PradomoColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ControlSliderCard(
    icon: ImageVector, label: String, valueText: String, valueSub: String?, valueColor: Color,
    index: Int, count: Int, minLabel: String, maxLabel: String, enabled: Boolean,
    accent: Color, onIndex: (Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = PradomoColors.surface1)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, color = PradomoColors.textPrimary, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(valueText, color = valueColor, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    valueSub?.let { Text(it, color = accent, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Slider(
                value = index.toFloat(),
                onValueChange = { onIndex(it.roundToInt().coerceIn(0, count - 1)) },
                valueRange = 0f..(count - 1).toFloat(),
                steps = (count - 2).coerceAtLeast(0),
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(minLabel, color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(maxLabel, color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SettingsTab(
    pad: PaddingValues,
    gamepadName: String?,
    topButton: ButtonAction,
    bottomButton: ButtonAction,
    smoothEnabled: Boolean,
    smoothLevel: SmoothLevel,
    onTop: (ButtonAction) -> Unit,
    onBottom: (ButtonAction) -> Unit,
    onSmooth: (Boolean) -> Unit,
    onSmoothLevel: (SmoothLevel) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("CONTROLLER BUTTONS")
        Card(colors = CardDefaults.cardColors(containerColor = PradomoColors.surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    gamepadName?.let { "🎮 $it connected" } ?: "No controller connected — plug in via USB-C",
                    color = if (gamepadName != null) PradomoColors.connected else PradomoColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Top button (small)", color = PradomoColors.textPrimary,
                    style = MaterialTheme.typography.labelLarge)
                ButtonActionSelector(topButton, onTop)
                Text("Bottom button (large)", color = PradomoColors.textPrimary,
                    style = MaterialTheme.typography.labelLarge)
                ButtonActionSelector(bottomButton, onBottom)
                Text("Hold a button to momentarily change speed while driving.",
                    color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }

        SectionHeader("GRASS CARE")
        Card(colors = CardDefaults.cardColors(containerColor = PradomoColors.surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Controlled deceleration", color = PradomoColors.textPrimary,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Ease into motion and coast to a stop instead of jerking — gentler on the turf. E-STOP always stops instantly.",
                            color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = smoothEnabled, onCheckedChange = onSmooth,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PradomoColors.bgBase,
                            checkedTrackColor = PradomoColors.connected,
                        ),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SmoothLevel.entries.forEachIndexed { i, lvl ->
                        SegmentedButton(
                            selected = smoothLevel == lvl,
                            onClick = { onSmoothLevel(lvl) },
                            enabled = smoothEnabled,
                            shape = SegmentedButtonDefaults.itemShape(i, SmoothLevel.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = PradomoColors.mossDeep,
                                activeContentColor = PradomoColors.textPrimary,
                            ),
                            icon = {},
                        ) { Text(lvl.label, maxLines = 1) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ButtonActionSelector(selected: ButtonAction, onSelect: (ButtonAction) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ButtonAction.entries.forEachIndexed { i, a ->
            SegmentedButton(
                selected = selected == a,
                onClick = { onSelect(a) },
                shape = SegmentedButtonDefaults.itemShape(i, ButtonAction.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = PradomoColors.mossDeep,
                    activeContentColor = PradomoColors.textPrimary,
                ),
                icon = {},
            ) { Text(a.short, maxLines = 1) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun PlaceholderTab(pad: PaddingValues, icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(pad).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = PradomoColors.connected, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = PradomoColors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(body, color = PradomoColors.textSecondary, textAlign = TextAlign.Center)
    }
}

private const val EXIT_HOLD_SECONDS = 5

@Composable
private fun PocketOverlay(blade: BladeSpeed, onExit: () -> Unit) {
    var holdRemaining by remember { mutableStateOf<Int?>(null) }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(onPress = {
                coroutineScope {
                    holdRemaining = EXIT_HOLD_SECONDS
                    val ticker = launch {
                        repeat(EXIT_HOLD_SECONDS) { delay(1000); holdRemaining = EXIT_HOLD_SECONDS - (it + 1) }
                        holdRemaining = null; onExit()
                    }
                    tryAwaitRelease(); ticker.cancel(); holdRemaining = null
                }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        val warn = if (blade != BladeSpeed.OFF) "⚠ BLADES ${blade.label.uppercase()}\n\n" else ""
        val remaining = holdRemaining
        if (remaining != null) {
            Text("KEEP HOLDING…\n$remaining", color = Color(0xFFCCCCCC), textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold, fontSize = 40.sp)
        } else {
            Text("${warn}POCKET MODE\ndriving via remote\n\nHold the screen for ${EXIT_HOLD_SECONDS}s to exit",
                color = if (blade != BladeSpeed.OFF) Color(0xFF3A1010) else Color(0xFF2A2A2A),
                textAlign = TextAlign.Center)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private fun setScreenBrightness(activity: Activity, value: Float) {
    val lp = activity.window.attributes
    lp.screenBrightness = value
    activity.window.attributes = lp
}
