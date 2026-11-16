package app.lynli.klang.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class SourcePlaybackRecoveryTest {
    private val song = "klang-source://spotify/song"
    private val networkError = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    @Test fun retriesAreBoundedAndPausedDeviceOrDecoderFailuresAreLeftAlone() {
        val recovery = SourcePlaybackRecovery()
        assertNull(recovery.retry(song, false, networkError))
        assertNull(recovery.retry("content://music/1", true, networkError))
        assertNull(recovery.retry(song, true, PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(300L, recovery.retry(song, true, networkError))
        assertEquals(1_000L, recovery.retry(song, true, networkError))
        assertNull(recovery.retry(song, true, networkError))
        recovery.stable()
        assertEquals(300L, recovery.retry(song, true, networkError))
        assertEquals(300L, recovery.retry("klang-source://spotify/next", true, networkError))
    }
}
