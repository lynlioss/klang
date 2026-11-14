package app.lynli.klang.lyrics

data class LyricsQuery(val title: String, val artist: String, val album: String, val durationMs: Long)
data class LyricWord(val text: String, val startMs: Long, val endMs: Long) {
    fun progress(positionMs: Long): Float = ((positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
}
data class LyricLine(val text: String, val startMs: Long, val endMs: Long, val words: List<LyricWord> = emptyList())
data class LyricsDocument(val providerId: String, val plain: String, val lines: List<LyricLine>, val instrumental: Boolean = false) {
    val synchronized get() = lines.isNotEmpty()
    val hasWordTiming get() = lines.any { it.words.isNotEmpty() }
    fun activeLine(positionMs: Long): Int {
        // Choose the most recently started vocal, including overlapping lines.
        return lines.indexOfLast { it.startMs <= positionMs && positionMs < it.endMs }
    }
}

internal val LyricsDocument.timingQuality: Int
    get() = when {
        hasWordTiming -> 2
        synchronized -> 1
        else -> 0
    }

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data object Disabled : LyricsState
    data object NotFound : LyricsState
    data class Ready(val document: LyricsDocument) : LyricsState
    data class Error(val message: String) : LyricsState
}

interface LyricsProvider {
    val id: String
    suspend fun find(query: LyricsQuery): LyricsDocument?
}
