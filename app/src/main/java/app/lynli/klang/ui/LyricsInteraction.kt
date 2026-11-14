package app.lynli.klang.ui

internal const val LYRICS_CONTROLS_TIMEOUT_MS = 5000L

internal data class LyricsControlsState(val lastInteractionMs: Long, val visible: Boolean = true, val touching: Boolean = false) {
    fun touchStarted(nowMs: Long) = copy(lastInteractionMs = nowMs, visible = true, touching = true)
    fun touchEnded(nowMs: Long) = copy(lastInteractionMs = nowMs, visible = true, touching = false)
    fun hideIfIdle(nowMs: Long) = if (!touching && nowMs - lastInteractionMs >= LYRICS_CONTROLS_TIMEOUT_MS) copy(visible = false) else this
}
