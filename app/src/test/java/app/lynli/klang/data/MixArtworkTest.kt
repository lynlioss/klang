package app.lynli.klang.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MixArtworkTest {
    private data class Song(val title: String, val album: String, val artist: String, val albumArtist: String = "")
    private fun track(title: String, album: String, artist: String, albumArtist: String = "") = Song(title, album, artist, albumArtist)
    private fun artwork(tracks: List<Song>, random: Random = Random.Default) = mixArtwork(tracks, random) {
        it.album to albumGroupingArtist(it.albumArtist, it.artist)
    }

    @Test fun songsAndGuestCreditsFromTheSameAlbumSupplyOneCover() {
        val tracks = listOf(
            track("KING", "BULLY", "Kanye West, Ye"),
            track("Song", "BULLY", "Kanye West, Ye, Guest"),
            track("Another", " bully ", "Kanye West", "Kanye West"),
            track("Hold My Liquor", "Yeezus", "Kanye West"),
            track("Blood On The Leaves", "Yeezus", "Kanye West"),
            track("Make It Easy", "Make It Easy", "Cage The Elephant"),
        )
        repeat(20) { seed ->
            val covers = artwork(tracks, Random(seed))
            assertEquals(3, covers.size)
            assertEquals(3, covers.map { it.album.trim().lowercase() }.distinct().size)
            assertTrue(covers.all { it in tracks })
        }
    }

    @Test fun fewerAlbumsLeaveSlotsEmptyInsteadOfRepeatingCovers() {
        assertTrue(artwork(emptyList()).isEmpty())
        val album = List(8) { track("Song $it", "One album", "Artist") }
        assertEquals(1, artwork(album).size)
        assertEquals(2, artwork(album + track("Song", "Second album", "Artist")).size)
    }

    @Test fun matchingAlbumNamesByDifferentArtistsRemainSeparate() {
        val tracks = listOf(track("A", "Greatest Hits", "Artist A"), track("B", "Greatest Hits", "Artist B"))
        assertEquals(2, artwork(tracks).size)
    }
}
