package app.lynli.klang.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal const val PLAYBACK_SOURCE_KEY = "app.lynli.klang.PLAYBACK_SOURCE"

/** Shared tracks do not make a different playlist the owner of the active queue. */
internal fun canResumeCollection(activeSource: String?, requestedSource: String, hasCurrentItem: Boolean): Boolean =
    hasCurrentItem && activeSource != null && activeSource == requestedSource

/**
 * Stamp the originating collection onto the item itself. Playlist metadata alone does not always
 * survive the trip through a [androidx.media3.session.MediaController], which left the big
 * collection play button out of sync with a queue it had just started.
 */
internal fun MediaItem.withPlaybackSource(sourceId: String?): MediaItem = buildUpon()
    .setMediaMetadata(mediaMetadata.buildUpon().setExtras(Bundle().apply {
        sourceId?.let { putString(PLAYBACK_SOURCE_KEY, it) }
    }).build()).build()

/** Source of the active queue: the current item first, then the playlist-level copy. */
internal fun Player.playbackSourceOrNull(): String? =
    currentMediaItem?.mediaMetadata?.extras?.getString(PLAYBACK_SOURCE_KEY)
        ?: playlistMetadata.extras?.getString(PLAYBACK_SOURCE_KEY)
