package app.lynli.klang.lyrics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsParserTest {
    @Test fun lrcSupportsFractionsMultipleTimestampsOffsetsAndInstrumentalGaps() {
        val lines = LyricsParser.lrc("[ar:Artist]\n[offset:-100]\n[00:02.50][00:08.005] Hello\n[00:05.1] \n[00:10] Goodbye", 15_000)
        assertEquals(listOf(2600L, 5200L, 8105L, 10100L), lines.map { it.startMs })
        assertEquals(listOf("Hello", "", "Hello", "Goodbye"), lines.map { it.text })
        assertEquals(15_000L, lines.last().endMs)
        val doc = LyricsDocument("test", "", lines)
        assertEquals(-1, doc.activeLine(0))
        assertEquals(1, doc.activeLine(6000))
        assertEquals(0, doc.activeLine(3000)) // seeking backward
        assertEquals(-1, doc.activeLine(15_000))
    }

    @Test fun enhancedLrcPreservesWordTextAndRealTiming() {
        val line = LyricsParser.lrc("[00:01.00]<00:01.00>Hello <00:02.00>world", 4000).single()
        assertEquals("Hello world", line.text)
        assertEquals(listOf("Hello ", "world"), line.words.map { it.text })
        assertEquals(listOf(1000L, 2000L), line.words.map { it.startMs })
        assertEquals(4000L, line.words.last().endMs)
    }

    @Test fun lyricsfileReadsWordTimingEvenWithoutWordEndAndSupportsOverlaps() {
        val doc = LyricsParser.lyricsfile("""
            version: '1.0'
            metadata:
              duration_ms: 10000
            lines:
              - text: "Hello world"
                start_ms: 1000
                end_ms: 5000
                words:
                  - text: "Hello "
                    start_ms: 1000
                  - text: world
                    start_ms: 2500
              - text: Harmony
                start_ms: 3000
                end_ms: 7000
            plain: |
              Hello world
              Harmony
        """.trimIndent(), 10_000)
        assertTrue(doc.hasWordTiming)
        assertEquals(2500L, doc.lines.first().words.first().endMs)
        assertEquals(5000L, doc.lines.first().words.last().endMs)
        assertEquals(1, doc.activeLine(3500))
        assertEquals(-1, doc.activeLine(7500))
    }

    @Test fun malformedYamlFallsBackToLrcAndPlainLyricsDoNotAcquireFakeSync() {
        val doc = LyricsParser.record(JSONObject().put("lyricsfile", "lines: [broken")
            .put("syncedLyrics", "[00:01]こんにちは").put("plainLyrics", "こんにちは"), 3000)
        assertEquals("こんにちは", doc.lines.single().text)
        val plain = LyricsParser.record(JSONObject().put("plainLyrics", "Read along"), 3000)
        assertFalse(plain.synchronized)
        assertEquals("Read along", plain.plain)
    }

    @Test fun lineSyncedLyricsUseOnlyTheProviderLineTimestamps() {
        val doc = LyricsParser.record(JSONObject().put("syncedLyrics", "[00:01]I feel the music\n[00:05]Next line"), 7000)
        assertTrue(doc.synchronized)
        assertFalse(doc.hasWordTiming)
        assertTrue(doc.lines.all { it.words.isEmpty() })
        assertEquals(0, doc.activeLine(4999))
        assertEquals(1, doc.activeLine(5000))
    }

    @Test fun instrumentalAndUntrustedYamlTagsAreHandledSafely() {
        assertTrue(LyricsParser.record(JSONObject().put("instrumental", true), 5000).instrumental)
        val source = "!!java.net.URL ['https://example.com']"
        assertThrows(Exception::class.java) { LyricsParser.lyricsfile(source, 5000) }
    }

    @Test fun overlappingVocalRemainsActiveWhenTheShorterVocalEnds() {
        val document = LyricsDocument("test", "", listOf(LyricLine("Lead", 1000, 8000), LyricLine("Harmony", 2000, 4000)))
        assertEquals(1, document.activeLine(3000))
        assertEquals(0, document.activeLine(5000))
        assertEquals(-1, document.activeLine(8000))
    }

    @Test fun wordRevealFollowsTheClockAndRewindsOnSeek() {
        val word = LyricWord("Hello", 1000, 2000)
        assertEquals(0f, word.progress(999), 0f)
        assertEquals(0.5f, word.progress(1500), 0f)
        assertEquals(1f, word.progress(2500), 0f)
        assertEquals(0.25f, word.progress(1250), 0f)
    }

    @Test fun positiveLrcOffsetShowsLinesAndWordsEarlier() {
        val line = LyricsParser.lrc("[offset:250]\n[00:01]<00:01>Hello <00:02>world", 4000).single()
        assertEquals(750L, line.startMs)
        assertEquals(750L, line.words.first().startMs)
        assertEquals(1750L, line.words.last().startMs)
    }

    @Test fun lyricsfileWithoutWordsRetainsLineSyncIncludingLongNotes() {
        val doc = LyricsParser.lyricsfile("""
            version: '1.0'
            lines:
              - text: Hold this note
                start_ms: 10000
                end_ms: 25000
        """.trimIndent(), 30000)
        assertFalse(doc.hasWordTiming)
        assertTrue(doc.lines.single().words.isEmpty())
        assertEquals(0, doc.activeLine(24999))
        assertEquals(-1, doc.activeLine(25000))
    }
}
