package app.lynli.klang.lyrics

/** The string a timed line actually shows; word timings index into exactly this text. */
val LyricLine.displayText: String
    get() = when {
        text.isNotBlank() && words.isEmpty() -> text
        words.isNotEmpty() -> words.joinToString("") { it.text }
        else -> "♪  ♪  ♪"
    }

/**
 * Continuous per-character sung amount at [timeMs], with no hidden timing offset. Inside a
 * word the progress is spread across its letters, so the last letter completes at the word end.
 */
fun LyricLine.charFill(timeMs: Long): FloatArray {
    val shown = displayText
    val out = FloatArray(shown.length)
    if (words.isEmpty()) return out
    var offset = 0
    for (word in words) {
        val progress = word.progress(timeMs)
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
