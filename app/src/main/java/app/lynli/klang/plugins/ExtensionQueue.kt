@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.plugins

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Preserve current playback and prepared successors while updating an extension queue. */
internal fun reconcileExtensionQueue(player: Player, items: List<MediaItem>) {
    for (index in items.indices) {
        val item = items[index]
        val existing = (index until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).let { previous -> previous.mediaId == item.mediaId ||
            previous.mediaMetadata.extras?.containsKey("extensionHash") != true &&
                previous.localConfiguration?.uri == item.localConfiguration?.uri && item.localConfiguration?.uri?.scheme != "klang-pending" } }
        if (existing == null) player.addMediaItem(index, item)
        else {
            if (existing != index) player.moveMediaItem(existing, index)
            val previous = player.getMediaItemAt(index)
            if (previous != item) player.replaceMediaItem(index, item)
        }
    }
    if (player.mediaItemCount > items.size) player.removeMediaItems(items.size, player.mediaItemCount)
}
