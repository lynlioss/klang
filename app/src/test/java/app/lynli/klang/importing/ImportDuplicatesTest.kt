package app.lynli.klang.importing

import org.junit.Assert.*
import org.junit.Test

class ImportDuplicatesTest {
    @Test fun sameRecordingRequiresMatchingTitleArtistAndCloseDuration() {
        assertTrue(sameRecording("ELEMENT.", "Kendrick Lamar", 209_000, "element", "Kendrick Lamar", 208_733))
        assertFalse(sameRecording("ELEMENT.", "Kendrick Lamar", 209_000, "ELEMENT. (Live)", "Kendrick Lamar", 209_000))
        assertFalse(sameRecording("ELEMENT.", "Kendrick Lamar", 209_000, "ELEMENT.", "Cover Artist", 209_000))
        assertFalse(sameRecording("ELEMENT.", "Kendrick Lamar", 209_000, "ELEMENT.", "Kendrick Lamar", 240_000))
        assertFalse(sameRecording("ELEMENT.", "Kendrick Lamar", 0, "ELEMENT.", "Kendrick Lamar", 209_000))
    }
    @Test fun presentationTextAndUnicodeDoNotHideADuplicate() {
        assertTrue(sameRecording("hey now (Official Audio)", "Kendrick Lamar", 218_000, "hey now", "Kendrick Lamar", 217_000))
        assertTrue(sameRecording("Ｆｉｒｅ", "Artist", 120_000, "Fire", "artist", 120_000))
    }
}
