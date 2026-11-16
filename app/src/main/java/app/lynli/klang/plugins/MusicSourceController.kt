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

/** Generic source host: discover providers, validate declared features, retain metadata and queue downloads. */
internal class MusicSourceController(private val context: Context, private val scope: CoroutineScope,
    private val providers: () -> List<InstalledPlugin>, private val message: (String) -> Unit) {
    private val store = SourceMediaStore.get(context)
    val changes = store.changes
    private val accountLock = Mutex()
    private val mutableResume = MutableStateFlow(0L)
    val resumes = mutableResume.asStateFlow()
    fun resumed() { mutableResume.value++ }
    private val mutableDestination = MutableStateFlow<SourceDestination?>(null)
    val destination = mutableDestination.asStateFlow()
    fun entries() = providers().filter { it.manifest.musicSource != null }
    fun open(providerId: String, entityId: String? = null, root: String = "home") {
        mutableDestination.value = SourceDestination(providerId, entityId, root)
    }
    fun dismiss() { mutableDestination.value = null }
    private fun provider(id: String) = entries().firstOrNull { it.manifest.id == id }
        ?: error("Enable this music source in Plugins.")
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
            if (operation != "accounts" && operation != "account.logout" && "accounts" in spec.capabilities) accountLock.withLock {
                val account = MusicSourceClient.call(context, plugin, "accounts")
                account.optString("revision").takeIf { it.isNotBlank() }?.let { revision -> withContext(Dispatchers.IO) { store.acceptAccount(id, revision) } }
            }
            val result = MusicSourceClient.call(context, plugin, operation, args)
            if (read) MusicSourceProtocol.page(result)
            if (!read && operation != "accounts") withContext(Dispatchers.IO) { store.clearPages(id) }
            if (read) withContext(Dispatchers.IO) {
                result.optJSONArray("items")?.objects().orEmpty().filter { it.optString("kind") == "track" }.forEach { store.track(plugin.manifest, it) }
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
    fun settings(id: String) {
        try {
            val spec = requireNotNull(provider(id).manifest.musicSource)
            val activity = spec.settingsActivity ?: error("This source has no account settings.")
            context.startActivity(android.content.Intent().setComponent(android.content.ComponentName(spec.packageName, activity))
                .putExtra("klang.theme", context.getSharedPreferences("klang", Context.MODE_PRIVATE).getString("theme", "system"))
                .putExtra("klang.dynamicColor", context.getSharedPreferences("klang", Context.MODE_PRIVATE).getBoolean("dynamicColor", false))
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
