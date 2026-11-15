package app.lynli.klang.data

import com.materialkolor.hct.Hct
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun artworkFixturePixels(name: String): IntArray =
    requireNotNull(ArtworkColorsTest::class.java.getResourceAsStream("/artwork/$name.png")).use {
        val image = requireNotNull(ImageIO.read(it))
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

class ArtworkColorsTest {
    @Test fun colorfulCoversKeepTheirSignatureAccent() {
        for ((name, expected) in listOf("pink-sunset" to 0xFFFF007D.toInt(), "red-accent" to 0xFFFF1E00.toInt())) {
            val seed = requireNotNull(ArtworkColors.seed(artworkFixturePixels(name)))
            val color = Hct.fromInt(seed)
            val difference = kotlin.math.abs(color.hue - Hct.fromInt(expected).hue)
            assertTrue("$name lost its accent: $color", minOf(difference, 360.0 - difference) < 10.0)
            assertTrue("$name accent was muddied: $color", color.chroma > 60.0)
        }
    }

    @Test fun monochromeCoversIgnoreCompressionTint() {
        for (name in listOf("monochrome-dark", "monochrome-light")) {
            val seed = requireNotNull(ArtworkColors.seed(artworkFixturePixels(name)))
            assertFalse("$name acquired a color", ArtworkColors.hasColor(seed))
        }
    }

    @Test fun smallAccentSurvivesMostlyNeutralArtwork() {
        val red = 0xFFF52016.toInt()
        val pixels = IntArray(4096) { if (it < 3850) 0xFFE4E4E4.toInt() else red }
        assertEquals(red, ArtworkColors.seed(pixels))
    }

    @Test fun visibleDarkAccentSurvivesBlackBackground() {
        val purple = 0xFF802080.toInt()
        val pixels = IntArray(4096) { if (it < 3072) 0xFF000000.toInt() else purple }
        assertEquals(purple, ArtworkColors.seed(pixels))
    }

    @Test fun isolatedColorNoiseDoesNotTintMonochromeArtwork() {
        val pixels = IntArray(4096) { if (it < 4092) 0xFF888888.toInt() else 0xFFFF0000.toInt() }
        assertFalse(ArtworkColors.hasColor(requireNotNull(ArtworkColors.seed(pixels))))
    }

    @Test fun genuinelyBrownArtworkKeepsItsColor() {
        val brown = 0xFF885533.toInt()
        assertEquals(brown, ArtworkColors.seed(IntArray(4096) { brown }))
    }

    @Test fun transparentColorsDoNotSupplyTheAccent() {
        val blue = 0xFF1252D2.toInt()
        val pixels = IntArray(4096) { if (it < 3000) 0x00FF0000 else blue }
        assertEquals(blue, ArtworkColors.seed(pixels))
        assertNull(ArtworkColors.seed(IntArray(4096) { 0x00FF0000 }))
        assertNull(ArtworkColors.seed(intArrayOf()))
    }

    @Test fun pureBlackAndWhiteDoNotUseMissingArtworkFallback() {
        for (color in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val seed = ArtworkColors.seed(IntArray(4096) { color })
            assertNotNull(seed)
            assertFalse(ArtworkColors.hasColor(requireNotNull(seed)))
        }
    }
}
