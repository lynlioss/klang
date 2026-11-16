package app.lynli.klang.plugins

import android.content.Context
import app.lynli.klang.data.Track
import kotlinx.coroutines.*
import org.json.JSONObject

/** Bounded, memory-only stream preparation shared by browsing, playback and seeks. */
internal class SourceStreams private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SourceMediaStore.get(context)
    private val cache = StreamCache<JSONObject>(24)
    private class Pending(val work: Deferred<JSONObject>, var waiters: Int = 0)
    private val pending = mutableMapOf<String, Pending>()
    private var generation = 0L
    private var preparation: Job? = null

    private fun provider(id: String): InstalledPlugin {
        val registry = PluginRegistry(context)
        val enabled = PluginStore(context) { registry.manifests }.read().enabled
        return registry.entries.firstOrNull { it.manifest.id == id && id in enabled && it.manifest.musicSource != null }
            ?: error("Enable this music source in Plugins, or download the song for offline playback.")
    }
    suspend fun resolve(identity: String): JSONObject {
        val plugin = provider(requireNotNull(java.net.URI(identity).rawAuthority))
        val spec = requireNotNull(plugin.manifest.musicSource)
        require("stream.resolve" in spec.capabilities)
        val account = if ("accounts" in spec.capabilities) MusicSourceClient.call(context, plugin, "accounts") else JSONObject()
        val revision = account.optString("revision")
        if (revision.isNotBlank()) withContext(Dispatchers.IO) { store.acceptAccount(plugin.manifest.id, revision) }
        val captured: Long
        val key: String
        val request: Pending
        synchronized(this) {
            captured = generation
            key = "$identity:${plugin.manifest.version}:$revision:${account.optString("playbackRevision")}:$captured"
            cache.get(key, System.currentTimeMillis())?.let { return JSONObject(it.toString()) }
            request = pending[key] ?: Pending(scope.async(start = CoroutineStart.LAZY) {
                val result = MusicSourceClient.call(context, plugin, "stream.resolve", JSONObject().put("item", store.item(identity)))
                MusicSourceProtocol.stream(result)
                synchronized(this@SourceStreams) {
                    if (captured == generation) cache.put(key, JSONObject(result.toString()),
                        result.optLong("expiresAt", System.currentTimeMillis() + 60_000))
                }
                result
            }).also { pending[key] = it }
            request.waiters++
            request.work.start()
        }
        try { return JSONObject(request.work.await().toString()) }
        finally { synchronized(this) {
            request.waiters--
            if (request.waiters == 0) {
                if (pending[key] === request) pending.remove(key)
                if (request.work.isActive) request.work.cancel()
            }
        } }
    }
    /** Prepare only a couple of likely songs; never download audio merely because a page is visible. */
    @Synchronized fun prepare(tracks: List<Track>) {
        val songs = tracks.filter { it.providerId != null && !it.offline }.distinctBy(Track::id).take(2)
        if (songs.isEmpty()) return
        preparation?.cancel()
        preparation = scope.launch {
            for (track in songs) {
                try { withTimeout(45_000) { resolve(track.id) } }
                catch (cancelled: CancellationException) { if (!currentCoroutineContext().isActive) throw cancelled }
                catch (_: Exception) { /* A speculative failure never interrupts current playback. */ }
            }
        }
    }
    @Synchronized fun invalidate() {
        generation++
        preparation?.cancel(); preparation = null
        pending.values.forEach { it.work.cancel() }; pending.clear(); cache.clear()
    }
    companion object {
        private var instance: SourceStreams? = null
        @Synchronized fun get(context: Context) = instance ?: SourceStreams(context.applicationContext).also { instance = it }
    }
}

/** Expiry margins prevent a prepared URL from expiring during stream startup. */
internal class StreamCache<T>(private val capacity: Int) {
    private val entries = LinkedHashMap<String, Pair<Long, T>>(capacity, .75f, true)
    @Synchronized fun get(key: String, now: Long): T? {
        val entry = entries[key] ?: return null
        if (entry.first <= now + 30_000) { entries.remove(key); return null }
        return entry.second
    }
    @Synchronized fun put(key: String, value: T, expiresAt: Long) {
        entries[key] = expiresAt to value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun clear() = entries.clear()
}
