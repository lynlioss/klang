package app.lynli.klang.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.File

class LrclibProviderTest {
    private val query = LyricsQuery("Dying in Reverse", "Cage the Elephant", "Neon Pill", 234_000)
    private fun record(title: String = query.title, artist: String = query.artist, duration: Double = 234.0,
        plain: String = "Matched lyrics") = JSONObject().put("trackName", title).put("artistName", artist)
        .put("albumName", query.album).put("duration", duration).put("plainLyrics", plain)

    @Test fun exactLookupSendsMetadataAndReturnsLyrics() = runBlocking {
        var requests = 0
        val provider = LrclibProvider { path, parameters ->
            requests++; assertEquals("/api/get", path)
            assertEquals(query.title, parameters["track_name"])
            assertEquals(query.artist, parameters["artist_name"])
            assertEquals(query.album, parameters["album_name"])
            assertEquals("234.0", parameters["duration"])
            LyricsHttpResponse(200, wordRecord().toString())
        }
        assertEquals("Matched lyrics", provider.find(query)?.plain)
        assertEquals(1, requests)
    }

    @Test fun fallbackRejectsWrongArtistAndDifferentRecordingDuration() = runBlocking {
        val paths = mutableListOf<String>()
        val provider = LrclibProvider { path, parameters ->
            paths += path
            if (path == "/api/get") LyricsHttpResponse(404, "") else {
                assertFalse(parameters.containsKey("album_name"))
                LyricsHttpResponse(200, JSONArray(listOf(record(artist = "Other band"), record(duration = 300.0),
                    record(duration = 235.0, plain = "Correct"))).toString())
            }
        }
        assertEquals("Correct", provider.find(query)?.plain)
        assertEquals(listOf("/api/get", "/api/search"), paths)
    }

    @Test fun noExactTitleMatchDoesNotReturnUnrelatedSearchResult() = runBlocking {
        val provider = LrclibProvider { path, _ -> if (path == "/api/get") LyricsHttpResponse(404, "")
            else LyricsHttpResponse(200, JSONArray(listOf(record(title = "Dying in Reverse Live"))).toString()) }
        assertNull(provider.find(query))
    }

    @Test fun rateLimitedLookupDoesNotProceedToSearch() = runBlocking {
        var requests = 0
        val provider = LrclibProvider { _, _ -> requests++; LyricsHttpResponse(429, "", 12) }
        try { provider.find(query); fail("Expected rate-limit error") }
        catch (e: IOException) { assertTrue(e.message.orEmpty().contains("12 seconds")) }
        assertEquals(1, requests)
    }

    @Test fun invalidDurationAndUnknownAlbumAreOmitted() = runBlocking {
        val provider = LrclibProvider { _, params ->
            assertFalse(params.containsKey("duration")); assertFalse(params.containsKey("album_name"))
            LyricsHttpResponse(200, record().put("instrumental", true).toString())
        }
        assertTrue(provider.find(query.copy(durationMs = 4_000_000, album = "Unknown album"))!!.instrumental)
    }

    @Test fun fallbackRejectsARecordingWithMoreThanTwoSecondsDifference() = runBlocking {
        val provider = LrclibProvider { path, _ -> if (path == "/api/get") LyricsHttpResponse(404, "")
            else LyricsHttpResponse(200, JSONArray(listOf(record(duration = 239.0))).toString()) }
        assertNull(provider.find(query))
    }

    private fun wordRecord(title: String = query.title, artist: String = query.artist, duration: Double = 234.0) =
        record(title, artist, duration).put("hasWordSync", true).put("lyricsfile", """
            version: '1.0'
            lines:
              - text: Matched lyrics
                start_ms: 1000
                end_ms: 3000
                words:
                  - text: 'Matched '
                    start_ms: 1000
                  - text: lyrics
                    start_ms: 2000
        """.trimIndent())

    @Test fun sameDurationWordSyncedDuplicateUpgradesAnExactLineOnlyMatch() = runBlocking {
        val homecoming = LyricsQuery("Homecoming", "Kanye West/Chris Martin", "Graduation (Deluxe Edition - Explicit)", 204_000)
        val lineOnly = record(homecoming.title, homecoming.artist, 204.0).put("albumName", homecoming.album)
            .put("syncedLyrics", "[00:01]Matched lyrics").put("hasWordSync", false)
        val wordSynced = wordRecord(homecoming.title, "Kanye West, Chris Martin", 204.0).put("albumName", "Graduation")
        val paths = mutableListOf<String>()
        val provider = LrclibProvider { path, _ ->
            paths += path
            LyricsHttpResponse(200, if (path == "/api/get") lineOnly.toString() else JSONArray(listOf(lineOnly, wordSynced)).toString())
        }
        val result = provider.find(homecoming)!!
        assertTrue(result.hasWordTiming)
        assertEquals(listOf(1000L, 2000L), result.lines.single().words.map { it.startMs })
        assertEquals(listOf("/api/get", "/api/search"), paths)
    }

    @Test fun wordSyncBeatsTinyDurationDifferencesWithinTheTwoSecondTolerance() = runBlocking {
        val provider = LrclibProvider { path, _ ->
            if (path == "/api/get") LyricsHttpResponse(404, "")
            else LyricsHttpResponse(200, JSONArray(listOf(record().put("syncedLyrics", "[00:01]Matched lyrics"),
                wordRecord(duration = 235.0))).toString())
        }
        assertTrue(provider.find(query)!!.hasWordTiming)
    }

    @Test fun optionalWordUpgradeRejectsDifferentTitleArtistOrRecordingLength() = runBlocking {
        val lineOnly = record().put("syncedLyrics", "[00:01]Matched lyrics")
        val provider = LrclibProvider { path, _ -> LyricsHttpResponse(200,
            if (path == "/api/get") lineOnly.toString() else JSONArray(listOf(
                wordRecord(duration = 237.0), wordRecord(title = "Dying in Reverse Live"), wordRecord(artist = "Other band"))).toString()) }
        val result = provider.find(query)!!
        assertTrue(result.synchronized)
        assertFalse(result.hasWordTiming)
    }

    @Test fun failedUpgradeRetainsUsableExactLyricsAndCancellationStillPropagates() = runBlocking {
        val lineOnly = record().put("syncedLyrics", "[00:01]Matched lyrics")
        val limited = LrclibProvider { path, _ -> if (path == "/api/get") LyricsHttpResponse(200, lineOnly.toString())
            else LyricsHttpResponse(429, "", 60) }
        assertTrue(limited.find(query)!!.synchronized)
        val cancelled = LrclibProvider { path, _ -> if (path == "/api/get") LyricsHttpResponse(200, lineOnly.toString())
            else throw CancellationException("View closed") }
        try { cancelled.find(query); fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun wordSyncIsRankedFromParsedTimestampsRatherThanOnlyTheAdvertisedFlag() = runBlocking {
        val lineOnly = record().put("syncedLyrics", "[00:01]Matched lyrics")
        val provider = LrclibProvider { path, _ -> LyricsHttpResponse(200,
            if (path == "/api/get") lineOnly.toString() else JSONArray(listOf(
                lineOnly.put("hasWordSync", true).put("lyricsfile", "lines: [broken"), wordRecord().put("hasWordSync", false))).toString()) }
        assertTrue(provider.find(query)!!.hasWordTiming)
    }

    @Test fun homecomingFixturePreservesTheProviderWordTimings() = runBlocking {
        val response = File("src/test/resources/lyrics/homecoming-word-timing.json").readText()
        val provider = LrclibProvider { path, _ -> assertEquals("/api/get", path); LyricsHttpResponse(200, response) }
        val doc = provider.find(LyricsQuery("Homecoming", "Kanye West/Chris Martin", "Graduation", 203_000))!!
        assertTrue(doc.hasWordTiming)
        assertEquals(6, doc.lines.sumOf { it.words.size })
        assertEquals(8227L, doc.lines.last().words.last().endMs)
    }
}
