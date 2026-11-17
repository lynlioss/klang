@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import app.lynli.klang.plugins.MusicSourceProtocol
import app.lynli.klang.plugins.SourceStreams
import app.lynli.klang.plugins.StreamPreparation
import app.lynli.klang.plugins.SourceMediaStore
import kotlinx.coroutines.*
import java.io.File

/** Evictable playback bytes are separate from user-requested offline downloads. */
internal class SourceAudioCache private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache by lazy {
        SimpleCache(File(context.cacheDir, "source-audio"), LeastRecentlyUsedCacheEvictor(96L * 1024 * 1024),
            StandaloneDatabaseProvider(context))
    }
    private val preparation = StreamPreparation(scope, { prefetch(it) }, concurrency = 1, capacity = 12)
    private val prepared = linkedSetOf<String>()
    private val recent = mutableMapOf<String, Long>()
    private val readers = mutableMapOf<String, DataSource>()
    private var generation = 0L

    fun factory(upstream: DataSource.Factory): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory { it.key ?: app.lynli.klang.plugins.sourceAudioKey("public", org.json.JSONObject().put("url", it.uri.toString())) }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Fetch bounded audio prefixes sequentially; later neighbors stay queued while a reader runs. */
    fun prepare(ids: List<String>) {
        val now = System.currentTimeMillis()
        val targets = synchronized(this) {
            recent.entries.removeAll { now - it.value > 60_000 }
            ids.distinct().filter { it.startsWith("klang-source:") && it !in recent }.take(4)
        }
        preparation.pause(false)
        preparation.offer(targets, priority = true)
    }
    private suspend fun prefetch(id: String) {
        if (SourceMediaStore.get(context).offlineUri(id) != null) return
        val captured = synchronized(this) { generation }
        val result = SourceStreams.get(context).resolve(id)
        val (url, headers) = MusicSourceProtocol.stream(result)
        val key = result.getString("_klangCacheKey")
        register(id, key)
        if (!cache.isCached(key, 0, PREFIX_BYTES)) {
            val spec = DataSpec.Builder().setUri(url).setKey(key).setHttpRequestHeaders(headers).setLength(PREFIX_BYTES).build()
            val reader = factory(sourceFactory(context, cacheAudio = false)).createDataSource()
            synchronized(this) { readers[id] = reader }
            try { runInterruptible { cacheAudioPrefix({ reader.open(spec) }, reader::read, reader::close, PREFIX_BYTES) } }
            finally { synchronized(this) { readers.remove(id) } }
        }
        synchronized(this) {
            if (captured == generation) { prepared.add(key); recent[id] = System.currentTimeMillis() }
            while (prepared.size > 128) prepared.remove(prepared.first())
        }
    }
    fun register(id: String, key: String) {
        val provider = java.net.URI(id).rawAuthority ?: return
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().set("klang.provider", provider).set("klang.track", id))
    }
    fun stats(provider: String): org.json.JSONObject {
        val keys = cache.keys.filter { cache.getContentMetadata(it).get("klang.provider", "") == provider }
        val populated = keys.filter { cache.getCachedSpans(it).isNotEmpty() }
        return org.json.JSONObject().put("bytes", populated.sumOf { key -> cache.getCachedSpans(key).sumOf { it.length } })
            .put("songs", populated.map { cache.getContentMetadata(it).get("klang.track", "") }.filter(String::isNotBlank).distinct().size)
    }
    fun clear(provider: String) {
        invalidate()
        cache.keys.filter { cache.getContentMetadata(it).get("klang.provider", "") == provider }.forEach(cache::removeResource)
    }
    @Synchronized fun cancelPreparation() {
        preparation.pause(true)
        val opened = readers.values.toList()
        scope.launch { opened.forEach { runCatching { it.close() } } }
    }

    @Synchronized fun invalidate() { generation++; prepared.clear(); recent.clear(); preparation.clear(); cancelPreparation() }

    companion object {
        private const val PREFIX_BYTES = 384L * 1024
        private var instance: SourceAudioCache? = null
        @Synchronized fun get(context: Context) = instance ?: SourceAudioCache(context.applicationContext).also { instance = it }
    }
}

/** A failed bounded range must never be retried as an unbounded speculative download. */
internal fun cacheAudioPrefix(open: () -> Unit, read: (ByteArray, Int, Int) -> Int, close: () -> Unit, maximumBytes: Long) {
    try {
        open()
        val buffer = ByteArray(16 * 1024)
        var remaining = maximumBytes
        while (remaining > 0 && !Thread.currentThread().isInterrupted) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            if (count == 0) throw java.io.IOException("The stream returned no audio bytes.")
            remaining -= count
        }
    } finally { close() }
}
