package app.lynli.klang.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackVisibilityTest {
    @Test fun clippedRowOnlyReplacesMiniPlayerWhenControlsAreOnScreen() {
        assertTrue(controlsAreVisible(400, 80, 0, 800))
        assertTrue(controlsAreVisible(-20, 80, 0, 800))
        assertFalse(controlsAreVisible(-61, 80, 0, 800))
        assertTrue(controlsAreVisible(740, 80, 0, 800))
        assertFalse(controlsAreVisible(781, 80, 0, 800))
    }
    @Test fun offscreenAndUnmeasuredRowsKeepMiniPlayerAvailable() {
        assertFalse(controlsAreVisible(0, 0, 0, 800))
        assertFalse(controlsAreVisible(0, 80, 0, 0))
        assertFalse(controlsAreVisible(-100, 80, 0, 800))
        assertFalse(controlsAreVisible(800, 80, 0, 800))
    }
    @Test fun viewportChangesAccountForInsetsAndResizing() {
        assertTrue(controlsAreVisible(500, 80, -24, 800))
        assertFalse(controlsAreVisible(500, 80, -24, 550))
        assertTrue(controlsAreVisible(0, 80, -24, 600))
    }
}
