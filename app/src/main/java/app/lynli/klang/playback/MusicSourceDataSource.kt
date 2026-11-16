@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import app.lynli.klang.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.IOException

/** Resolves on Media3's loader thread, immediately before opening the stream or a seek. */
internal fun musicSourceDataSource(context: Context): DataSource.Factory {
    val store = SourceMediaStore.get(context)
    return ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { spec ->
        if (spec.uri.scheme != "klang-source") spec else try {
            val local = store.offlineUri(spec.uri.toString())
            if (local != null) spec.withUri(local) else {
                val registry = PluginRegistry(context)
                val enabled = PluginStore(context) { registry.manifests }.read().enabled
                val plugin = registry.entries.firstOrNull { it.manifest.id == spec.uri.authority && it.manifest.id in enabled }
                    ?: error("Enable this music source in Plugins, or download the song for offline playback.")
                require("stream.resolve" in requireNotNull(plugin.manifest.musicSource).capabilities)
                val result = runBlocking(Dispatchers.IO) {
                    MusicSourceClient.call(context, plugin, "stream.resolve", JSONObject().put("item", store.item(spec.uri.toString())))
                }
                val (url, headers) = MusicSourceProtocol.stream(result)
                spec.withUri(Uri.parse(url)).withAdditionalHeaders(headers)
            }
        } catch (e: Exception) { throw IOException(e.message ?: "Couldn't open this song", e) }
    }
}
