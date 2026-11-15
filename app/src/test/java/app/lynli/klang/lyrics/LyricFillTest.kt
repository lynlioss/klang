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

    @Test fun charactersLightUpInOrderInsideAWord() {
        val half = line.charFill(500)
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
        val nearEnd = line.charFill(1000)
        assertEquals(1f, nearEnd[4], 0.001f)
        // The separator between words is never lit.
        assertEquals(0f, nearEnd[5], 0.001f)
        // The next word has not started yet.
        assertEquals(0f, nearEnd[6], 0.001f)
        val done = line.charFill(2000)
        (6..10).forEach { assertEquals(1f, done[it], 0.001f) }
    }

    @Test fun aLineAheadOfTheClockStaysDark() {
        assertTrue(line.charFill(-5000).all { it == 0f })
        assertTrue(line.charFill(-5000).size == line.displayText.length)
    }

    @Test fun wordsStartAtTheirTimestampWithoutAnImplicitLead() {
        val start = line.charFill(0)
        assertTrue(start.all { it == 0f })
        assertEquals(0f, line.charFill(999)[6], 0f)
        assertEquals(0f, line.charFill(1000)[6], 0f)
        assertTrue(line.charFill(1001)[6] > 0f)
    }

    @Test fun shortWordsFillContinuouslyBetweenFramesAndRewindOnSeek() {
        val short = LyricLine("Hey", 1000, 1150, listOf(LyricWord("Hey", 1000, 1150)))
        val first = short.charFill(1016)[0]
        val second = short.charFill(1032)[0]
        assertTrue(first > 0f && first < second && second < 1f)
        assertTrue(short.charFill(1150).all { it == 1f })
        assertTrue(short.charFill(999).all { it == 0f })
    }

    @Test fun lineWithoutWordTimingsHasNoKaraoke() {
        val plain = LyricLine("Just a line", 0, 1000)
        assertEquals("Just a line", plain.displayText)
        assertTrue(plain.charFill(500).all { it == 0f })
    }
}
