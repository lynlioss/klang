package app.lynli.klang.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

/** Native adjacent-item commands respect shuffle/repeat and preserve the paused/playing state. */
internal fun Player.skipPlaybackTrack(expectedTrackId: String, forward: Boolean): Boolean {
    if (currentMediaItem?.mediaId != expectedTrackId) return false
    if (forward) {
        if (!hasNextMediaItem()) return false
        seekToNextMediaItem()
    } else {
        if (!hasPreviousMediaItem()) return false
        seekToPreviousMediaItem()
    }
    return true
}

/** Recheck the live player so buffering or a resume during the dismiss gesture cannot stop music. */
internal fun Player.dismissPausedPlayback(expectedTrackId: String): Boolean {
    if (currentMediaItem?.mediaId != expectedTrackId || isPlaying || playWhenReady) return false
    stop()
    clearMediaItems()
    setPlaylistMetadata(MediaMetadata.EMPTY)
    return true
}
