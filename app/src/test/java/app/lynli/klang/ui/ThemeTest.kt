package app.lynli.klang.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import app.lynli.klang.data.ArtworkColors
import app.lynli.klang.data.artworkFixturePixels
import com.materialkolor.dynamicColorScheme
import com.materialkolor.hct.Hct
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test fun darkSurfacesAreNeutralAndKeepReadableContent() {
        for (seed in listOf(0xFF1252D2, 0xFF993C79, 0xFFF5D010)) {
            val scheme = dynamicColorScheme(Color(seed), isDark = true).blackSurfaces()
            for (fill in listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer,
                scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
                assertEquals(fill.red, fill.green, 0.001f)
                assertEquals(fill.red, fill.blue, 0.001f)
                val ratio = (scheme.onSurfaceVariant.luminance() + 0.05f) / (fill.luminance() + 0.05f)
                assertTrue("Dark surface text contrast=$ratio", ratio >= 4.5f)
            }
        }
    }

    @Test fun contentDerivedMaterialSchemesKeepTextReadable() {
        val seeds = listOf(0xFF1252D2, 0xFF993C79, 0xFF888888, 0xFFF5D010, 0xFF39B54A,
            0xFFFF0000, 0xFF000000, 0xFFFFFFFF).map { it.toInt() } +
            listOf("pink-sunset", "red-accent", "monochrome-dark", "monochrome-light")
                .map { requireNotNull(ArtworkColors.seed(artworkFixturePixels(it))) }
        for (seed in seeds) {
            for (dark in listOf(false, true)) {
                val scheme = artworkColorScheme(seed, dark)
                for ((fill, text) in listOf(scheme.surface to scheme.onSurface,
                    scheme.primary to scheme.onPrimary, scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.secondary to scheme.onSecondary, scheme.secondaryContainer to scheme.onSecondaryContainer,
                    scheme.tertiary to scheme.onTertiary, scheme.tertiaryContainer to scheme.onTertiaryContainer,
                    scheme.surfaceContainerHighest to scheme.onSurfaceVariant)) {
                    val a = fill.luminance(); val b = text.luminance()
                    val ratio = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                    assertTrue("Seed $seed, dark=$dark, contrast=$ratio", ratio >= 4.5f)
                }
            }
        }
    }

    @Test fun coverAccentsKeepTheirHueInPrimaryAndTonalControls() {
        for (name in listOf("pink-sunset", "red-accent")) {
            val seed = requireNotNull(ArtworkColors.seed(artworkFixturePixels(name)))
            val sourceHue = Hct.fromInt(seed).hue
            for (dark in listOf(false, true)) {
                val scheme = artworkColorScheme(seed, dark)
                for (color in listOf(scheme.primary, scheme.primaryContainer, scheme.secondary, scheme.secondaryContainer)) {
                    val hct = Hct.fromInt(color.toArgb())
                    val difference = kotlin.math.abs(hct.hue - sourceHue)
                    assertTrue("$name, dark=$dark shifted accent to $hct", minOf(difference, 360.0 - difference) < 10.0)
                }
            }
        }
    }

    @Test fun monochromeArtworkKeepsNeutralAccentsAndSurfaces() {
        for (name in listOf("monochrome-dark", "monochrome-light")) {
            val seed = requireNotNull(ArtworkColors.seed(artworkFixturePixels(name)))
            for (dark in listOf(false, true)) {
                val scheme = artworkColorScheme(seed, dark)
                for (color in listOf(scheme.primary, scheme.primaryContainer, scheme.secondary, scheme.secondaryContainer,
                    scheme.tertiary, scheme.tertiaryContainer, scheme.surface, scheme.surfaceContainerHigh)) {
                    assertEquals("$name, dark=$dark", color.red, color.green, 0.001f)
                    assertEquals("$name, dark=$dark", color.red, color.blue, 0.001f)
                }
            }
        }
    }
}
