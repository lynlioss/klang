package app.lynli.klang.lyrics

import app.lynli.klang.plugins.PluginConfiguration
import app.lynli.klang.plugins.PluginManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LyricsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val query = LyricsQuery("Homecoming", "Kanye West/Chris Martin", "Graduation", 203_000)
    private val manifest = PluginManifest.parse(File("src/main/assets/plugins/lrclib.json").readText())
    private val config = PluginConfiguration(enabled = setOf("lrclib"))
    private val lineOnly = LyricsDocument("lrclib", "Matched lyrics", listOf(LyricLine("Matched lyrics", 1000, 3000)))
    private val wordSynced = lineOnly.copy(lines = listOf(LyricLine("Matched lyrics", 1000, 3000,
        listOf(LyricWord("Matched ", 1000, 2000), LyricWord("lyrics", 2000, 3000)))))

    private fun repository(directory: File, now: () -> Long = System::currentTimeMillis,
        find: suspend (LyricsQuery) -> LyricsDocument?) = LyricsRepository(directory, listOf(manifest), listOf(object : LyricsProvider {
        override val id = "lrclib"
        override suspend fun find(query: LyricsQuery) = find.invoke(query)
    }), now)
    private fun cacheFile(directory: File) = directory.listFiles()!!.single { it.extension == "json" }

    @Test fun versionTwoReleaseCacheRefreshesToWordSyncWithoutClearingOfflineLyrics() = runBlocking {
        val directory = temp.newFolder()
        repository(directory) { lineOnly }.find(query, config)
        val file = cacheFile(directory)
        val old = JSONObject(file.readText()).put("lookupVersion", 2)
        file.writeText(old.toString())
        var requests = 0
        val upgraded = repository(directory) { requests++; wordSynced }
        val result = upgraded.find(query, config) as LyricsState.Ready
        assertTrue(result.document.hasWordTiming)
        assertEquals(1, requests)
        assertTrue((upgraded.find(query, config) as LyricsState.Ready).document.hasWordTiming)
        assertEquals(1, requests)
    }

    @Test fun staleCacheSurvivesAnOfflineRefresh() = runBlocking {
        val directory = temp.newFolder()
        repository(directory) { lineOnly }.find(query, config)
        val file = cacheFile(directory)
        file.writeText(JSONObject(file.readText()).apply { remove("lookupVersion") }.toString())
        val result = repository(directory) { throw IOException("Offline") }.find(query, config) as LyricsState.Ready
        assertEquals(lineOnly, result.document)
        assertTrue(file.isFile)
    }

    @Test fun aCachedLineMatchRefreshesAfterOneDayToPickUpNewWordTimings() = runBlocking {
        val directory = temp.newFolder()
        var time = 1_000_000_000_000L
        repository(directory, { time }) { lineOnly }.find(query, config)
        var requests = 0
        val updated = repository(directory, { time }) { requests++; wordSynced }
        time += 24 * 60 * 60 * 1000L - 1
        assertFalse((updated.find(query, config) as LyricsState.Ready).document.hasWordTiming)
        assertEquals(0, requests)
        time++
        assertTrue((updated.find(query, config) as LyricsState.Ready).document.hasWordTiming)
        assertEquals(1, requests)
    }

    @Test fun refreshingDoesNotReplaceCachedWordSyncWithLineOnlyLyrics() = runBlocking {
        val directory = temp.newFolder()
        repository(directory) { wordSynced }.find(query, config)
        cacheFile(directory).setLastModified(1)
        val result = repository(directory) { lineOnly }.find(query, config) as LyricsState.Ready
        assertEquals(wordSynced, result.document)
    }

    @Test fun cacheRefreshDoesNotSwallowCancellation() = runBlocking {
        val directory = temp.newFolder()
        repository(directory) { lineOnly }.find(query, config)
        cacheFile(directory).setLastModified(1)
        val cancelled = repository(directory) { throw CancellationException("Track changed") }
        try { cancelled.find(query, config); fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }
}
