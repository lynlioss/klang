package app.lynli.klang.lyrics

import android.content.Context
import app.lynli.klang.plugins.PluginConfiguration
import app.lynli.klang.plugins.PluginManifest
import app.lynli.klang.plugins.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class LyricsRepository internal constructor(private val directory: File, private val manifests: List<PluginManifest>,
    private val registeredProviders: List<LyricsProvider>, private val nowMs: () -> Long = System::currentTimeMillis,
    private val registry: PluginRegistry? = null) {
    constructor(context: Context, registry: PluginRegistry) : this(File(context.filesDir, "lyrics-v2"), emptyList(), emptyList(), registry = registry)

    private companion object {
        const val LOOKUP_VERSION = 3
        const val CACHE_REFRESH_MS = 24 * 60 * 60 * 1000L
    }
    private val cacheMutex = Mutex()
    suspend fun find(query: LyricsQuery, config: PluginConfiguration): LyricsState = cacheMutex.withLock { withContext(Dispatchers.IO) {
        val definitions = registry?.manifests ?: manifests
        val providers = (registry?.lyricsProviders(config) ?: registeredProviders).filter { it.id in config.enabled }
        if (providers.isEmpty()) return@withContext LyricsState.Disabled
        var error: Exception? = null
        for (provider in providers) {
            val manifest = definitions.firstOrNull { it.id == provider.id } ?: continue
            val cache = manifest.settings.none { it.id == "cacheLyrics" } || config.boolean(manifest, "cacheLyrics")
            val settingsKey = manifest.settings.filterNot { it.id in setOf("cacheLyrics", "timingOffset") }
                .joinToString("|") { "${it.id}=${config.value(manifest, it)}" }
            // Keep the original key for providers without request settings, preserving offline
            // LRCLIB lyrics across the migration. Provider updates refresh the cached document.
            val key = MessageDigest.getInstance("SHA-256").digest((provider.id + "|" +
                (if (settingsKey.isEmpty()) "" else "$settingsKey|") + query)
                .toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(directory, "$key.json")
            try {
                val cachedJson = if (cache && file.isFile && file.length() <= 2_000_000)
                    runCatching { JSONObject(file.readText()) }.getOrNull() else null
                val cached = cachedJson?.let { runCatching { decode(it) }.getOrNull() }
                val stale = cached != null && (cachedJson?.optInt("lookupVersion") != LOOKUP_VERSION ||
                    cachedJson?.optString("pluginVersion") != manifest.version || nowMs() - file.lastModified() >= CACHE_REFRESH_MS)
                val fetched = if (cached == null || stale) {
                    try { provider.find(query) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (cached == null) throw failure else null }
                } else null
                // Keep cached lyrics on failed refresh and never downgrade real word timings.
                val document = fetched?.takeIf { cached == null || it.timingQuality >= cached.timingQuality } ?: cached ?: continue
                if (cache && fetched != null) runCatching {
                    directory.mkdirs()
                    val temp = File(directory, "$key.tmp")
                    temp.writeText(encode(document, manifest.version).toString()); if (!temp.renameTo(file)) temp.delete() else file.setLastModified(nowMs())
                    var bytes = 0L
                    directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, entry ->
                        bytes += entry.length()
                        if (index >= 200 || bytes > 24_000_000) entry.delete()
                    }
                }
                return@withContext LyricsState.Ready(document)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure }
        }
        error?.let { LyricsState.Error(it.message ?: "Couldn't connect. Check your connection and try again.") } ?: LyricsState.NotFound
    } }

    suspend fun clearCache(id: String) = cacheMutex.withLock { withContext(Dispatchers.IO) {
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            if (runCatching { JSONObject(file.readText()).optString("provider") == id }.getOrDefault(true)) file.delete()
        }
    } }
    private fun encode(doc: LyricsDocument, pluginVersion: String) = JSONObject().put("lookupVersion", LOOKUP_VERSION).put("pluginVersion", pluginVersion)
        .put("provider", doc.providerId).put("plain", doc.plain).put("instrumental", doc.instrumental)
        .put("lines", JSONArray(doc.lines.map { line -> JSONObject().put("text", line.text).put("start", line.startMs).put("end", line.endMs)
            .put("words", JSONArray(line.words.map { word -> JSONObject().put("text", word.text).put("start", word.startMs).put("end", word.endMs) })) }))
    private fun decode(json: JSONObject): LyricsDocument {
        val lines = json.getJSONArray("lines")
        return LyricsDocument(json.getString("provider"), json.getString("plain"), (0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i); val words = line.getJSONArray("words")
            LyricLine(line.getString("text"), line.getLong("start"), line.getLong("end"), (0 until words.length()).map { j ->
                val word = words.getJSONObject(j); LyricWord(word.getString("text"), word.getLong("start"), word.getLong("end"))
            })
        }, json.getBoolean("instrumental"))
    }
}
