package app.lynli.klang.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class PlaybackControlsTest {
    private class Playback {
        var current: MediaItem? = MediaItem.Builder().setMediaId("current").build()
        var playing = false
        var playWhenReady = false
        var hasPrevious = true
        var hasNext = true
        var metadata: MediaMetadata? = null
        val commands = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> current
                "isPlaying" -> playing
                "getPlayWhenReady" -> playWhenReady
                "getCurrentPosition" -> 80_000L
                "hasPreviousMediaItem" -> hasPrevious
                "hasNextMediaItem" -> hasNext
                "seekToPreviousMediaItem", "seekToNextMediaItem", "stop" -> { commands += method.name; null }
                "clearMediaItems" -> { commands += method.name; current = null; null }
                "setPlaylistMetadata" -> { commands += method.name; metadata = args!![0] as MediaMetadata; null }
                else -> error("Unexpected playback command: ${method.name}")
            }
        } as Player
    }

    @Test fun previousSwipeSelectsPreviousItemEvenAfterEightySeconds() {
        val playback = Playback()
        assertTrue(playback.player.skipPlaybackTrack("current", forward = false))
        assertEquals(listOf("seekToPreviousMediaItem"), playback.commands)
        assertFalse(playback.playWhenReady)
    }

    @Test fun nextSwipeKeepsPlaybackRunningWithoutRestartingTheQueue() {
        val playback = Playback().apply { playing = true; playWhenReady = true }
        assertTrue(playback.player.skipPlaybackTrack("current", forward = true))
        assertEquals(listOf("seekToNextMediaItem"), playback.commands)
        assertTrue(playback.playing)
        assertTrue(playback.playWhenReady)
    }

    @Test fun boundariesAndTrackChangesCancelPendingSwipes() {
        val playback = Playback().apply { hasPrevious = false; hasNext = false }
        assertFalse(playback.player.skipPlaybackTrack("current", forward = false))
        assertFalse(playback.player.skipPlaybackTrack("current", forward = true))
        assertFalse(playback.player.skipPlaybackTrack("old", forward = true))
        assertTrue(playback.commands.isEmpty())
    }

    @Test fun pausedDismissStopsAndClearsQueueAndCollectionMetadata() {
        val playback = Playback()
        assertTrue(playback.player.dismissPausedPlayback("current"))
        assertEquals(listOf("stop", "clearMediaItems", "setPlaylistMetadata"), playback.commands)
        assertNull(playback.current)
        assertEquals(MediaMetadata.EMPTY, playback.metadata)
    }

    @Test fun resumedOrBufferingPlaybackCannotBeDismissed() {
        val playing = Playback().apply { playing = true }
        val buffering = Playback().apply { playWhenReady = true }
        assertFalse(playing.player.dismissPausedPlayback("current"))
        assertFalse(buffering.player.dismissPausedPlayback("current"))
        assertTrue(playing.commands.isEmpty())
        assertTrue(buffering.commands.isEmpty())
    }

    @Test fun dismissalCannotClearAQueueThatChangedDuringTheAnimation() {
        val playback = Playback()
        assertFalse(playback.player.dismissPausedPlayback("old"))
        playback.current = null
        assertFalse(playback.player.dismissPausedPlayback("current"))
        assertTrue(playback.commands.isEmpty())
    }
}
