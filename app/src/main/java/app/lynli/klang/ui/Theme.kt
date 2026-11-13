@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lynli.klang.R
import app.lynli.klang.data.Track
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

// Brand expression is reserved for display text. Body, labels, and controls stay native.
private fun displayFamily(size: Int) = FontFamily(Font(when (size) {
    57 -> R.font.klang_display_57
    45 -> R.font.klang_display_45
    else -> R.font.klang_display_36
}, weight = FontWeight.Black))
internal val KlangTypography = Typography().let { native ->
    Typography(
        displayLarge = native.displayLarge.copy(fontFamily = displayFamily(57), fontWeight = FontWeight.Black, letterSpacing = 0.sp),
        displayMedium = native.displayMedium.copy(fontFamily = displayFamily(45), fontWeight = FontWeight.Black, letterSpacing = 0.sp),
        displaySmall = native.displaySmall.copy(fontFamily = displayFamily(36), fontWeight = FontWeight.Black, letterSpacing = 0.sp),
    )
}
private val LocalKlangDark = staticCompositionLocalOf { false }

// Neutral surface tones keep dark mode black instead of washing the app in its accent hue.
internal fun ColorScheme.blackSurfaces() = copy(
    surface = Color(0xFF090909), background = Color(0xFF090909),
    surfaceDim = Color(0xFF090909), surfaceBright = Color(0xFF343434),
    surfaceContainerLowest = Color(0xFF050505), surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF191919), surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2C2C2C), surfaceVariant = Color(0xFF2C2C2C),
    onSurface = Color(0xFFF2F2F2), onBackground = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFFC6C6C6), outline = Color(0xFF909090), outlineVariant = Color(0xFF454545),
)

@Composable
fun KlangTheme(theme: String, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val context = LocalContext.current
    val generated = rememberDynamicColorScheme(seedColor = Color(0xFF787878),
        isDark = dark, style = PaletteStyle.Monochrome)
    val target = if (dynamic) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else generated
    val scheme = animatedScheme(if (dark) target.blackSurfaces() else target)
    CompositionLocalProvider(LocalKlangDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = KlangTypography, motionScheme = MotionScheme.expressive(), content = content)
    }
}

/** Cover colors are scoped to playback and album screens, never the app shell. */
@Composable
internal fun ContentTheme(enabled: Boolean, track: Track?, content: @Composable () -> Unit) {
    if (enabled && track != null) CoverTheme(track, content) else content()
}

@Composable
internal fun UtilityIconButton(onClick: () -> Unit, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    content: @Composable () -> Unit) {
    FilledTonalIconButton(onClick, modifier, colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface)) { content() }
}

/** The mini player is always content-colored, independently of the app appearance switches. */
@Composable
internal fun CoverTheme(track: Track, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val seed by produceState<Int?>(null, track.id) { value = ArtworkStore.seed(context, track) }
    val target = rememberDynamicColorScheme(seedColor = Color(seed ?: 0xFF5075C8.toInt()),
        isDark = LocalKlangDark.current, style = PaletteStyle.Vibrant)
    MaterialTheme(colorScheme = animatedScheme(target), content = content)
}

@Composable
private fun animatedScheme(target: ColorScheme): ColorScheme {
    @Composable fun color(value: Color) = animateColorAsState(value,
        animationSpec = spring(dampingRatio = 1f, stiffness = 800f), label = "Material color").value
    return target.copy(
        primary = color(target.primary), onPrimary = color(target.onPrimary),
        primaryContainer = color(target.primaryContainer), onPrimaryContainer = color(target.onPrimaryContainer),
        secondary = color(target.secondary), onSecondary = color(target.onSecondary),
        secondaryContainer = color(target.secondaryContainer), onSecondaryContainer = color(target.onSecondaryContainer),
        tertiary = color(target.tertiary), onTertiary = color(target.onTertiary),
        tertiaryContainer = color(target.tertiaryContainer), onTertiaryContainer = color(target.onTertiaryContainer),
        surface = color(target.surface), onSurface = color(target.onSurface), onSurfaceVariant = color(target.onSurfaceVariant),
        surfaceContainerLowest = color(target.surfaceContainerLowest), surfaceContainerLow = color(target.surfaceContainerLow),
        surfaceContainer = color(target.surfaceContainer), surfaceContainerHigh = color(target.surfaceContainerHigh),
        surfaceContainerHighest = color(target.surfaceContainerHighest), outline = color(target.outline), outlineVariant = color(target.outlineVariant),
    )
}
