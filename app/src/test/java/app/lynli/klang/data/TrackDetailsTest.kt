package app.lynli.klang.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackDetailsTest {
    @Test fun formatsComeFromMimeTypesAndFilenames() {
        assertEquals("MP3", formatLabel("audio/mpeg", "song.mp3"))
        assertEquals("M4A", formatLabel("audio/mp4", "song.m4a"))
        assertEquals("FLAC", formatLabel("audio/flac", "song.flac"))
        assertEquals("OGG", formatLabel(null, "track.ogg"))
        assertEquals("OPUS", formatLabel("audio/opus", "track.opus"))
        assertEquals("WAV", formatLabel("audio/x-wav", "track.bin"))
        assertNull(formatLabel(null, "no-extension"))
    }

    @Test fun qualityPrefersLosslessThenBitrateBands() {
        fun details(mime: String?, kbps: Int?) = TrackDetails(0, null, mime, kbps, null, null, null)
        assertEquals("Lossless", details("audio/flac", 900).quality)
        assertEquals("Lossless", details("audio/x-wav", 1_411).quality)
        assertEquals("High", details("audio/mpeg", 320).quality)
        assertEquals("Standard", details("audio/mpeg", 192).quality)
        assertEquals("Low", details("audio/mpeg", 96).quality)
        assertNull(details("audio/mpeg", null).quality)
    }
}
