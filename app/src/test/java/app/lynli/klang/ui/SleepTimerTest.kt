package app.lynli.klang.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test fun countdownShowsSecondsBelowAnHour() {
        assertEquals("0:05", remainingText(5_000))
        assertEquals("12:34", remainingText(754_000))
    }

    @Test fun countdownShowsHoursWhenNeeded() {
        assertEquals("1:00:00", remainingText(3_600_000))
        assertEquals("2:03:04", remainingText(7_384_000))
    }

    @Test fun expiredCountdownsStayAtZero() {
        assertEquals("0:00", remainingText(-10_000))
        assertEquals("0:00", remainingText(0))
    }
}
