package app.lynli.klang.data

import org.junit.Assert.*
import org.junit.Test

class LocalRecordingsTest {
    private val song = RecordingIdentity("Hold My Liquor", "Kanye West", "Yeezus", 326_000)
    private fun match(remote: RecordingIdentity, local: List<RecordingIdentity> = listOf(song)) =
        LocalRecordings(local) { it }.match(remote)

    @Test fun normalizesCasePunctuationAndFeaturedCredits() {
        assertEquals(song, match(song.copy(title = "HOLD MY LIQUOR (feat. Artist)", artist = "Kanye West, Artist", durationMs = 325_800)))
        val blood = song.copy(title = "Blood On The Leaves", durationMs = 359_000)
        assertEquals(blood, match(blood.copy(title = "Blood on the Leaves"), listOf(blood)))
    }
    @Test fun rejectsVersionsOtherArtistsAndDurationMismatches() {
        listOf("Live", "Remix", "Radio Edit", "Acoustic", "Clean").forEach {
            assertNull(match(song.copy(title = "${song.title} ($it)")))
        }
        assertNull(match(song.copy(artist = "Cover Artist")))
        assertNull(match(song.copy(durationMs = 345_000)))
        assertNull(match(song.copy(durationMs = 0)))
    }
    @Test fun albumAndClosestDurationBreakTiesWithoutMergingDifferentRecordings() {
        val compilation = song.copy(album = "Hits", durationMs = 325_500)
        assertEquals(song, match(song, listOf(compilation, song)))
        assertNull(match(song.copy(explicit = true), listOf(song.copy(explicit = false))))
    }
}
