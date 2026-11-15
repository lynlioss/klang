package app.lynli.klang.importing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ImportTest {
    private val spotifyId = "4cOdK2wGLETKBW3PvgPWqT"
    private val song = ImportSong("spotify:track:$spotifyId", "https://open.spotify.com/track/$spotifyId",
        "Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213_000,
        "https://example.com/cover.jpg", "1987-11-12", 1, 1, true)

    @Test fun outputPolicyUsesOriginalCodecAndStreamCopy() {
        assertTrue(ORIGINAL_AUDIO_FORMAT.startsWith("bestaudio["))
        assertTrue(ORIGINAL_AUDIO_FORMAT.contains("opus"))
        assertTrue(ORIGINAL_AUDIO_FORMAT.contains("mp4a"))
        assertEquals("ExtractAudio+ffmpeg_o:-c:a copy", STREAM_COPY_ARGUMENTS)
    }

    @Test fun canonicalizesSharedLinksWithoutTracking() {
        val link = ImportLink.parse(" https://open.spotify.com/intl-de/track/$spotifyId?si=tracking ")
        assertEquals(song.url, link.url)
        assertFalse(link.playlist)
        assertEquals(link, ImportLink.parse("spotify:track:$spotifyId"))
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            ImportLink.parse("https://youtu.be/dQw4w9WgXcQ?si=tracking").url)
    }

    @Test fun songSharedFromPlaylistDoesNotImportEntirePlaylist() {
        val link = ImportLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890123")
        assertFalse(link.playlist)
        assertTrue(ImportLink.parse("https://music.youtube.com/playlist?list=PL1234567890123").playlist)
    }

    @Test fun rejectsLookalikeHostsAndCommands() {
        listOf("https://youtube.com.attacker.org/watch?v=dQw4w9WgXcQ",
            "https://user@music.youtube.com/watch?v=dQw4w9WgXcQ", "file:///etc/passwd",
            "--exec touch /tmp/anything", "https://open.spotify.com/album/$spotifyId").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ImportLink.parse(value) }
        }
    }

    @Test fun journalPreservesMetadataOrderAndFailures() {
        val first = ImportEntry(song, ImportPhase.COMPLETE, 1f, uri = "content://media/external/audio/media/3")
        val second = ImportEntry(song.copy(sourceId = "other"), ImportPhase.FAILED, error = "Network unavailable")
        val batch = ImportBatch("batch", "Playlist", ImportSource.SPOTIFY, listOf(first, second), "playlist", 10)
        assertEquals(listOf(batch), ImportCodec.decode(ImportCodec.encode(listOf(batch))))
        assertFalse(batch.pending)
        assertEquals(1, batch.complete)
        assertEquals(1, batch.failed)
        assertEquals(1f, batch.progress, 0f)
    }

    @Test fun matchesOfficialRecordingAndRejectsWrongVersions() {
        fun candidate(title: String, artist: String = "Rick Astley - Topic", duration: Int = 213) =
            JSONObject().put("title", title).put("uploader", artist).put("duration", duration)
        assertTrue(recordingScore(song, candidate(song.title)) >= .72)
        assertEquals(0.0, recordingScore(song, candidate(song.title + " (Live)")), 0.0)
        assertEquals(0.0, recordingScore(song, candidate(song.title + " (Karaoke)")), 0.0)
        assertEquals(0.0, recordingScore(song, candidate(song.title, "Cover Artist")), 0.0)
        assertEquals(0.0, recordingScore(song, candidate(song.title, duration = 350)), 0.0)
        assertTrue(recordingScore(song.copy(title = song.title + " (Live)"), candidate(song.title + " (Live)")) >= .72)
    }

    @Test fun tagsUseReleaseDateAndAlbumArtwork() {
        val info = JSONObject().put("title", "Video title").put("upload_date", "20261115")
        tagInfo(info, song)
        assertEquals(song.title, info.getString("track"))
        assertEquals(song.artist, info.getString("artist"))
        assertEquals(song.album, info.getString("album"))
        assertEquals("1987-11-12", info.getString("meta_date"))
        assertEquals(1, info.getInt("track_number"))
        assertEquals(song.artwork, info.getJSONArray("thumbnails").getJSONObject(0).getString("url"))
    }

    @Test fun publicSpotifyMetadataSelectsLargestCover() {
        val json = JSONObject().put("uri", song.sourceId).put("name", song.title)
            .put("artists", JSONObject().put("items", JSONArray().put(JSONObject()
                .put("profile", JSONObject().put("name", song.artist)))))
            .put("duration", JSONObject().put("totalMilliseconds", song.durationMs))
            .put("albumOfTrack", JSONObject().put("name", song.album).put("coverArt", JSONObject()
                .put("sources", JSONArray().put(JSONObject().put("width", 64).put("url", "small"))
                    .put(JSONObject().put("width", 640).put("url", "large")))))
        val metadata = SpotifyMetadata.parsePublicTrack(json)
        assertEquals("large", metadata.artwork)
        assertEquals(song.album, metadata.album)
        assertEquals(song.artist, metadata.artist)
        val state = JSONObject().put("entities", JSONObject().put("items", JSONObject().put(song.sourceId, json)))
        val html = "<script id=\"initialState\" type=\"text/plain\">" +
            Base64.getEncoder().encodeToString(state.toString().toByteArray()) + "</script>"
        assertEquals(song.title, SpotifyMetadata.publicEntity(html, song.sourceId)?.getString("name"))
    }
}
