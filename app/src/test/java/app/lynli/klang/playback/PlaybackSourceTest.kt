package app.lynli.klang.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceTest {
    @Test fun albumPlaybackDoesNotActivateMyMixOrAnotherPlaylistWithSharedSongs() {
        val source = "album:6:YeezusKanye West"
        assertTrue(canResumeCollection(source, source, true))
        assertFalse(canResumeCollection(source, "mix", true))
        assertFalse(canResumeCollection(source, "playlist:saved", true))
    }
    @Test fun sameNamedPlaylistsRemainDistinctAndIdentitySurvivesRenaming() {
        assertTrue(canResumeCollection("playlist:one", "playlist:one", true))
        assertFalse(canResumeCollection("playlist:one", "playlist:two", true))
    }
    @Test fun libraryPlaybackAndEmptyQueuesCannotResumeACollection() {
        assertFalse(canResumeCollection(null, "mix", true))
        assertFalse(canResumeCollection("mix", "mix", false))
    }
}
