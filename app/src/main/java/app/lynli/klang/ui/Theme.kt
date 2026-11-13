@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.lynli.klang.data.Track
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

// Native Android typeface in every role.
@Composable
fun KlangTheme(theme: String, dynamic: Boolean, coverColor: Boolean, track: Track?, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val context = LocalContext.current
    val seed by produceState<Int?>(null, track?.id, coverColor) {
        value = if (coverColor && track != null) ArtworkStore.seed(context, track) else null
    }
    val generated = rememberDynamicColorScheme(seedColor = Color(seed ?: 0xFF5075C8.toInt()),
        isDark = dark, style = PaletteStyle.Vibrant)
    val target = if (dynamic && !coverColor) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else generated
    val scheme = animatedScheme(target)
    MaterialTheme(colorScheme = scheme, typography = Typography(), motionScheme = MotionScheme.expressive(), content = content)
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
