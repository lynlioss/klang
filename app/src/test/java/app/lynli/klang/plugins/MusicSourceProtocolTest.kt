package app.lynli.klang.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicSourceProtocolTest {
    @Test fun catalogIdentityIsStableOpaqueAndScopedToTheProvider() {
        val id = "catalog:track:a/b ?+#ü"
        val source = MusicSourceProtocol.identity("community.music", id)
        assertEquals(id, MusicSourceProtocol.entityId(source))
        assertFalse(source.contains(" "))
        assertFalse(source.contains("#"))
        assertNotEquals(source, MusicSourceProtocol.identity("other", id))
        assertThrows(IllegalArgumentException::class.java) { MusicSourceProtocol.identity("../bad", id) }
    }
    @Test fun playlistDuplicatesRetainBothPositionsAndEmptyPagesCanAdvance() {
        val items = JSONArray().put(JSONObject().put("id", "same-track").put("kind", "track").put("title", "Song").put("uid", "first"))
            .put(JSONObject().put("id", "same-track").put("kind", "track").put("title", "Song").put("uid", "second"))
        val page = JSONObject().put("items", items).put("total", 120).put("offset", 0).put("nextOffset", 50)
        assertEquals(2, MusicSourceProtocol.page(page).getJSONArray("items").length())
        assertEquals(50, MusicSourceProtocol.page(JSONObject(page.toString()).put("items", JSONArray())).getInt("nextOffset"))
        assertThrows(IllegalArgumentException::class.java) { MusicSourceProtocol.page(page.put("nextOffset", 0)) }
        assertThrows(IllegalArgumentException::class.java) { MusicSourceProtocol.page(page.put("nextOffset", 120)) }
    }
    @Test fun playbackAcceptsHttpsAndRejectsInjectedHeaders() {
        val result = JSONObject().put("url", "https://audio.example.test/recording?expire=1800000000")
            .put("headers", JSONObject().put("User-Agent", "Klang"))
        assertEquals("Klang", MusicSourceProtocol.stream(result).second["User-Agent"])
        listOf("http://audio.example.test/file", "file:///tmp/song", "https://secret@audio.example.test/file").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { MusicSourceProtocol.stream(JSONObject(result.toString()).put("url", url)) }
        }
        assertThrows(IllegalArgumentException::class.java) { MusicSourceProtocol.stream(result.put("headers", JSONObject().put("Referer", "ok\r\nCookie: secret"))) }
        assertThrows(IllegalArgumentException::class.java) { MusicSourceProtocol.stream(result.put("headers", JSONObject().put("Host", "other.example.test"))) }
    }
}
