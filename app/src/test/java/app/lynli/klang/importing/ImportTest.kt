package app.lynli.klang.importing

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImportTest {
    private val song = ImportSong("id", "https://music.example/song", "ELEMENT.", "Kendrick Lamar", durationMs = 209_000,
        payload = "{\"customToken\":\"abc\"}")
    @Test fun journalPreservesOpaqueProviderPayloadAndDuplicateDecision() {
        val entry = ImportEntry(song, ImportPhase.DUPLICATE, duplicateUri = "content://media/1", allowDuplicate = false)
        val batch = ImportBatch("batch", "Album", "custom-provider", listOf(entry), createdAt = 10)
        assertEquals(listOf(batch), ImportCodec.decode(ImportCodec.encode(listOf(batch))))
        assertTrue(batch.warnings)
        assertFalse(batch.pending)
    }
    @Test fun acceptsAlbumAndCustomProviderLinksWithoutKnowingTheService() {
        assertEquals("https://music.example/album/abc", ImportLink.parse(" https://music.example/album/abc ").url)
        assertEquals("custom:ep:abc", ImportLink.parse("custom:ep:abc").url)
        listOf("file:///etc/passwd", "content://music/1", "--exec touch anything", "https://user@music.example/a",
            "https://music.example/%invalid", "https://music.example/song with spaces").forEach {
            assertThrows(IllegalArgumentException::class.java) { ImportLink.parse(it) }
        }
    }
    @Test fun metadataPayloadIsNotLostWhenPreviewEntersQueue() {
        val json = JSONObject().put("id", "custom:1").put("url", "https://example.com/song").put("title", "Song")
            .put("privateProviderField", JSONObject().put("token", "abc"))
        val parsed = ImportCodec.readSong(json)
        assertEquals("abc", JSONObject(parsed.payload).getJSONObject("privateProviderField").getString("token"))
    }
    @Test fun blockedDuplicatesDoNotPretendAudioHasDownloaded() {
        val preparing = ImportEntry(song, ImportPhase.MATCHING)
        val duplicate = ImportEntry(song, ImportPhase.DUPLICATE)
        assertEquals(0f, downloadProgress(listOf(preparing, duplicate)), 0f)
        assertEquals(.45f, downloadProgress(listOf(preparing.copy(phase = ImportPhase.DOWNLOADING, progress = .5f), duplicate)), .001f)
    }
    @Test fun completionAlertsRequireBackgroundVisibilityAndPermission() {
        assertFalse(shouldNotifyDownloadCompletion(appVisible = true, permissionGranted = true, hasOutcome = true))
        assertFalse(shouldNotifyDownloadCompletion(appVisible = false, permissionGranted = false, hasOutcome = true))
        assertFalse(shouldNotifyDownloadCompletion(appVisible = false, permissionGranted = true, hasOutcome = false))
        assertTrue(shouldNotifyDownloadCompletion(appVisible = false, permissionGranted = true, hasOutcome = true))
    }
}
