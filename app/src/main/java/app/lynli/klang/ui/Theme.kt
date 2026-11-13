package app.lynli.klang.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lynli.klang.R

val Brand = FontFamily(Font(R.font.space_grotesk))
private val Dark = darkColorScheme(
    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF092F67), primaryContainer = Color(0xFF284779), onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFC2C6DC), onSecondary = Color(0xFF2C3042), secondaryContainer = Color(0xFF424659), onSecondaryContainer = Color(0xFFE0E2F9),
    tertiary = Color(0xFFD5B9FF), onTertiary = Color(0xFF3C245F), tertiaryContainer = Color(0xFF543C77), onTertiaryContainer = Color(0xFFEDDCFF),
    background = Color(0xFF101114), surface = Color(0xFF101114), onSurface = Color(0xFFF1F0F5), onSurfaceVariant = Color(0xFFC1C4CF),
    surfaceContainerLowest = Color(0xFF0B0C0F), surfaceContainerLow = Color(0xFF191A20), surfaceContainer = Color(0xFF202127),
    surfaceContainerHigh = Color(0xFF2A2B32), surfaceContainerHighest = Color(0xFF34353D), outline = Color(0xFF8B8E99), outlineVariant = Color(0xFF424650),
)
private val Light = lightColorScheme(
    primary = Color(0xFF315DA8), onPrimary = Color.White, primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF0B2F65),
    secondaryContainer = Color(0xFFE0E2F9), onSecondaryContainer = Color(0xFF292E41),
    tertiary = Color(0xFF6B4D8F), tertiaryContainer = Color(0xFFEDDCFF), onTertiaryContainer = Color(0xFF35204F),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191B21), onSurfaceVariant = Color(0xFF454954),
    surfaceContainer = Color(0xFFEEEFF7), surfaceContainerLow = Color(0xFFF3F3FB), surfaceContainerHigh = Color(0xFFE8E9F1),
)
private val Type = Typography(
    displayLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Medium, fontSize = 57.sp, lineHeight = 64.sp),
    headlineLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = Brand, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KlangTheme(theme: String, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val scheme = if (dynamic) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = Type, motionScheme = MotionScheme.expressive(), content = content)
}
