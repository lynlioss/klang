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
    private val preparation = StreamPreparation(scope, { resolve(it) })
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
    suspend fun resolve(identity: String): JSONObject {
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
        val result = requests.get(key) {
            val refresh = synchronized(this) { identity in rejected }
            val args = JSONObject().put("item", withContext(Dispatchers.IO) { store.item(identity) })
            if (refresh) args.put("refresh", true)
            val stream = MusicSourceClient.call(context, plugin, "stream.resolve", args)
            MusicSourceProtocol.stream(stream)
            stream.put("_klangCacheKey", sourceAudioKey(key, stream))
            synchronized(this) { rejected.remove(identity) }
            PreparedStream(JSONObject(stream.toString()), stream.optLong("expiresAt", System.currentTimeMillis() + 60_000))
        }
        return JSONObject(result.toString())
    }
    /** Prepare links for likely visible tracks, with two workers and a bounded backlog. */
    fun prepare(tracks: List<Track>, priority: Boolean = false) {
        preparation.offer(tracks.filter { it.providerId != null && !it.offline }.map(Track::id).distinct().take(12), priority)
    }
    fun prepareIdentities(ids: List<String>) {
        preparation.offer(ids.filter { it.startsWith("klang-source:") && store.offlineUri(it) == null }.take(4), priority = true)
    }
    fun buffering(value: Boolean, current: String? = null) = preparation.pause(value, keep = current)
    @Synchronized fun reject(identity: String) {
        rejected.add(identity)
        requests.invalidate("$identity|")
    }
    @Synchronized fun observeAccount(id: String, account: JSONObject) {
        val previous = accounts[id]?.second
        if (previous != null && (previous.optString("revision") != account.optString("revision") ||
                previous.optString("playbackRevision") != account.optString("playbackRevision"))) {
            requests.invalidate(); preparation.clear(); rejected.clear()
            app.lynli.klang.playback.SourceAudioCache.get(context).invalidate()
        }
        accounts[id] = System.currentTimeMillis() to JSONObject(account.toString())
    }
    @Synchronized fun invalidate() {
        requests.invalidate(); preparation.clear(); accounts.clear(); rejected.clear(); installed = null
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
