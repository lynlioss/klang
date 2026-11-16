package app.lynli.klang.plugins

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.lynli.klang.data.Track
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Durable catalog identity and offline mapping; stores metadata, never playback URLs or credentials. */
internal class SourceMediaStore(private val context: Context) {
    private val directory = File(context.filesDir, "music-sources").apply { mkdirs() }
    private val memory = android.util.LruCache<String, JSONObject>(500)
    private val offlineChecks = ConcurrentHashMap<String, Pair<Long, Uri?>>()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()
    private fun file(key: String) = AtomicFile(File(directory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"))
    @Synchronized private fun read(key: String): JSONObject? {
        memory.get(key)?.let { return JSONObject(it.toString()) }
        return runCatching { file(key).openRead().use {
            val bytes = it.readNBytes(256_001); require(bytes.size <= 256_000)
            JSONObject(bytes.toString(Charsets.UTF_8))
        } }.getOrNull()?.also { memory.put(key, JSONObject(it.toString())) }
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
        read(key)?.optString("offlineUri")?.takeIf { it.isNotBlank() }?.let { json.put("offlineUri", it) }
        write(key, json)
        return toTrack(key, json)
    }
    private fun toTrack(key: String, json: JSONObject): Track = Track(Uri.parse(key), json.getString("title"),
        json.optString("artist"), json.optString("album"), json.optLong("durationMs"), albumArtist = json.optString("albumArtist"),
        providerId = json.getString("providerId"), sourceId = json.getString("id"), sourceBadge = json.optString("badge", "music"),
        sourceLabel = json.optString("linkLabel"), shareUrl = json.optString("url").takeIf { it.startsWith("https://") },
        artworkUrl = json.optString("artwork").takeIf { it.startsWith("https://") },
        sourcePayload = providerItem(json).toString(), offline = offlineUri(key) != null)
    fun lookup(key: String): Track? = if (!key.startsWith("klang-source:")) null else read(key)?.let { toTrack(key, it) }
    fun item(key: String): JSONObject = providerItem(read(key) ?: error("This song's catalog metadata is unavailable. Open it again in its music source."))
    private fun providerItem(json: JSONObject) = JSONObject(json.toString()).apply {
        listOf("providerId", "badge", "linkLabel", "offlineUri").forEach { remove(it) }
    }
    fun offlineUri(key: String): Uri? {
        offlineChecks[key]?.takeIf { System.currentTimeMillis() - it.first < 10_000 }?.let { return it.second }
        val value = read(key)?.optString("offlineUri")?.takeIf { it.isNotBlank() }?.let { raw ->
        val uri = Uri.parse(raw)
        if (uri.scheme != "content") null else runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { uri } }.getOrNull()
        }
        if (offlineChecks.size > 500) offlineChecks.clear()
        offlineChecks[key] = System.currentTimeMillis() to value
        return value
    }
    @Synchronized
    fun markOffline(providerId: String, sourceId: String, uri: String) {
        require(Uri.parse(uri).scheme == "content")
        val key = identity(providerId, sourceId)
        read(key)?.let { write(key, it.put("offlineUri", uri)) }
        offlineChecks.remove(key)
        mutableChanges.value++
    }
    @Synchronized fun acceptAccount(providerId: String, revision: String) {
        require(revision.length in 1..200)
        val key = "account:$providerId"
        if (read(key)?.optString("revision") == revision) return
        clearPages(providerId)
        write(key, JSONObject().put("revision", revision))
        mutableChanges.value++
    }
    @Synchronized
    fun clearPages(providerId: String) {
        memory.evictAll()
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { candidate ->
            runCatching { JSONObject(candidate.readText()) }.getOrNull()?.takeIf { it.optString("cacheProvider") == providerId }?.let { candidate.delete() }
        }
    }
    fun cacheKey(providerId: String, args: JSONObject) = "page:$providerId:$args"
    fun cache(providerId: String, args: JSONObject, result: JSONObject) = write(cacheKey(providerId, args), JSONObject(result.toString()).put("cacheProvider", providerId))
    fun cached(providerId: String, args: JSONObject) = read(cacheKey(providerId, args))
    companion object {
        private val lock = Any()
        private var instance: SourceMediaStore? = null
        fun get(context: Context): SourceMediaStore = synchronized(lock) { instance ?: SourceMediaStore(context.applicationContext).also { instance = it } }
    }
}
