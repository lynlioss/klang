package app.lynli.klang.data

import org.junit.Assert.*
import org.junit.Test

class AlbumIdentityTest {
    @Test fun guestCreditsInEarlierImportsDoNotSplitBullyDeluxe() {
        val credits = listOf("Kanye West, Ye", "Kanye West, Ye, Don Toliver", "Kanye West, Ye, Peso Pluma",
            "Kanye West, Ye, The WRLDFMS Tony Williams")
        assertEquals(setOf("Kanye West"), credits.map { albumGroupingArtist(it, it) }.toSet())
        assertEquals("Kanye West", albumGroupingArtist("", "Kanye West feat. Don Toliver"))
    }
    @Test fun albumArtistGroupsCompilationAndGuestTracksWithoutCombiningOtherArtists() {
        assertEquals("Various Artists", albumGroupingArtist("Various Artists", "Singer, Guest"))
        assertEquals("Kanye West", albumGroupingArtist("Kanye West", "Kanye West, Ye, Don Toliver"))
        assertNotEquals(albumGroupingArtist("", "Artist A"), albumGroupingArtist("", "Artist B"))
        assertEquals("Earth, Wind & Fire", albumGroupingArtist("Earth, Wind & Fire", "Earth, Wind & Fire, Guest"))
    }
}
