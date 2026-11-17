package app.lynli.klang.plugins

import android.content.Context
import app.lynli.klang.data.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest

/** Bounded, memory-only stream preparation shared by browsing, playback and seeks. */
internal class SourceStreams private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SourceMediaStore.get(context)
    private val requests = StreamRequests<JSONObject>(scope)
    private val preparation = StreamPreparation(scope, { resolve(it) }, concurrency = 4, capacity = 48)
    private val pendingMatches = mutableMapOf<String, LinkedHashSet<String>>()
    private val matched = linkedSetOf<String>()
    private val matchPreparation = StreamPreparation(scope, { sendMatches(it) }, concurrency = 1, capacity = 30)
    private val accounts = mutableMapOf<String, Pair<Long, JSONObject>>()
    private val accountLock = Mutex()
    private val rejected = mutableSetOf<String>()
    private var installed: Pair<Long, List<InstalledPlugin>>? = null

    private fun provider(id: String): InstalledPlugin {
        val entries = synchronized(this) { installed?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.second }
            ?: PluginRegistry(context).entries.also { synchronized(this) { installed = System.currentTimeMillis() to it } }
        val enabled = PluginStore(context) { entries.map { it.manifest } }.read().enabled
        return entries.firstOrNull { it.manifest.id == id && id in enabled && it.manifest.musicSource != null }
            ?: error("Enable this music source in Plugins, or download the song for offline playback.")
    }
    suspend fun resolve(identity: String, playback: Boolean = false): JSONObject {
        val plugin = provider(requireNotNull(java.net.URI(identity).rawAuthority))
        val spec = requireNotNull(plugin.manifest.musicSource)
        require("stream.resolve" in spec.capabilities)
        val account = accountLock.withLock {
            synchronized(this) { accounts[plugin.manifest.id]?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.second }
                ?: (if ("accounts" in spec.capabilities) MusicSourceClient.call(context, plugin, "accounts") else JSONObject()).also {
                    observeAccount(plugin.manifest.id, it)
                }
        }
        val revision = account.optString("revision")
        if (revision.isNotBlank()) withContext(Dispatchers.IO) { store.acceptAccount(plugin.manifest.id, revision) }
        val key = "$identity|${plugin.manifest.version}|$revision|${account.optString("playbackRevision")}"
        // Playback never joins a speculative request the source may shed; prepared URLs still share the audio key.
        val result = requests.get(if (playback) "$key|playback" else key) {
            val refresh = synchronized(this) { identity in rejected }
            val args = JSONObject().put("item", withContext(Dispatchers.IO) { store.item(identity) })
                // The source may shed speculative prefetch at capacity; playback must never be dropped.
                .put("playback", playback)
            if (refresh) args.put("refresh", true)
            val stream = MusicSourceClient.call(context, plugin, "stream.resolve", args)
            MusicSourceProtocol.stream(stream)
            stream.put("_klangCacheKey", sourceAudioKey(key, stream))
            synchronized(this) { rejected.remove(identity) }
            PreparedStream(JSONObject(stream.toString()), stream.optLong("expiresAt", System.currentTimeMillis() + 60_000))
        }
        return JSONObject(result.toString())
    }
    /** Four workers prepare likely visible tracks and queue neighbors with a bounded backlog. */
    fun prepare(tracks: List<Track>, priority: Boolean = false) {
        val online = tracks.filter { it.providerId != null && !it.offline }.map(Track::id).distinct()
        preparation.offer(online.take(24), priority)
        prepareMatches(online)
    }
    fun prepareIdentities(ids: List<String>) {
        scope.launch {
            preparation.offer(ids.filter { it.startsWith("klang-source:") && store.offlineUri(it) == null }.distinct().take(12), priority = true)
        }
    }
    fun prepareMatches(ids: List<String>) {
        val providers = synchronized(this) {
            for (id in ids) {
                if (!id.startsWith("klang-source:") || id in matched) continue
                val providerId = runCatching { java.net.URI(id).rawAuthority }.getOrNull() ?: continue
                val waiting = pendingMatches.getOrPut(providerId) { linkedSetOf() }
                if (waiting.size < 20_000) waiting.add(id)
            }
            pendingMatches.filterValues { it.isNotEmpty() }.keys.toList()
        }
        matchPreparation.offer(providers, priority = true)
    }
    private suspend fun sendMatches(providerId: String) {
        val plugin = provider(providerId)
        val supported = "stream.prepare" in requireNotNull(plugin.manifest.musicSource).capabilities
        while (true) {
            val ids = synchronized(this) { pendingMatches[providerId]?.take(100).orEmpty() }
            if (ids.isEmpty()) return
            if (supported) {
                val items = withContext(Dispatchers.IO) { ids.mapNotNull { id -> runCatching { store.item(id) }.getOrNull() } }
                if (items.isNotEmpty()) MusicSourceClient.call(context, plugin, "stream.prepare",
                    JSONObject().put("items", org.json.JSONArray(items)))
            }
            synchronized(this) {
                pendingMatches[providerId]?.removeAll(ids.toSet()); matched.addAll(ids)
                while (matched.size > 20_000) matched.remove(matched.first())
            }
        }
    }
    fun buffering(value: Boolean, current: String? = null) {
        preparation.pause(value, keep = current); matchPreparation.pause(value)
    }
    @Synchronized fun reject(identity: String) {
        rejected.add(identity)
        requests.invalidate("$identity|")
    }
    @Synchronized fun observeAccount(id: String, account: JSONObject) {
        val previous = accounts[id]?.second
        if (previous != null && (previous.optString("revision") != account.optString("revision") ||
                previous.optString("playbackRevision") != account.optString("playbackRevision"))) {
            requests.invalidate(); preparation.clear(); matchPreparation.clear(); pendingMatches.clear(); matched.clear(); rejected.clear()
            app.lynli.klang.playback.SourceAudioCache.get(context).invalidate()
        }
        accounts[id] = System.currentTimeMillis() to JSONObject(account.toString())
    }
    @Synchronized fun invalidate() {
        requests.invalidate(); preparation.clear(); matchPreparation.clear(); pendingMatches.clear(); matched.clear(); accounts.clear(); rejected.clear(); installed = null
        app.lynli.klang.playback.SourceAudioCache.get(context).invalidate()
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
    @Synchronized fun removePrefix(prefix: String) { entries.keys.removeAll { it.startsWith(prefix) } }
}

/** Signed URLs never become on-disk cache keys. A stable encoding ID permits safe URL refreshes. */
internal fun sourceAudioKey(scope: String, stream: JSONObject): String {
    val identity = stream.optString("cacheKey").takeIf { it.isNotBlank() && it != "null" && it.length <= 1024 }
        ?: stream.getString("url")
    return MessageDigest.getInstance("SHA-256").digest("$scope|$identity".toByteArray())
        .joinToString("") { "%02x".format(it) }
}
