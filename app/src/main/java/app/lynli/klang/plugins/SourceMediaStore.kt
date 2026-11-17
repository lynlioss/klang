package app.lynli.klang.plugins

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.lynli.klang.data.Track
import app.lynli.klang.data.LocalRecordings
import app.lynli.klang.data.RecordingIdentity
import app.lynli.klang.data.audioFileIds
import app.lynli.klang.data.mergeMusicEntries
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Durable catalog identity and offline mapping; stores metadata, never playback URLs or credentials. */
internal class SourceMediaStore(private val context: Context) {
    private val directory = File(context.filesDir, "music-sources").apply { mkdirs() }
    private val memory = object : android.util.LruCache<String, JSONObject>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: JSONObject) = key.length * 2 + value.toString().length * 2
    }
    private val offlineChecks = ConcurrentHashMap<String, Pair<Long, Uri?>>()
    @Volatile private var cachedLibrary: Pair<Set<String>, SourceLibrary>? = null
    @Volatile private var deviceTracks = emptyList<Track>()
    @Volatile private var localRecordings = LocalRecordings(emptyList<Track>(), ::recording)
    @Volatile private var deviceLoaded = false
    private val deviceLock = Any()
    fun updateDeviceLibrary(tracks: List<Track>) = synchronized(deviceLock) {
        deviceTracks = tracks.filter { it.providerId == null }
        localRecordings = LocalRecordings(deviceTracks, ::recording)
        deviceLoaded = true
        cachedLibrary = null
    }
    /** Restored source queues can start before the library screen has scanned MediaStore. */
    fun ensureDeviceLibrary() = synchronized(deviceLock) {
        if (!deviceLoaded) updateDeviceLibrary(kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.lynli.klang.data.MusicRepository(context).tracks()
        })
    }
    private fun recording(track: Track) = RecordingIdentity(track.title, track.artist, track.album, track.durationMs)
    private fun localMatch(json: JSONObject) = localRecordings.match(RecordingIdentity(json.optString("title"),
        json.optString("artist"), json.optString("album"), json.optLong("durationMs"),
        json.optString("isrc").takeIf { it.isNotBlank() && it != "null" },
        if (json.has("explicit")) json.optBoolean("explicit") else null))
    fun unify(entries: List<Track>): List<Track> {
        val sources = entries.filter { it.providerId != null }.map { entry ->
            val local = localRecordings.match(recording(entry))
            val uri = local?.uri ?: entry.localUri
            entry.copy(localUri = uri, offline = uri != null || entry.offline,
                aliases = entry.aliases + uri?.toString()?.let(::audioFileIds).orEmpty())
        }
        val grouped = sources.groupBy { it.localUri?.let { uri -> audioFileIds(uri.toString()).sorted().first() } ?: it.id }
            .values.map { copies -> copies.first().copy(aliases = copies.flatMap { it.identities }.toSet() - copies.first().id) }
        return mergeMusicEntries(entries.filter { it.providerId == null }, grouped, Track::id) { it.localUri?.toString() }
    }
    fun refreshLocal(track: Track): Track {
        if (track.providerId == null) return track
        val local = localRecordings.match(recording(track))?.uri
        return track.copy(localUri = local ?: track.localUri, offline = local != null || track.offline,
            aliases = track.aliases + local?.toString()?.let(::audioFileIds).orEmpty())
    }
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private fun file(key: String) = AtomicFile(File(directory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"))
    private fun read(key: String): JSONObject? {
        // LruCache is thread-safe. Cached UI/playback reads must not wait for a large disk sync.
        memory.get(key)?.let { return JSONObject(it.toString()) }
        return synchronized(this) {
            memory.get(key)?.let { return@synchronized JSONObject(it.toString()) }
            runCatching { file(key).openRead().use {
                val bytes = it.readNBytes(256_001); require(bytes.size <= 256_000)
                JSONObject(bytes.toString(Charsets.UTF_8))
            } }.getOrNull()?.also { memory.put(key, JSONObject(it.toString())) }
        }
    }
    @Synchronized private fun write(key: String, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256_000)
        if (memory.get(key)?.toString() == json.toString()) return
        val target = file(key); val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream); memory.put(key, JSONObject(json.toString())) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    fun identity(providerId: String, id: String): String = MusicSourceProtocol.identity(providerId, id)
    @Synchronized fun track(plugin: PluginManifest, item: JSONObject): Track {
        val spec = requireNotNull(plugin.musicSource)
        val id = item.getString("id").also { require(it.length in 1..1024) }
        require(item.getString("title").let { it.isNotBlank() && it.length <= 1000 })
        require(item.optString("artist").length <= 1000 && item.optString("album").length <= 1000)
        require(item.optLong("durationMs") in 0..86_400_000L)
        val key = identity(plugin.id, id)
        val json = providerItem(item).put("providerId", plugin.id).put("badge", spec.badge).put("linkLabel", spec.linkLabel)
        val previous = read(key)
        previous?.optString("offlineUri")?.takeIf { it.isNotBlank() }?.let { json.put("offlineUri", it) }
        if (!json.has("added")) previous?.optLong("added")?.let { json.put("added", it) }
        write(key, json)
        return toTrack(key, json)
    }
    private fun toTrack(key: String, json: JSONObject): Track {
        val local = localMatch(json)?.uri ?: downloadedUri(key, json)
        return Track(Uri.parse(key), json.getString("title"),
        json.optString("artist"), json.optString("album"), json.optLong("durationMs"), added = json.optLong("added"), albumArtist = json.optString("albumArtist"),
        providerId = json.getString("providerId"), sourceId = json.getString("id"), sourceBadge = json.optString("badge", "music"),
        sourceLabel = json.optString("linkLabel"), shareUrl = json.optString("url").takeIf { it.startsWith("https://") },
        artworkUrl = json.optString("artwork").takeIf { it.startsWith("https://") },
        sourcePayload = providerItem(json).toString(), offline = local != null, localUri = local,
        aliases = local?.toString()?.let(::audioFileIds).orEmpty())
    }
    fun lookup(key: String): Track? = if (!key.startsWith("klang-source:")) null else read(key)?.let { toTrack(key, it) }
    fun item(key: String): JSONObject = providerItem(read(key) ?: error("This song's catalog metadata is unavailable. Open it again in its music source."))
    private fun providerItem(json: JSONObject) = JSONObject(json.toString()).apply {
        listOf("providerId", "badge", "linkLabel", "offlineUri").forEach { remove(it) }
    }
    fun offlineUri(key: String): Uri? {
        val item = read(key) ?: return null
        localMatch(item)?.let { return it.uri }
        return downloadedUri(key, item)
    }
    private fun downloadedUri(key: String, item: JSONObject): Uri? {
        if (item.optString("offlineUri").isBlank()) return null
        offlineChecks[key]?.takeIf { System.currentTimeMillis() - it.first < 10_000 }?.let { return it.second }
        val value = item.optString("offlineUri").takeIf { it.isNotBlank() }?.let { raw ->
        val uri = Uri.parse(raw)
        if (uri.scheme != "content") null else runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { uri } }.getOrNull()
        }
        if (offlineChecks.size > 12_000) offlineChecks.entries.sortedBy { it.value.first }.take(2_000).forEach { offlineChecks.remove(it.key) }
        offlineChecks[key] = System.currentTimeMillis() to value
        return value
    }
    @Synchronized
    fun markOffline(providerId: String, sourceId: String, uri: String) {
        require(Uri.parse(uri).scheme == "content")
        val key = identity(providerId, sourceId)
        read(key)?.let { write(key, it.put("offlineUri", uri)) }
        val indexKey = "offline:$providerId"
        val ids = offlineIds(providerId)
        write(indexKey, JSONObject().put("ids", JSONArray((ids + sourceId).distinct().takeLast(10_000))))
        offlineChecks.remove(key)
        cachedLibrary = null
        mutableChanges.value++
    }
    @Synchronized fun acceptAccount(providerId: String, revision: String): Boolean {
        require(revision.length in 1..200)
        val key = "account:$providerId"
        if (read(key)?.optString("revision") == revision) return false
        clearPages(providerId, includeLibrary = true)
        write(key, JSONObject().put("revision", revision))
        mutableChanges.value++
        return true
    }
    fun accountRevision(providerId: String): String? = read("account:$providerId")?.optString("revision")

    /** Commit membership only for the account that supplied it; seen search songs aren't library songs. */
    @Synchronized fun replaceLibrary(plugin: PluginManifest, kind: String, items: List<JSONObject>, revision: String) {
        require(kind in LIBRARY_KINDS && items.size <= 10_000)
        check(revision == accountRevision(plugin.id)) { "The source account changed during synchronization." }
        val ids = items.distinctBy { it.getString("id") }.map { item ->
            val value = JSONObject(item.toString()).put("accountRevision", revision)
            if (value.optString("kind") == "track") track(plugin, value)
            else write("entity:${plugin.id}:$kind:${value.getString("id")}", value)
            value.getString("id")
        }
        // Chunk membership so large liked-song libraries stay within the per-file metadata bound.
        val key = "library:${plugin.id}:$kind"
        val chunks = ids.chunked(50)
        chunks.forEachIndexed { index, chunk -> write("$key:$index", JSONObject()
            .put("cacheProvider", plugin.id).put("libraryCache", true).put("revision", revision).put("ids", JSONArray(chunk))
            .apply { if (kind == "track") {
                val songs = JSONArray(chunk.mapNotNull { read(identity(plugin.id, it)) })
                if (songs.toString().toByteArray().size < 240_000) put("items", songs)
            } }) }
        write(key, JSONObject().put("cacheProvider", plugin.id).put("libraryCache", true).put("revision", revision).put("chunks", chunks.size))
        cachedLibrary = null
    }
    @Synchronized private fun libraryIds(providerId: String, kind: String): List<String> {
        val key = "library:$providerId:$kind"
        val header = read(key) ?: return emptyList()
        if (header.optString("revision") != accountRevision(providerId)) return emptyList()
        return (0 until header.optInt("chunks").coerceIn(0, 200)).flatMap { index ->
            read("$key:$index")?.takeIf { it.optString("revision") == header.optString("revision") }
                ?.optJSONArray("ids")?.strings().orEmpty()
        }
    }
    @Synchronized fun library(providerIds: Set<String>): SourceLibrary {
        cachedLibrary?.takeIf { it.first == providerIds }?.let { return it.second }
        val favorites = providerIds.flatMap { providerId -> libraryIds(providerId, "track").map { identity(providerId, it) } }.toSet()
        // Load large liked-song snapshots by page, avoiding thousands of files at startup.
        val savedTracks = providerIds.flatMap { providerId ->
            val key = "library:$providerId:track"
            val header = read(key)
            if (header == null || header.optString("revision") != accountRevision(providerId)) emptyList() else
                (0 until header.optInt("chunks").coerceIn(0, 200)).flatMap { index ->
                    val chunk = read("$key:$index")
                    if (chunk == null || chunk.optString("revision") != accountRevision(providerId)) emptyList() else {
                        val songs = chunk.optJSONArray("items")
                        if (songs != null) (0 until songs.length()).map { i ->
                            val item = songs.getJSONObject(i)
                            toTrack(identity(providerId, item.getString("id")), item)
                        } else chunk.optJSONArray("ids")?.strings().orEmpty().mapNotNull { lookup(identity(providerId, it)) }
                    }
                }
        }
        val offline = providerIds.flatMap { providerId -> offlineIds(providerId).map { identity(providerId, it) } }.filter { offlineUri(it) != null }
        fun entities(kind: String) = providerIds.flatMap { providerId -> libraryIds(providerId, kind).mapNotNull { id ->
            read("entity:$providerId:$kind:$id")?.let { sourceCollection(providerId, it) }
        } }
        return SourceLibrary(tracks = (offline.mapNotNull(::lookup) + savedTracks).distinctBy(Track::id), favorites = favorites,
            collections = listOf("album", "playlist", "artist", "folder").flatMap(::entities).distinctBy { it.identity }, home = entities("home"))
            .also { cachedLibrary = providerIds.toSet() to it }
    }
    /** Upgrade files downloaded before the unified library had an explicit offline index. */
    @Synchronized private fun offlineIds(providerId: String): List<String> {
        val key = "offline:$providerId"
        read(key)?.optJSONArray("ids")?.let { return it.strings() }
        val ids = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { candidate ->
            runCatching { JSONObject(candidate.readText()) }.getOrNull()?.takeIf {
                it.optString("providerId") == providerId && it.optString("offlineUri").isNotBlank()
            }?.optString("id")
        }.distinct().take(10_000)
        write(key, JSONObject().put("ids", JSONArray(ids)))
        return ids
    }
    @Synchronized
    fun clearPages(providerId: String, includeLibrary: Boolean = false) {
        if (includeLibrary) cachedLibrary = null
        memory.evictAll()
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { candidate ->
            runCatching { JSONObject(candidate.readText()) }.getOrNull()?.takeIf {
                it.optString("cacheProvider") == providerId && (includeLibrary || !it.optBoolean("libraryCache"))
            }?.let { candidate.delete() }
        }
    }
    fun cacheKey(providerId: String, args: JSONObject) = "page:$providerId:$args"
    fun cache(providerId: String, args: JSONObject, result: JSONObject) = write(cacheKey(providerId, args), JSONObject(result.toString()).put("cacheProvider", providerId))
    fun cached(providerId: String, args: JSONObject) = read(cacheKey(providerId, args))
    companion object {
        private val LIBRARY_KINDS = setOf("track", "album", "playlist", "artist", "folder", "home")
        private val lock = Any()
        private var instance: SourceMediaStore? = null
        fun get(context: Context): SourceMediaStore = synchronized(lock) { instance ?: SourceMediaStore(context.applicationContext).also { instance = it } }
    }
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
