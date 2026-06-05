package id.au.james.lymow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Site Rugged — dark-first (UI spec section 2)
object LymowColors {
    val bgBase = Color(0xFF0B0F0E)
    val surface1 = Color(0xFF15201D)
    val surface2 = Color(0xFF1E2C28)
    val hairline = Color(0xFF3A4A45)
    val textPrimary = Color(0xFFF2F7F5)
    val textSecondary = Color(0xFFA9B8B2)
    val textDisabled = Color(0xFF5C6A65)
    val connected = Color(0xFF22E06B)
    val connecting = Color(0xFFFFC02E)
    val disconnected = Color(0xFFFF3B30)
    val warning = Color(0xFFFF8A1E)
    val danger = Color(0xFFFF1E1E)
    val driveAccent = Color(0xFF3DD6FF)
    val hazard = Color(0xFFFFD400)
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
    primary = LymowColors.connected,
    background = LymowColors.bgBase,
    surface = LymowColors.surface1,
    onPrimary = LymowColors.bgBase,
    onBackground = LymowColors.textPrimary,
    onSurface = LymowColors.textPrimary,
    error = LymowColors.danger,
)

private val LymowType = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

// Dark is the default & recommended scheme (outdoor/sunlight use); we intentionally
// do not follow the system light/dark setting.
@Composable
fun LymowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = LymowType, content = content)
}
