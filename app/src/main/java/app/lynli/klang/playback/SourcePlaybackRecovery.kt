package app.lynli.klang.playback

import androidx.media3.common.PlaybackException

/** Retry only source/network failures, bounded per song and reset after stable playback. */
internal class SourcePlaybackRecovery {
    private var identity: String? = null
    private var attempts = 0
    fun song(id: String?) { if (identity != id) { identity = id; attempts = 0 } }
    fun stable() { attempts = 0 }
    fun retry(id: String?, playWhenReady: Boolean, errorCode: Int): Long? {
        song(id)
        if (id?.startsWith("klang-source:") != true || !playWhenReady || attempts >= 2 ||
            errorCode !in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED)) return null
        return if (++attempts == 1) 300L else 1_000L
    }
}
