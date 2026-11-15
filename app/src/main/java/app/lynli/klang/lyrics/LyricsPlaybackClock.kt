package app.lynli.klang.lyrics

import kotlin.math.abs
import kotlin.math.roundToLong

/** A frame clock that absorbs small MediaController corrections without reversing the karaoke. */
internal class LyricsPlaybackClock {
    private var position = 0.0
    private var frameNanos: Long? = null
    private var discontinuity = -1L
    private var running = false

    fun update(reportedMs: Long, nowNanos: Long, playing: Boolean, revision: Long): Long {
        val previousFrame = frameNanos
        val elapsedMs = previousFrame?.let { ((nowNanos - it) / 1_000_000.0).coerceAtLeast(0.0) } ?: 0.0
        val reported = reportedMs.coerceAtLeast(0).toDouble()
        if (previousFrame == null || revision != discontinuity || !playing || !running) {
            position = reported
        } else {
            val expected = position + elapsedMs
            val error = reported - expected
            // Large corrections are real timeline changes. Small transport corrections are
            // absorbed gradually; the reading cursor continues forwards at a steady rate.
            position = if (abs(error) > 250.0) reported
                else expected + error.coerceIn(-elapsedMs * 0.15, elapsedMs * 0.15)
        }
        frameNanos = nowNanos
        discontinuity = revision
        running = playing
        return position.roundToLong()
    }
}
