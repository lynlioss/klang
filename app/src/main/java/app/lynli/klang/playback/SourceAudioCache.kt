@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import app.lynli.klang.plugins.MusicSourceProtocol
import app.lynli.klang.plugins.SourceStreams
import kotlinx.coroutines.*
import java.io.File

/** Evictable playback bytes are separate from user-requested offline downloads. */
internal class SourceAudioCache private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache by lazy {
        SimpleCache(File(context.cacheDir, "source-audio"), LeastRecentlyUsedCacheEvictor(96L * 1024 * 1024),
            StandaloneDatabaseProvider(context))
    }
    private val active = mutableMapOf<String, Job>()
    private val prepared = linkedSetOf<String>()
    private val recent = mutableMapOf<String, Long>()
    private val readers = mutableMapOf<String, DataSource>()
    private var generation = 0L

    fun factory(upstream: DataSource.Factory): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory { it.key ?: app.lynli.klang.plugins.sourceAudioKey("public", org.json.JSONObject().put("url", it.uri.toString())) }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Fetch at most 192 KiB per upcoming song, after current playback is ready. */
    @Synchronized fun prepare(ids: List<String>) {
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > 30_000 }
        for (id in ids.distinct().filter { it.startsWith("klang-source:") }.take(2)) {
            if (id in active || id in recent || active.size >= 1) continue
            lateinit var job: Job
            val captured = generation
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    withTimeout(35_000) {
                        val result = SourceStreams.get(context).resolve(id)
                        val (url, headers) = MusicSourceProtocol.stream(result)
                        val key = result.getString("_klangCacheKey")
                        if (synchronized(this@SourceAudioCache) { key in prepared } && cache.isCached(key, 0, PREFIX_BYTES)) {
                            synchronized(this@SourceAudioCache) { recent[id] = System.currentTimeMillis() }
                            return@withTimeout
                        }
                        if (!cache.isCached(key, 0, PREFIX_BYTES)) {
                            val spec = DataSpec.Builder().setUri(url).setKey(key).setHttpRequestHeaders(headers).setLength(PREFIX_BYTES).build()
                            val reader = factory(sourceFactory(context, cacheAudio = false)).createDataSource()
                            synchronized(this@SourceAudioCache) { readers[id] = reader }
                            try { runInterruptible { cacheAudioPrefix(reader, spec, PREFIX_BYTES) } }
                            finally { synchronized(this@SourceAudioCache) { readers.remove(id) } }
                        }
                        synchronized(this@SourceAudioCache) {
                            if (captured == generation) { prepared.add(key); recent[id] = System.currentTimeMillis() }
                            while (prepared.size > 128) prepared.remove(prepared.first())
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Playback may resolve/retry independently of speculative audio. */ }
                finally { synchronized(this@SourceAudioCache) { if (active[id] === job) active.remove(id) } }
            }
            active[id] = job; job.start()
        }
    }

    @Synchronized fun cancelPreparation() {
        active.values.forEach { it.cancel() }
        val opened = readers.values.toList()
        scope.launch { opened.forEach { runCatching { it.close() } } }
    }
    @Synchronized fun invalidate() { generation++; prepared.clear(); recent.clear(); cancelPreparation() }

    companion object {
        private const val PREFIX_BYTES = 192L * 1024
        private var instance: SourceAudioCache? = null
        @Synchronized fun get(context: Context) = instance ?: SourceAudioCache(context.applicationContext).also { instance = it }
    }
}

/** A failed bounded range must never be retried as an unbounded speculative download. */
internal fun cacheAudioPrefix(source: DataSource, spec: DataSpec, maximumBytes: Long) {
    try {
        source.open(spec)
        val buffer = ByteArray(16 * 1024)
        var remaining = maximumBytes
        while (remaining > 0 && !Thread.currentThread().isInterrupted) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            if (read == 0) throw java.io.IOException("The stream returned no audio bytes.")
            remaining -= read
        }
    } finally { source.close() }
}
