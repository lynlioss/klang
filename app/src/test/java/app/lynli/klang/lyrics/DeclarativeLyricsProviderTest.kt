package app.lynli.klang.lyrics

import app.lynli.klang.plugins.PluginConfiguration
import app.lynli.klang.plugins.PluginManifest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeclarativeLyricsProviderTest {
    private val query = LyricsQuery("Homecoming", "Kanye West/Chris Martin", "Graduation", 203_000)
    private fun manifest() = JSONObject(File("src/main/assets/plugins/lrclib.json").readText())
    private fun record(words: Boolean = false, title: String = query.title) = JSONObject()
        .put("trackName", title).put("artistName", query.artist).put("albumName", query.album).put("duration", 203)
        .put("plainLyrics", "Hello world").put("syncedLyrics", if (words) "[00:01]<00:01>Hello <00:02>world" else "[00:01]Hello world")

    @Test fun downloadRuntimeUpgradesExactLineLyricsToMatchedWordTimings() = runBlocking {
        var requests = 0
        val provider = DeclarativeLyricsProvider(PluginManifest.parse(manifest().toString()), PluginConfiguration()) { _, _, _ ->
            requests++
            LyricsHttpResponse(200, if (requests == 1) record().toString()
                else "[${record(true, "Wrong song")},${record(true)}]")
        }
        val result = provider.find(query)!!
        assertEquals("lrclib", result.providerId)
        assertTrue(result.hasWordTiming)
        assertEquals(2, requests)
        assertEquals(listOf("Hello ", "world"), result.lines.single().words.map { it.text })
    }

    @Test fun thirdPartySettingsAndNestedMappingsReachTheRuntime() = runBlocking {
        val json = manifest().put("id", "third-party").put("settings", org.json.JSONArray("""
            [{"id":"apiKey","type":"text","title":"API key","default":"","secret":true}]
        """))
        json.getJSONObject("lyrics").put("requests", org.json.JSONArray("""
            [{"url":"https://example.com/lyrics","query":{"song":"{title}"},"headers":{"Authorization":"Bearer {settings.apiKey}"},
              "response":{"format":"json","records":"result","plain":"lyrics.text","synced":"lyrics.lrc"}}]
        """))
        val provider = DeclarativeLyricsProvider(PluginManifest.parse(json.toString()),
            PluginConfiguration(values = mapOf("third-party.apiKey" to "my-key"))) { _, parameters, headers ->
            assertEquals(query.title, parameters["song"])
            assertEquals("Bearer my-key", headers["Authorization"])
            LyricsHttpResponse(200, """{"result":{"lyrics":{"text":"Hello","lrc":"[00:01]Hello"}}}""")
        }
        val result = provider.find(query)!!
        assertEquals("third-party", result.providerId)
        assertEquals("Hello", result.plain)
        assertTrue(result.synchronized)
    }

    @Test fun failedOptionalEnrichmentKeepsUsableLyrics() = runBlocking {
        var requests = 0
        val provider = DeclarativeLyricsProvider(PluginManifest.parse(manifest().toString()), PluginConfiguration()) { _, _, _ ->
            requests++
            if (requests == 1) LyricsHttpResponse(200, record().toString()) else LyricsHttpResponse(503, "")
        }
        assertEquals("Hello world", provider.find(query)!!.plain)
        assertEquals(2, requests)
    }

    @Test fun exactWordTimingsSkipTheSearchRequest() = runBlocking {
        var requests = 0
        val provider = DeclarativeLyricsProvider(PluginManifest.parse(manifest().toString()), PluginConfiguration()) { _, _, _ ->
            requests++
            LyricsHttpResponse(200, record(true).toString())
        }
        assertTrue(provider.find(query)!!.hasWordTiming)
        assertEquals(1, requests)
    }
}
