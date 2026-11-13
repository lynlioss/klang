package app.lynli.klang.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTest {
    @Test fun playlistsRoundTripNamesAndOrderedIds() {
        val saved = listOf(SavedPlaylist("one", "静かな夜 🎧", listOf("content://audio/9", "content://audio/2")),
            SavedPlaylist("two", "Empty"))
        assertEquals(saved, PlaylistCodec.decode(PlaylistCodec.encode(saved)))
    }
    @Test fun addingDoesNotDuplicateAndMovingPreservesOrder() {
        val playlist = SavedPlaylist("id", "Mix", listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c", "d"), playlist.add(listOf("b", "d", "d")).trackIds)
        assertEquals(listOf("b", "a", "c"), playlist.move("b", -1).trackIds)
        assertEquals(listOf("a", "c", "b"), playlist.move("b", 1).trackIds)
        assertEquals(playlist, playlist.move("a", -1))
        assertEquals(playlist, playlist.move("missing", 1))
        assertEquals(listOf("a", "c"), playlist.remove("b").trackIds)
    }
    @Test fun invalidEntriesDoNotEraseOtherPlaylists() {
        assertEquals(listOf(SavedPlaylist("ok", "Fine", listOf("a"))),
            PlaylistCodec.decode("""[{"id":"ok","name":"Fine","tracks":["a","a"]},{"name":"bad"}]"""))
    }
}
