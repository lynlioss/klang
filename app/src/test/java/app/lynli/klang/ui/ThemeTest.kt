package app.lynli.klang.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.dynamicColorScheme
import com.materialkolor.PaletteStyle
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test fun contentDerivedMaterialSchemesKeepTextReadable() {
        for (seed in listOf(0xFF1252D2, 0xFF993C79, 0xFF888888, 0xFFF5D010)) {
            for (dark in listOf(false, true)) {
                val scheme = dynamicColorScheme(Color(seed), isDark = dark, style = PaletteStyle.Vibrant)
                for ((fill, text) in listOf(scheme.surface to scheme.onSurface,
                    scheme.primary to scheme.onPrimary, scheme.secondaryContainer to scheme.onSecondaryContainer,
                    scheme.surfaceContainer to scheme.onSurfaceVariant)) {
                    val a = fill.luminance(); val b = text.luminance()
                    val ratio = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                    assertTrue("Seed $seed, dark=$dark, contrast=$ratio", ratio >= 4.5f)
                }
            }
        }
    }
}
