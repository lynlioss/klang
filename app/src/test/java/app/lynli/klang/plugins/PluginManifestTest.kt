package app.lynli.klang.plugins

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginManifestTest {
    private fun manifest() = PluginManifest.parse(File("src/main/assets/plugins/lrclib.json").readText())
    @Test fun bundledManifestDefinesLyricsButtonsAndTypedSettings() {
        val manifest = manifest()
        assertEquals(PluginType.LYRICS, manifest.type)
        assertEquals(listOf("Homepage", "Donate"), manifest.buttons.map { it.label })
        assertTrue(manifest.buttons.all { it.url == "https://lrclib.net/" })
        assertEquals(2, manifest.settings.size)
        assertFalse(manifest.settings.any { it.id == "estimateWords" })
        assertTrue(manifest.settings.last() is PluginSetting.Range)
    }
    @Test fun defaultsAndProviderSpecificOverridesAreIndependent() {
        val manifest = manifest()
        assertTrue(PluginConfiguration().boolean(manifest, "cacheLyrics"))
        assertEquals(0, PluginConfiguration().integer(manifest, "timingOffset"))
        val config = PluginConfiguration(values = mapOf("lrclib.cacheLyrics" to "false", "lrclib.timingOffset" to "-500"))
        assertFalse(config.boolean(manifest, "cacheLyrics"))
        assertEquals(-500, config.integer(manifest, "timingOffset"))
    }
}
