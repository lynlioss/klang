package app.lynli.klang.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PlaybackStartTest {
    @Test fun myMixStartsAnywhereInThePlaylistAndEnablesShuffle() {
        val random = Random(42)
        val starts = List(100) { collectionPlaybackStart(8, null, "mix", false, random) }
        assertTrue(starts.all { it.shuffle })
        assertEquals((0..7).toSet(), starts.map { it.index }.toSet())
    }
    @Test fun choosingASongInMyMixKeepsThatSongFirst() {
        assertEquals(PlaybackStart(4, true), collectionPlaybackStart(8, 4, "mix", false))
    }
    @Test fun albumsAndPlaylistsPlayInOrderUnlessShuffleWasRequested() {
        assertEquals(PlaybackStart(0, false), collectionPlaybackStart(8, null, "album:test", false))
        assertEquals(PlaybackStart(3, false), collectionPlaybackStart(8, 3, "playlist:test", false))
        assertTrue(collectionPlaybackStart(8, null, "playlist:test", true).shuffle)
        assertEquals(PlaybackStart(0, true), collectionPlaybackStart(1, null, "mix", false))
    }
}
