package app.lynli.klang.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlaybackClockTest {
    @Test fun smallControllerCorrectionsDoNotRewindOrJumpAtTheLineBoundary() {
        val clock = LyricsPlaybackClock()
        clock.update(990, 0, true, 0)
        val boundary = clock.update(1006, 16_000_000, true, 0)
        val corrected = clock.update(970, 32_000_000, true, 0)
        assertTrue(boundary >= 1000)
        assertTrue(corrected > boundary)
        assertTrue(corrected - boundary < 20)
    }

    @Test fun explicitSmallBackwardSeekResetsImmediately() {
        val clock = LyricsPlaybackClock()
        clock.update(1100, 0, true, 0)
        assertEquals(1000L, clock.update(1000, 16_000_000, true, 1))
    }

    @Test fun pausingAndBufferingFreezeTheCursorAndResumeWithoutCatchup() {
        val clock = LyricsPlaybackClock()
        clock.update(1000, 0, true, 0)
        assertEquals(1010L, clock.update(1010, 16_000_000, false, 0))
        assertEquals(1010L, clock.update(1010, 1_016_000_000, false, 0))
        assertEquals(1010L, clock.update(1010, 2_016_000_000, true, 0))
        assertEquals(1026L, clock.update(1026, 2_032_000_000, true, 0))
    }

    @Test fun largeTimelineCorrectionDoesNotAnimateThroughUnrelatedLyrics() {
        val clock = LyricsPlaybackClock()
        clock.update(1000, 0, true, 0)
        assertEquals(30_000L, clock.update(30_000, 16_000_000, true, 0))
    }
}
