package com.pradomo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Turf — grass/forest palette, dark-first, sun-readable (≥7:1 on key text).
// Warm very-dark green background (nothing in nature is pure black).
object PradomoColors {
    val bgBase = Color(0xFF121A12)       // dark forest charcoal
    val surface1 = Color(0xFF1B261A)     // cards / app bars / list rows
    val surface2 = Color(0xFF273524)     // raised chips / wells
    val hairline = Color(0xFF45563E)     // 1dp borders / dividers
    val textPrimary = Color(0xFFECF3E4)  // warm off-white
    val textSecondary = Color(0xFFA9B89C)
    val textDisabled = Color(0xFF66745A)
    val connected = Color(0xFF5BC465)    // fresh grass green — "go"
    val mossDeep = Color(0xFF2D5E33)     // deep moss (primary container / selected fills)
    val connecting = Color(0xFFE7B23C)   // amber
    val disconnected = Color(0xFFFF6257) // warm coral red
    val warning = Color(0xFFF2A33D)      // orange
    val danger = Color(0xFFFF4438)       // alarm red
    val driveAccent = Color(0xFF58C8E0)  // teal — off the green axis, never reads as status
    val hazard = Color(0xFFF4D03F)
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
    primary = PradomoColors.connected,
    onPrimary = Color(0xFF06140A),
    primaryContainer = PradomoColors.mossDeep,
    onPrimaryContainer = Color(0xFFCDE7CF),
    secondary = PradomoColors.driveAccent,
    onSecondary = Color(0xFF04181D),
    secondaryContainer = Color(0xFF1E3A41),
    onSecondaryContainer = Color(0xFFBDE8F2),
    background = PradomoColors.bgBase,
    onBackground = PradomoColors.textPrimary,
    surface = PradomoColors.surface1,
    onSurface = PradomoColors.textPrimary,
    surfaceVariant = PradomoColors.surface2,
    onSurfaceVariant = PradomoColors.textSecondary,
    outline = PradomoColors.hairline,
    outlineVariant = Color(0xFF33402D),
    error = PradomoColors.danger,
    onError = Color(0xFF1A0502),
)

private val PradomoType = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

// Dark is the default & recommended scheme (outdoor/sunlight use); we intentionally
// do not follow the system light/dark setting.
@Composable
fun PradomoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = PradomoType, content = content)
}
