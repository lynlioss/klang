package app.lynli.klang.lyrics

import kotlin.math.roundToInt

/**
 * Karaoke fill, the way Aurora does it: every character has its own sung amount in `0..1`, so a
 * word lights up letter by letter instead of being uncovered by a moving rectangle. The leading
 * character carries the soft edge, which is what makes the wipe read as singing.
 */
private const val FILL_LEAD_MS = 80L

/** Characters are lit in quarter steps so the line re-lays out only when its look changes. */
internal const val FILL_STEPS = 4f

/** The string a timed line actually shows; word timings index into exactly this text. */
val LyricLine.displayText: String
    get() = when {
        text.isNotBlank() && words.isEmpty() -> text
        words.isNotEmpty() -> words.joinToString("") { it.text }
        else -> "♪  ♪  ♪"
    }

/**
 * Per-character sung amount at [timeMs]. Outside a word's span characters stay dark; inside a
 * word the progress is spread across its letters, so the last letter completes at the word end.
 */
fun LyricLine.charFill(timeMs: Long): FloatArray {
    val shown = displayText
    val out = FloatArray(shown.length)
    if (words.isEmpty()) return out
    // Slightly ahead of the reported clock so the wipe reads in time with the audio.
    val t = timeMs + FILL_LEAD_MS
    var offset = 0
    for (word in words) {
        val progress = word.progress(t)
        val letters = word.text.trimEnd().length.coerceAtLeast(1)
        for (i in word.text.indices) {
            val index = offset + i
            if (index >= out.size) break
            out[index] = (progress * letters - i).coerceIn(0f, 1f)
        }
        offset += word.text.length
    }
    return out
}

/** Quarter-character steps with structural equality, so composition is stable between changes. */
fun LyricLine.charFillSteps(timeMs: Long): List<Float> =
    charFill(timeMs).map { (it * FILL_STEPS).roundToInt() / FILL_STEPS }
