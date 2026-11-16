package app.lynli.klang.plugins

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

class PluginManifestTest {
    private fun manifest() = PluginManifest.parse(File("src/main/assets/plugins/lrclib.json").readText())
    @Test fun bundledManifestDefinesLyricsButtonsAndTypedSettings() {
        val manifest = manifest()
        assertEquals(PluginType.LYRICS, manifest.type)
        assertEquals(listOf("Homepage", "Source", "Donate"), manifest.buttons.map { it.label })
        assertEquals("https://github.com/lynlioss/lrclib-klang", manifest.repository)
        assertEquals(2, manifest.lyrics!!.endpoints.size)
        assertEquals(2, manifest.settings.size)
        assertFalse(manifest.settings.any { it.id == "estimateWords" })
        assertTrue(manifest.settings.last() is PluginSetting.Range)
    }
    @Test fun repositoryLinksAreCanonicalAndRejectFilePathsAndNonGithubHosts() {
        assertEquals("https://github.com/owner/provider", GitHubPluginRepo.parse(" https://github.com/Owner/provider.git/ ").url)
        listOf("http://github.com/owner/provider", "https://github.com/owner/provider/tree/main",
            "https://github.com.evil.example/owner/provider", "https://user@github.com/owner/provider").forEach { link ->
            assertThrows(IllegalArgumentException::class.java) { GitHubPluginRepo.parse(link) }
        }
    }
    @Test fun versionsRejectPrereleasesAndCompareNumerically() {
        assertTrue(PluginVersion.parse("1.10.0") > PluginVersion.parse("1.9.9"))
        assertThrows(IllegalArgumentException::class.java) { PluginVersion.parse("1.0.0-beta") }
    }
    @Test fun customSettingsAndDisabledLinksParseWithoutAppSpecificCode() {
        val json = JSONObject(File("src/main/assets/plugins/lrclib.json").readText())
        json.put("settings", JSONArray("""[
          {"id":"apiKey","type":"text","title":"API key","default":"","secret":true},
          {"id":"language","type":"choice","title":"Language","default":"en","options":[{"value":"en","label":"English"}]}
        ]"""))
        json.put("buttons", JSONArray("""[{"enabled":false,"label":"Donate"}]"""))
        val manifest = PluginManifest.parse(json.toString())
        assertTrue(manifest.buttons.isEmpty())
        assertTrue((manifest.settings.first() as PluginSetting.Text).secret)
        assertEquals("en", (manifest.settings.last() as PluginSetting.Choice).defaultValue)
    }
    @Test fun unknownApiAndUnknownRequestSettingsAreRejected() {
        val json = JSONObject(File("src/main/assets/plugins/lrclib.json").readText())
        json.put("apiVersion", 2)
        assertThrows(IllegalArgumentException::class.java) { PluginManifest.parse(json.toString()) }
        json.put("apiVersion", 1)
        json.getJSONObject("lyrics").getJSONArray("requests").getJSONObject(0)
            .getJSONObject("query").put("key", "{settings.missing}")
        assertThrows(IllegalArgumentException::class.java) { PluginManifest.parse(json.toString()) }
    }
    @Test fun defaultsAndProviderSpecificOverridesAreIndependent() {
        val manifest = manifest()
        assertTrue(PluginConfiguration().boolean(manifest, "cacheLyrics"))
        assertEquals(0, PluginConfiguration().integer(manifest, "timingOffset"))
        val config = PluginConfiguration(values = mapOf("lrclib.cacheLyrics" to "false", "lrclib.timingOffset" to "-500"))
        assertFalse(config.boolean(manifest, "cacheLyrics"))
        assertEquals(-500, config.integer(manifest, "timingOffset"))
    }
    @Test fun extensionsStayOnApi1AndRejectUndeclaredCapabilitiesAndBadServices() {
        val json = JSONObject(File("src/test/resources/plugins/extension.json").readText())
        val manifest = PluginManifest.parse(json.toString())
        assertEquals(1, manifest.apiVersion)
        assertEquals(PluginType.EXTENSION, manifest.type)
        assertFalse(manifest.defaultEnabled)
        assertEquals("Jam", manifest.extension!!.actions.single().label)
        json.getJSONObject("extension").put("capabilities", JSONArray(listOf("filesystem.all")))
        assertThrows(IllegalArgumentException::class.java) { PluginManifest.parse(json.toString()) }
        json.getJSONObject("extension").put("capabilities", JSONArray(listOf("audio.read"))).put("service", "other.Service")
        assertThrows(IllegalArgumentException::class.java) { PluginManifest.parse(json.toString()) }
    }
    @Test fun api1MusicProvidersDeclareAServiceAndSupportedLinks() {
        val json = JSONObject(File("src/main/assets/plugins/lrclib.json").readText())
        json.put("apiVersion", 1).put("type", "musicImport")
        json.remove("lyrics")
        json.put("musicImport", JSONObject().put("package", "com.example.provider")
            .put("service", "com.example.provider.DownloadService").put("hosts", JSONArray(listOf("music.example.com")))
            .put("schemes", JSONArray(listOf("example-music"))))
        val provider = PluginManifest.parse(json.toString())
        assertEquals(PluginType.IMPORT, provider.type)
        assertEquals(1, provider.apiVersion)
        assertEquals("com.example.provider", provider.musicImport!!.packageName)
        json.getJSONObject("musicImport").put("service", "another.package.Service")
        assertThrows(IllegalArgumentException::class.java) { PluginManifest.parse(json.toString()) }
    }
}
