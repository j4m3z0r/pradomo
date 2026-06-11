@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pradomo.ui.drive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.positionChange
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
import com.pradomo.control.ControllerModeGroup
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
import kotlin.math.abs
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
    val modeGroup by vm.modeGroup.collectAsStateWithLifecycle()
    val modeSelecting by vm.modeSelecting.collectAsStateWithLifecycle()
    val maneuverLabel by vm.maneuverLabel.collectAsStateWithLifecycle()
    val cuttingWidthMm by vm.cuttingWidthMm.collectAsStateWithLifecycle()
    val rowOverlapMm by vm.rowOverlapMm.collectAsStateWithLifecycle()
    val cruiseCorrection by vm.cruiseCorrection.collectAsStateWithLifecycle()
    val turnRadiusMm by vm.turnRadiusMm.collectAsStateWithLifecycle()
    val headingAssist by vm.headingAssist.collectAsStateWithLifecycle()

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
                    modeGroup, modeSelecting, maneuverLabel,
                    onJoystick = vm::onJoystick,
                    onEmergencyStop = vm::emergencyStop,
                    onMode = vm::setSpeedMode,
                    onModeGroup = vm::setModeGroup,
                    onEdgeTap = { zone ->
                        when (zone) {
                            EdgeZone.LEFT -> vm.touchUTurn(left = true)
                            EdgeZone.RIGHT -> vm.touchUTurn(left = false)
                            EdgeZone.UP -> vm.touchCruise(forward = true)
                            EdgeZone.DOWN -> vm.touchCruise(forward = false)
                        }
                    },
                    onBlade = vm::setBlade,
                    onHeight = vm::setHeightMm,
                    onEnterPocket = { pocketMode = true },
                    onBackToScan = onDisconnected,
                )
                Tab.MAP -> MapView(pad, trail, state.telemetry, onClear = vm::clearMap)
                Tab.SETTINGS -> SettingsTab(
                    pad, gamepad, topButton, bottomButton, smoothEnabled, smoothLevel,
                    cuttingWidthMm, rowOverlapMm, cruiseCorrection, turnRadiusMm, headingAssist,
                    onTop = vm::setTopButton, onBottom = vm::setBottomButton,
                    onSmooth = vm::setSmoothEnabled,
                    onSmoothLevel = vm::setSmoothLevel,
                    onCuttingWidth = vm::setCuttingWidthMm,
                    onRowOverlap = vm::setRowOverlapMm,
                    onCruiseCorrection = vm::setCruiseCorrection,
                    onTurnRadius = vm::setTurnRadiusMm,
                    onHeadingAssist = vm::setHeadingAssist,
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
    modeGroup: ControllerModeGroup,
    modeSelecting: Boolean,
    maneuverLabel: String?,
    onJoystick: (Float, Float) -> Unit,
    onEmergencyStop: () -> Unit,
    onMode: (SpeedMode) -> Unit,
    onModeGroup: (ControllerModeGroup) -> Unit,
    onEdgeTap: (EdgeZone) -> Unit,
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

        if (maneuverLabel != null) RunningManeuverBanner(maneuverLabel)

        DriveCard(
            connected, control, speedMode, joySize, modeGroup, modeSelecting,
            onJoystick, onEmergencyStop, onMode, onModeGroup, onEdgeTap,
        )

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

/** Slim banner shown while a semi-autonomous maneuver is running, with how to bail out. */
@Composable
private fun RunningManeuverBanner(label: String) {
    Surface(color = PradomoColors.mossDeep, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("▶ $label", color = PradomoColors.textPrimary,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("touch stick to cancel", color = PradomoColors.textSecondary,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The mode selector: a 2-page swipe in place of the old speed row. Page 0 = Slow/Normal/
 * Turbo chips; page 1 = the Auto legend. Swiping (or the gamepad both-buttons+◀▶ chord,
 * which moves [group]) switches the active group. A "MODE ●○" dot row shows the page.
 */
@Composable
private fun ModePager(
    group: ControllerModeGroup,
    selecting: Boolean,
    speedMode: SpeedMode,
    connected: Boolean,
    onMode: (SpeedMode) -> Unit,
    onModeGroup: (ControllerModeGroup) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MODE", color = PradomoColors.textSecondary,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            // Tappable dots (swipe also works); tapping a dot jumps to that group.
            ControllerModeGroup.entries.forEach { g ->
                val on = g == group
                Box(
                    Modifier.size(22.dp).clickable { onModeGroup(g) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(if (on) 9.dp else 6.dp).background(
                            if (on) PradomoColors.connected else PradomoColors.textSecondary,
                            RoundedCornerShape(50),
                        ),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(if (selecting) "switching…" else "tap ●○ · swipe ◀▶", color = PradomoColors.textSecondary,
                style = MaterialTheme.typography.labelSmall)
        }
        // Horizontal swipe across the strip switches group. A HorizontalPager (or
        // detectHorizontalDragGestures) loses the drag here because the SegmentedButtons
        // consume the down; intercept with requireUnconsumed=false and only claim the
        // gesture once it's clearly a horizontal drag, so chip taps still register.
        Box(
            Modifier.fillMaxWidth().pointerInput(group) {
                val slop = 10.dp.toPx()
                val threshold = 36.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var accum = 0f
                    var claimed = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        accum += change.positionChange().x
                        if (!claimed && abs(accum) > slop) claimed = true
                        if (claimed) change.consume()
                        if (!change.pressed) break
                    }
                    if (accum <= -threshold) onModeGroup(group.next())
                    else if (accum >= threshold) onModeGroup(group.prev())
                }
            },
        ) {
            when (group) {
                ControllerModeGroup.SPEED -> SpeedSegmented(speedMode, connected, onMode)
                ControllerModeGroup.AUTO -> AutoLegend()
            }
        }
    }
}

/** The Auto-mode page: explains the joystick-edge maneuver taps. */
@Composable
private fun AutoLegend() {
    Surface(
        color = PradomoColors.surface2, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("AUTO — tap the joystick edge", color = PradomoColors.connected,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text("◀ ▶  K-turn into the next row     ▲ ▼  cruise control",
                color = PradomoColors.textPrimary, style = MaterialTheme.typography.bodyMedium)
        }
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
    modeGroup: ControllerModeGroup,
    modeSelecting: Boolean,
    onJoystick: (Float, Float) -> Unit,
    onEmergencyStop: () -> Unit,
    onMode: (SpeedMode) -> Unit,
    onModeGroup: (ControllerModeGroup) -> Unit,
    onEdgeTap: (EdgeZone) -> Unit,
) {
    val auto = modeGroup == ControllerModeGroup.AUTO
    Card(colors = CardDefaults.cardColors(containerColor = PradomoColors.surface1)) {
        Column(Modifier.padding(16.dp)) {
            ModePager(modeGroup, modeSelecting, speedMode, connected, onMode, onModeGroup)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                VirtualJoystick(
                    enabled = connected, drive = control.drive, turn = control.turn,
                    onVector = onJoystick, gateSize = joySize,
                    autoMode = auto, onEdgeTap = onEdgeTap,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !connected -> "NOT CONNECTED"
                    auto -> "TAP EDGE FOR MANEUVER · DRAG TO DRIVE"
                    else -> "RELEASE TO STOP"
                },
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
    cuttingWidthMm: Int,
    rowOverlapMm: Int,
    cruiseCorrection: Boolean,
    turnRadiusMm: Int,
    headingAssist: Boolean,
    onTop: (ButtonAction) -> Unit,
    onBottom: (ButtonAction) -> Unit,
    onSmooth: (Boolean) -> Unit,
    onSmoothLevel: (SmoothLevel) -> Unit,
    onCuttingWidth: (Int) -> Unit,
    onRowOverlap: (Int) -> Unit,
    onCruiseCorrection: (Boolean) -> Unit,
    onTurnRadius: (Int) -> Unit,
    onHeadingAssist: (Boolean) -> Unit,
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
                Text("In Speed mode, hold a button to momentarily change speed. Hold both buttons and push the stick ◀▶ to switch to Auto mode.",
                    color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }

        SectionHeader("AUTO MANEUVERS")
        Card(colors = CardDefaults.cardColors(containerColor = PradomoColors.surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val pitch = (cuttingWidthMm - rowOverlapMm).coerceAtLeast(20)
                Text("In Auto mode: gamepad — hold the big button and push the stick ◀▶ (K-turn) or ▲▼ (cruise). Touch — single-tap the joystick's edge.",
                    color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                StepperRow("Cutting width", "$cuttingWidthMm mm",
                    onMinus = { onCuttingWidth((cuttingWidthMm - 10).coerceIn(100, 600)) },
                    onPlus = { onCuttingWidth((cuttingWidthMm + 10).coerceIn(100, 600)) })
                StepperRow("Row overlap", "$rowOverlapMm mm",
                    onMinus = { onRowOverlap((rowOverlapMm - 5).coerceIn(0, 150)) },
                    onPlus = { onRowOverlap((rowOverlapMm + 5).coerceIn(0, 150)) })
                StepperRow("Turn radius", "$turnRadiusMm mm",
                    onMinus = { onTurnRadius((turnRadiusMm - 25).coerceIn(250, 700)) },
                    onPlus = { onTurnRadius((turnRadiusMm + 25).coerceIn(250, 700)) })
                Text("Row spacing (pitch): $pitch mm",
                    color = PradomoColors.textPrimary, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold)
                Text("Wider turn radius keeps both treads rolling through the K-turn (gentler on grass) but backs up deeper into the mowed row.",
                    color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text("⚠ Set cutting width to your mower's real value — it drives how far each K-turn shifts over. Validate on the mower before relying on it; the link has no watchdog, so a maneuver only stops on stick touch, E-STOP, or going out of range.",
                    color = PradomoColors.warning, style = MaterialTheme.typography.labelSmall)
            }
        }

        SectionHeader("STEERING ASSIST")
        Card(colors = CardDefaults.cardColors(containerColor = PradomoColors.surface1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AssistSwitchRow(
                    title = "Auto-correct cruise heading",
                    body = "Hold a straight line in cruise using telemetry — the mower can't track straight on its own. You can always steer with the stick; this adds an automatic hold on top.",
                    checked = cruiseCorrection, onChange = onCruiseCorrection,
                )
                AssistSwitchRow(
                    title = "Heading hold (manual driving)",
                    body = "Experimental: while you drive with the stick straight, keep pointing the same way (counters slopes and tread slip). Steering releases it instantly.",
                    checked = headingAssist, onChange = onHeadingAssist,
                )
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
private fun AssistSwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PradomoColors.textPrimary,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, color = PradomoColors.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PradomoColors.bgBase,
                checkedTrackColor = PradomoColors.connected,
            ),
        )
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PradomoColors.textPrimary, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = onMinus, modifier = Modifier.size(42.dp),
            contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 20.sp) }
        Text(value, color = PradomoColors.textPrimary, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.width(84.dp))
        FilledTonalButton(onClick = onPlus, modifier = Modifier.size(42.dp),
            contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 20.sp) }
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
