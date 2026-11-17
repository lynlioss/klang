@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.*
import app.lynli.klang.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Resolve on the loader thread; cached audio and offline files take precedence over the network. */
internal fun musicSourceDataSource(context: Context): DataSource.Factory = sourceFactory(context, cacheAudio = true)

internal fun sourceFactory(context: Context, cacheAudio: Boolean): DataSource.Factory {
    val store = SourceMediaStore.get(context)
    val streams = SourceStreams.get(context)
    val network = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(8_000).setReadTimeoutMs(10_000))
    return DataSource.Factory {
        object : DataSource {
            private var source: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()
            private fun use(factory: DataSource.Factory): DataSource = factory.createDataSource().also {
                listeners.forEach(it::addTransferListener); source = it
            }

            override fun addTransferListener(listener: TransferListener) {
                listeners += listener; source?.addTransferListener(listener)
            }

            override fun open(spec: DataSpec): Long {
                if (spec.uri.scheme != "klang-source") return use(network).open(spec)
                val identity = spec.uri.toString()
                store.ensureDeviceLibrary()
                store.offlineUri(identity)?.let { return use(network).open(spec.withUri(it)) }
                try {
                    return openResolvedStream(resolve = { refresh ->
                        if (refresh) streams.reject(identity)
                        val result = runBlocking(Dispatchers.IO) { streams.resolve(identity) }
                        val (url, headers) = MusicSourceProtocol.stream(result)
                        spec.withUri(Uri.parse(url)).withAdditionalHeaders(headers).buildUpon()
                            .setKey(result.getString("_klangCacheKey")).build()
                    }, open = { resolved ->
                        if (cacheAudio) SourceAudioCache.get(context).register(identity, requireNotNull(resolved.key))
                        val factory = if (cacheAudio) SourceAudioCache.get(context).factory(network) else network
                        use(factory).open(resolved)
                    }, close = { close() })
                } catch (e: IOException) { throw e }
                catch (e: Exception) { throw IOException(e.message ?: "Couldn't open this song", e) }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = requireNotNull(source).read(buffer, offset, length)
            override fun getUri(): Uri? = source?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()
            override fun close() { val opened = source; source = null; opened?.close() }
        }
    }
}

/** A rejected/expired URL is refreshed once at the same byte position, including after a seek. */
internal fun <T> openResolvedStream(resolve: (Boolean) -> T, open: (T) -> Long, close: () -> Unit,
    refreshable: (IOException) -> Boolean = { it is HttpDataSource.InvalidResponseCodeException && sourceResponseNeedsRefresh(it.responseCode) }): Long {
    try { return open(resolve(false)) }
    catch (error: IOException) {
        if (!refreshable(error)) throw error
        close()
        return open(resolve(true))
    }
}

internal fun sourceResponseNeedsRefresh(code: Int): Boolean = code in setOf(401, 403, 404, 410)
