@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.plugins

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ExtensionQueueTest {
    @Test fun appendAndReorderKeepCurrentAndPreparedNextWithoutResettingPlayer() {
        fun item(id: String) = MediaItem.Builder().setMediaId(id).build()
        val current = item("current"); val next = item("next")
        val entries = mutableListOf(current, next)
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "getMediaItemCount" -> entries.size
                "getMediaItemAt" -> entries[args[0] as Int]
                "addMediaItem" -> { entries.add(args[0] as Int, args[1] as MediaItem); null }
                "moveMediaItem" -> { entries.add(args[1] as Int, entries.removeAt(args[0] as Int)); null }
                "replaceMediaItem" -> { entries[args[0] as Int] = args[1] as MediaItem; null }
                "removeMediaItems" -> { entries.subList(args[0] as Int, args[1] as Int).clear(); null }
                else -> error("Queue reconciliation must not call ${method.name}")
            }
        } as Player
        val added = item("member")
        reconcileExtensionQueue(player, listOf(current, next, added))
        assertSame(current, entries[0]); assertSame(next, entries[1])
        reconcileExtensionQueue(player, listOf(current, added, next))
        assertSame(current, entries[0]); assertSame(next, entries[2])
        reconcileExtensionQueue(player, emptyList()); assertTrue(entries.isEmpty())
    }
}
