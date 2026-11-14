package app.lynli.klang.ui

import org.junit.Assert.*
import org.junit.Test

class LyricsInteractionTest {
    @Test fun controlsHideAfterFiveSecondsAndTouchRestoresThem() {
        val state = LyricsControlsState(1000)
        assertTrue(state.hideIfIdle(1000 + LYRICS_CONTROLS_TIMEOUT_MS - 1).visible)
        val hidden = state.hideIfIdle(1000 + LYRICS_CONTROLS_TIMEOUT_MS)
        assertFalse(hidden.visible)
        val touched = hidden.touchStarted(5000)
        assertTrue(touched.visible)
        assertTrue(touched.touching)
        // Touching keeps them on screen no matter how long the finger rests.
        assertTrue(touched.hideIfIdle(9000).visible)
        val released = touched.touchEnded(9500)
        assertTrue(released.hideIfIdle(9500 + LYRICS_CONTROLS_TIMEOUT_MS - 1).visible)
        assertFalse(released.hideIfIdle(9500 + LYRICS_CONTROLS_TIMEOUT_MS).visible)
    }

    @Test fun aNewTouchRestartsTheInactivityTimeout() {
        val initial = LyricsControlsState(0)
        val touched = initial.touchStarted(2500).touchEnded(2600)
        assertTrue(touched.hideIfIdle(5000).visible)
        assertFalse(touched.hideIfIdle(2600 + LYRICS_CONTROLS_TIMEOUT_MS).visible)
    }
}
