package app.lynli.klang.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.lynli.klang.data.audioFileIds

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

/** Remove every queued copy; Media3 advances a deleted current song and preserves other playback. */
internal fun Player.removeDeletedAudio(trackId: String): Boolean {
    val ids = audioFileIds(trackId)
    val indices = (0 until mediaItemCount).filter { getMediaItemAt(it).mediaId in ids }
    if (indices.isEmpty()) return false
    if (indices.size == mediaItemCount) {
        stop()
        clearMediaItems()
        setPlaylistMetadata(MediaMetadata.EMPTY)
    } else indices.asReversed().forEach(::removeMediaItem)
    return true
}
