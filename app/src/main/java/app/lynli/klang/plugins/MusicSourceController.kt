package app.lynli.klang.plugins

import android.content.Context
import app.lynli.klang.data.MusicRepository
import app.lynli.klang.data.Track
import app.lynli.klang.importing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SourceDestination(val providerId: String, val entityId: String? = null, val root: String = "home")

/** Non-secret link/cache state the source reports for one song. */
internal data class SourceRecording(val title: String, val artist: String, val album: String, val durationMs: Long)
internal data class SourceTrackStatus(val state: String, val verified: Boolean, val retryAt: Long,
    val sourceUrl: String?, val recordingUrl: String?, val recording: SourceRecording?)

/** Generic source host: discover providers, validate declared features, retain metadata and queue downloads. */
internal class MusicSourceController(private val context: Context, private val scope: CoroutineScope,
    private val providers: () -> List<InstalledPlugin>, private val message: (String) -> Unit) {
    private val store = SourceMediaStore.get(context)
    val changes = store.changes
    private val accountLock = Mutex()
    private val accounts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val playbackRevisions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val mutableLibrary = MutableStateFlow(SourceLibrary())
    val library = mutableLibrary.asStateFlow()
    private val mutableRefresh = MutableStateFlow(0L)
    val refreshes = mutableRefresh.asStateFlow()
    private val mutableResume = MutableStateFlow(0L)
    val resumes = mutableResume.asStateFlow()
    fun resumed() { accounts.clear(); mutableResume.value++; requestSync() }
    private val mutableDestination = MutableStateFlow<SourceDestination?>(null)
    val destination = mutableDestination.asStateFlow()
    fun entries() = providers().filter { it.manifest.musicSource != null }
    fun requestSync() { mutableRefresh.value++ }
    suspend fun reloadLibrary() {
        val saved = withContext(Dispatchers.IO) { store.library(entries().map { it.manifest.id }.toSet()) }
        mutableLibrary.value = saved.copy(connections = mutableLibrary.value.connections.filter { connection ->
            entries().any { it.manifest.id == connection.providerId }
        }, syncing = mutableLibrary.value.syncing)
    }
    private suspend fun account(plugin: InstalledPlugin, force: Boolean = false): JSONObject = accountLock.withLock {
        if (!force) accounts[plugin.manifest.id]?.takeIf { System.currentTimeMillis() - it.first < 10_000 }?.let { return@withLock it.second }
        val result = if ("accounts" in requireNotNull(plugin.manifest.musicSource).capabilities)
            MusicSourceClient.call(context, plugin, "accounts") else JSONObject().put("connected", true).put("revision", "public")
        result.optString("revision").takeIf { it.isNotBlank() }?.let { revision -> withContext(Dispatchers.IO) {
            if (store.acceptAccount(plugin.manifest.id, revision)) SourceStreams.get(context).invalidate()
        } }
        result.optString("playbackRevision").takeIf { it.isNotBlank() }?.let { revision ->
            val previous = playbackRevisions.put(plugin.manifest.id, revision)
            if (previous != null && previous != revision) SourceStreams.get(context).invalidate()
        }
        SourceStreams.get(context).observeAccount(plugin.manifest.id, result)
        accounts[plugin.manifest.id] = System.currentTimeMillis() to result
        result
    }
    /** Sync library membership in the background while durable metadata remains immediately usable. */
    suspend fun synchronize() {
        reloadLibrary()
        mutableLibrary.value = mutableLibrary.value.copy(syncing = true)
        try {
            val connections = mutableListOf<SourceConnection>()
            for (plugin in entries()) {
                val manifest = plugin.manifest
                val spec = requireNotNull(manifest.musicSource)
                try {
                    val account = account(plugin, force = true)
                    val connected = account.optBoolean("connected", account.optBoolean("spotify", true))
                    connections += SourceConnection(manifest.id, manifest.name, spec.badge, connected,
                        account.optString("profile").takeIf { it.isNotBlank() && it != "null" })
                    mutableLibrary.value = mutableLibrary.value.copy(connections = connections.toList())
                    reloadLibrary()
                    if (!connected) continue
                    val revision = account.optString("revision", "public")
                    if ("catalog.library" in spec.capabilities) for (kind in listOf("track", "playlist", "album", "artist", "folder")) {
                        try {
                            val items = readPages(manifest.id, "library", JSONObject().put("kind", kind), revision)
                            withContext(Dispatchers.IO) { store.replaceLibrary(manifest, kind, items, revision) }
                            reloadLibrary()
                        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                            connections[connections.lastIndex] = connections.last().copy(notice = e.message?.take(200))
                        }
                    }
                    if ("catalog.home" in spec.capabilities) try {
                        val home = call(manifest.id, "home", JSONObject().put("limit", 100))
                        withContext(Dispatchers.IO) { store.replaceLibrary(manifest, "home", home.getJSONArray("items").objects(), revision) }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                    connections += SourceConnection(manifest.id, manifest.name, spec.badge, true, notice = e.message?.take(200))
                }
                mutableLibrary.value = mutableLibrary.value.copy(connections = connections.toList())
            }
            reloadLibrary()
        } finally { mutableLibrary.value = mutableLibrary.value.copy(syncing = false) }
    }
    private suspend fun readPages(id: String, operation: String, args: JSONObject, revision: String): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var offset = 0
        do {
            val page = call(id, operation, JSONObject(args.toString()).put("offset", offset).put("limit", 100))
            page.optString("accountRevision").takeIf { it.isNotBlank() }?.let {
                check(it == revision) { "The source account changed. Refresh your library." }
            }
            check(!page.has("notice")) { page.optString("notice") }
            items += page.getJSONArray("items").objects()
            require(items.size <= 10_000) { "This source library supports up to 10,000 entries per collection." }
            if (page.isNull("nextOffset")) break
            offset = page.getInt("nextOffset")
            require(offset < 100_000)
        } while (true)
        return items
    }
    suspend fun search(query: String, kind: String = "all", offsets: Map<String, Int>? = null,
        cachedOnly: Boolean = false): SourceSearchPage = coroutineScope {
        val pages = entries().filter { "catalog.search" in requireNotNull(it.manifest.musicSource).capabilities &&
            (offsets == null || it.manifest.id in offsets) }.map { plugin -> async {
            val id = plugin.manifest.id
            try {
                val args = JSONObject().put("query", query).put("kind", kind).put("offset", offsets?.get(id) ?: 0).put("limit", 20)
                val result = if (cachedOnly) cached(id, "search", args) ?: return@async SourceSearchPage(emptyList(), emptyList(), emptyMap(), emptyList())
                    else call(id, "search", args)
                val items = result.getJSONArray("items").objects()
                SourceSearchPage(items.filter { it.optString("kind") == "track" && it.optBoolean("available", true) }.map { track(id, it) },
                    items.filter { it.optString("kind") != "track" }.map { sourceCollection(id, it) },
                    if (result.isNull("nextOffset")) emptyMap() else mapOf(id to result.getInt("nextOffset")),
                    listOfNotNull(result.optString("notice").takeIf { it.isNotBlank() }))
            } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                SourceSearchPage(emptyList(), emptyList(), emptyMap(), listOf("${plugin.manifest.name}: ${e.message.orEmpty().take(200)}"))
            }
        } }.awaitAll()
        SourceSearchPage(pages.flatMap { it.tracks }, pages.flatMap { it.collections },
            pages.flatMap { it.nextOffsets.entries }.associate { it.toPair() }, pages.flatMap { it.notices })
    }
    fun prepare(tracks: List<Track>) = SourceStreams.get(context).prepare(tracks)
    fun unify(tracks: List<Track>): List<Track> = store.unify(tracks)
    fun updateDeviceLibrary(tracks: List<Track>) = store.updateDeviceLibrary(tracks)
    fun refreshLocal(track: Track): Track = store.refreshLocal(track)
    fun offlineDeviceId(track: Track): String? = if (track.providerId == null) null else store.offlineUri(track.id)?.toString()
    fun open(providerId: String, entityId: String? = null, root: String = "home") {
        mutableDestination.value = SourceDestination(providerId, entityId, root)
    }
    fun dismiss() { mutableDestination.value = null }
    private fun provider(id: String) = entries().firstOrNull { it.manifest.id == id }
        ?: error("Enable this music source in Plugins.")
    /** Read saved catalog pages without binding a service or waiting for account/network requests. */
    suspend fun cached(id: String, operation: String, args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        provider(id)
        store.cached(id, JSONObject(args.toString()).put("operation", operation))?.takeIf {
            it.optString("accountRevision").ifBlank { store.accountRevision(id).orEmpty() } == store.accountRevision(id)
        }
    }
    suspend fun call(id: String, operation: String, args: JSONObject = JSONObject()): JSONObject {
        val plugin = provider(id)
        val spec = requireNotNull(plugin.manifest.musicSource)
        val capability = when (operation) {
            "home" -> "catalog.home"; "search" -> "catalog.search"; "library" -> "catalog.library"; "lookup" -> "catalog.lookup"
            "library.save", "library.remove" -> "library.write"
            "playlist.add", "playlist.remove", "playlist.move" -> "playlist.items"
            "playlist.follow", "playlist.unfollow" -> "playlist.follow"
            "playlist.cover" -> "playlist.cover"
            "accounts", "account.logout" -> "accounts"
            else -> operation
        }
        require(capability in spec.capabilities) { "This music source doesn't support that action." }
        val read = operation in setOf("home", "search", "library", "lookup")
        val cacheArgs = JSONObject(args.toString()).put("operation", operation)
        return try {
            if (operation != "accounts" && operation != "account.logout" && "accounts" in spec.capabilities) account(plugin)
            val result = MusicSourceClient.call(context, plugin, operation, args)
            if (read) MusicSourceProtocol.page(result)
            if (!read && operation != "accounts") {
                withContext(Dispatchers.IO) { store.clearPages(id) }
                requestSync()
            }
            if (read) withContext(Dispatchers.IO) {
                val revision = result.optString("accountRevision").ifBlank { store.accountRevision(id).orEmpty() }
                result.optJSONArray("items")?.objects().orEmpty().forEach { item ->
                    if (revision.isNotBlank()) item.put("accountRevision", revision)
                    if (item.optString("kind") == "track") store.track(plugin.manifest, item)
                }
                // Large pages still render, but only bounded pages enter the offline catalog cache.
                if (result.toString().toByteArray().size <= 256_000) store.cache(id, cacheArgs, result)
            }
            result
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            val cached = if (read) withContext(Dispatchers.IO) { store.cached(id, cacheArgs) } else null
            cached?.put("notice", "Showing saved metadata. ${e.message.orEmpty().take(200)}") ?: throw e
        }
    }
    suspend fun track(id: String, item: JSONObject): Track = withContext(Dispatchers.IO) { store.track(provider(id).manifest, item) }
    fun storedTrack(id: String): Track? = store.lookup(id)
    /** Link and playback-cache state for song properties; never returns credentials or signed audio URLs. */
    suspend fun status(track: Track): SourceTrackStatus? = withContext(Dispatchers.IO) {
        val id = track.providerId ?: return@withContext null
        val item = track.sourcePayload?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@withContext null
        val sourceId = track.sourceId ?: return@withContext null
        val plugin = runCatching { provider(id) }.getOrNull() ?: return@withContext null
        if ("catalog.lookup" !in requireNotNull(plugin.manifest.musicSource).capabilities) return@withContext null
        val result = try {
            MusicSourceClient.call(context, plugin, "lookup",
                JSONObject().put("id", sourceId).put("statusOnly", true).put("item", item))
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return@withContext null }
        val status = result.optJSONObject("sourceStatus") ?: return@withContext null
        val recording = status.optJSONObject("recording")
        SourceTrackStatus(status.optString("state"), status.optBoolean("verified"), status.optLong("retryAt"),
            status.optString("spotify").takeIf { it.startsWith("https://") },
            status.optString("youtube").takeIf { it.startsWith("https://") },
            recording?.let { SourceRecording(it.optString("title"), it.optString("artist"), it.optString("album"), it.optLong("durationMs")) })
    }
    /** Cached playback bytes for one source song, separate from user-requested offline downloads. */
    suspend fun cachedBytes(track: Track): Long = withContext(Dispatchers.IO) {
        if (track.providerId == null) 0L else app.lynli.klang.playback.SourceAudioCache.get(context).trackBytes(track.id)
    }
    fun accountRevision(id: String): String? = store.accountRevision(id)
    fun settings(id: String) {
        try {
            val spec = requireNotNull(provider(id).manifest.musicSource)
            val activity = spec.settingsActivity ?: error("This source has no account settings.")
            context.startActivity(android.content.Intent().setComponent(android.content.ComponentName(spec.packageName, activity))
                .putExtra("klang.theme", context.getSharedPreferences("klang", Context.MODE_PRIVATE).getString("theme", "system"))
                .putExtra("klang.dynamicColor", context.getSharedPreferences("klang", Context.MODE_PRIVATE).getBoolean("dynamicColor", false))
                .putExtra("klang.cacheBridge", "content://${context.packageName}.source-cache")
                .putExtra("klang.providerId", id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { message(e.message ?: "Couldn't open the plugin's settings.") }
    }
    suspend fun collection(id: String, entityId: String): List<Track> {
        val items = mutableListOf<JSONObject>()
        var offset = 0
        do {
            val page = call(id, "lookup", JSONObject().put("id", entityId).put("offset", offset).put("limit", 100))
            items += page.optJSONArray("items")?.objects().orEmpty()
            require(items.size <= 10_000) { "Use collections of up to 10,000 songs." }
            if (page.isNull("nextOffset")) break
            val next = page.getInt("nextOffset")
            require(next > offset && next < 10_000) { "The source returned invalid collection pagination." }
            offset = next
        } while (true)
        return items.filter { it.optString("kind") == "track" && it.optBoolean("available", true) }.map { track(id, it) }
    }
    suspend fun addToPlaylist(id: String, playlistId: String, ids: List<String>) {
        require(ids.size in 1..10_000)
        for (chunk in ids.chunked(100)) call(id, "playlist.add", JSONObject().put("id", playlistId).put("ids", org.json.JSONArray(chunk)))
    }
    fun download(tracks: List<Track>, title: String = tracks.firstOrNull()?.title.orEmpty(), collection: Boolean = false) {
        if (tracks.isEmpty()) return
        scope.launch {
            try {
                val id = requireNotNull(tracks.first().providerId)
                val plugin = provider(id)
                require("offline.download" in requireNotNull(plugin.manifest.musicSource).capabilities && plugin.manifest.musicImport != null)
                require(tracks.all { it.providerId == id })
                val device = MusicRepository(context).tracks()
                val batches = ImportStore.get(context).batches.value
                val queued = batches.filter { it.providerId == id }.flatMap { it.entries }
                    .filter { !it.phase.finished }.map { it.song.sourceId }.toSet()
                val songs = tracks.distinctBy { it.sourceId }.filter { it.sourceId !in queued }.map { track ->
                    ImportSong(requireNotNull(track.sourceId), requireNotNull(track.shareUrl), track.title, track.artist,
                        track.album, track.durationMs, track.artworkUrl, requireNotNull(track.sourcePayload))
                }
                if (songs.isEmpty()) { message("These songs are already queued for download."); return@launch }
                val entries = songs.map { song ->
                    val duplicate = duplicateRecording(song, device)
                    if (duplicate != null) store.markOffline(id, song.sourceId, duplicate.id)
                    ImportEntry(song, if (duplicate == null) ImportPhase.QUEUED else ImportPhase.DUPLICATE, duplicateUri = duplicate?.id)
                }
                val preview = ImportPreview(ImportLink.parse(songs.first().url), id, title, songs, collection, songs.first().artwork)
                ImportStore.get(context).enqueue(preview, entries)
                message(if (entries.all { it.phase == ImportPhase.DUPLICATE }) "Already on this device." else "Download queued.")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { message(e.message ?: "Couldn't queue this download.") }
        }
    }
}
