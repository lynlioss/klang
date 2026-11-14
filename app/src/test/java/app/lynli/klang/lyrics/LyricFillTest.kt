package app.lynli.klang.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricFillTest {
    private val words = listOf(
        LyricWord("Hello ", 0, 1000),
        LyricWord("world", 1000, 2000),
    )
    private val line = LyricLine("Hello world", 0, 2000, words)

    // The fill runs FILL_LEAD_MS ahead of the clock, so the audible time is `timeMs + 80`.
    private fun atAudible(ms: Long) = line.charFill(ms - 80)

    @Test fun charactersLightUpInOrderInsideAWord() {
        val half = atAudible(500)
        // "Hello " has five letters, so half the word lights two letters and half of a third.
        assertEquals(1f, half[0], 0.001f)
        assertEquals(1f, half[1], 0.001f)
        assertEquals(0.5f, half[2], 0.001f)
        assertEquals(0f, half[3], 0.001f)
        // The trailing space never lights.
        assertEquals(0f, half[5], 0.001f)
        assertTrue(half[6] == 0f)
    }

    @Test fun theLastLetterCompletesAtTheWordEnd() {
        val nearEnd = atAudible(1000)
        assertEquals(1f, nearEnd[4], 0.001f)
        // The separator between words is never lit.
        assertEquals(0f, nearEnd[5], 0.001f)
        // The next word has not started yet.
        assertEquals(0f, nearEnd[6], 0.001f)
        val done = atAudible(2000)
        (6..10).forEach { assertEquals(1f, done[it], 0.001f) }
    }

    @Test fun aLineAheadOfTheClockStaysDark() {
        assertTrue(line.charFill(-5000).all { it == 0f })
        assertTrue(line.charFill(-5000).size == line.displayText.length)
    }

    @Test fun theFirstLetterIsAlreadyMovingWhenTheWordStarts() {
        // The 80ms lead means a word is a fraction in at its own start, which is what makes the
        // wipe read in time with the voice instead of lagging behind it.
        val start = line.charFill(0)
        assertEquals(0.4f, start[0], 0.001f)
        assertEquals(0f, start[1], 0.001f)
    }

    @Test fun leadingEdgeStaysOnWholeSteps() {
        val steps = line.charFillSteps(500 - 80)
        assertTrue(steps.all { it * FILL_STEPS == (it * FILL_STEPS).toInt().toFloat() })
        assertEquals(0.5f, steps[2], 0.001f)
    }

    @Test fun lineWithoutWordTimingsHasNoKaraoke() {
        val plain = LyricLine("Just a line", 0, 1000)
        assertEquals("Just a line", plain.displayText)
        assertTrue(plain.charFill(500).all { it == 0f })
    }
}
