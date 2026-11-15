package app.lynli.klang.lyrics

import app.lynli.klang.BuildConfig
import app.lynli.klang.plugins.LyricsEndpoint
import app.lynli.klang.plugins.LyricsResponseMapping
import app.lynli.klang.plugins.PluginConfiguration
import app.lynli.klang.plugins.PluginManifest
import app.lynli.klang.plugins.PluginTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs

/** Shared API 1 runtime. Providers never receive a Context, file access, or executable hooks. */
class DeclarativeLyricsProvider(private val manifest: PluginManifest, private val config: PluginConfiguration,
    private val request: suspend (LyricsEndpoint, Map<String, String>, Map<String, String>) -> LyricsHttpResponse = ::requestLyrics) : LyricsProvider {
    override val id = manifest.id
    private val spec = requireNotNull(manifest.lyrics)
    private val mutex = Mutex()
    private var nextRequestNanos = 0L
    override suspend fun find(query: LyricsQuery): LyricsDocument? = mutex.withLock {
        if (query.title.isBlank() || query.artist.isBlank() || query.artist == "Unknown artist") return@withLock null
        var best: LyricsDocument? = null
        for (endpoint in spec.endpoints) {
            if (endpoint.condition == "missing" && best != null) continue
            if (endpoint.condition == "noWordTiming" && best?.let { it.instrumental || it.hasWordTiming || query.durationMs <= 0 } == true) continue
            try {
                val wait = (nextRequestNanos - System.nanoTime()) / 1_000_000
                if (wait > 0) delay(wait + 1)
                val parameters = endpoint.query.mapValues { render(it.value, query) }.filterValues { it.isNotBlank() }
                val headers = endpoint.headers.mapValues { render(it.value, query) }.filterValues { it.isNotBlank() }
                val response = try { request(endpoint, parameters, headers) }
                    finally { nextRequestNanos = System.nanoTime() + spec.requestIntervalMs * 1_000_000L }
                if (response.status == 404 || response.status == 204) continue
                if (response.status == 429 || response.status == 503) {
                    nextRequestNanos = System.nanoTime() + (response.retryAfterSeconds ?: 60).coerceIn(1, 86_400) * 1_000_000_000
                    throw IOException("${manifest.name} is busy. Try again later.")
                }
                if (response.status !in 200..299) throw IOException("${manifest.name} couldn't load lyrics (${response.status}).")
                val candidates = decode(response.body, endpoint.response, query)
                val candidate = candidates.sortedWith(compareByDescending<Pair<LyricsDocument, JSONObject?>> { it.first.timingQuality }
                    .thenByDescending { endpoint.response.album.isNotEmpty() &&
                        LrclibProvider.normalized(text(it.second, endpoint.response.album)) == LrclibProvider.normalized(query.album) }
                    .thenBy { abs(number(it.second, endpoint.response.duration) * 1000 - query.durationMs) }).firstOrNull()?.first
                val previous = best
                if (candidate != null && (previous == null || (!candidate.instrumental && candidate.timingQuality > previous.timingQuality))) best = candidate
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (best == null) throw error }
        }
        best
    }
    private fun render(template: String, query: LyricsQuery): String = PluginTemplates.render(template) { key ->
        when (key) {
            "title" -> query.title
            "artist" -> query.artist
            "album" -> query.album.takeUnless { it == "Unknown album" }.orEmpty()
            "durationSeconds" -> if (query.durationMs > 0) (query.durationMs / 1000.0).toString() else ""
            "durationMs" -> if (query.durationMs > 0) query.durationMs.toString() else ""
            else -> manifest.settings.firstOrNull { it.id == key.removePrefix("settings.") }?.let { config.value(manifest, it) }.orEmpty()
        }
    }
    internal fun decode(body: String, mapping: LyricsResponseMapping, query: LyricsQuery): List<Pair<LyricsDocument, JSONObject?>> {
        if (mapping.format != "json") {
            val doc = when (mapping.format) {
                "lrc" -> LyricsDocument(id, "", LyricsParser.lrc(body, query.durationMs))
                "lyricsfile" -> LyricsParser.lyricsfile(body, query.durationMs).copy(providerId = id)
                else -> LyricsDocument(id, body, emptyList())
            }
            return listOf(doc.withPlainText() to null).filter { usable(it.first) }
        }
        val root = JSONTokener(body).nextValue()
        val records = if (mapping.records.isBlank()) root else path(root, mapping.records)
        val objects = when (records) {
            is JSONArray -> (0 until records.length().coerceAtMost(200)).mapNotNull { records.optJSONObject(it) }
            is JSONObject -> listOf(records)
            else -> emptyList()
        }
        return objects.filter { record ->
            (mapping.title.isEmpty() || LrclibProvider.normalized(text(record, mapping.title)) == LrclibProvider.normalized(query.title)) &&
                (mapping.artist.isEmpty() || LrclibProvider.normalized(text(record, mapping.artist)) == LrclibProvider.normalized(query.artist)) &&
                (mapping.duration.isEmpty() || query.durationMs <= 0 || abs(number(record, mapping.duration) * 1000 - query.durationMs) <= mapping.durationToleranceMs)
        }.mapNotNull { record ->
            val canonical = JSONObject().put("plainLyrics", text(record, mapping.plain)).put("syncedLyrics", text(record, mapping.synced))
                .put("lyricsfile", text(record, mapping.lyricsfile)).put("instrumental", path(record, mapping.instrumental) == true)
            LyricsParser.record(canonical, query.durationMs).copy(providerId = id).takeIf(::usable)?.let { it to record }
        }
    }
    private fun usable(doc: LyricsDocument) = doc.instrumental || doc.plain.isNotBlank() || doc.lines.isNotEmpty()
    private fun LyricsDocument.withPlainText() = copy(plain = plain.ifBlank { lines.joinToString("\n") { it.text } })
    private fun path(root: Any?, key: String): Any? = if (key.isBlank()) null else key.split('.').fold(root) { value, part ->
        when (value) { is JSONObject -> value.opt(part); is JSONArray -> part.toIntOrNull()?.let(value::opt); else -> null }
    }?.takeUnless { it == JSONObject.NULL }
    private fun text(root: JSONObject?, key: String) = (path(root, key) as? String).orEmpty()
    private fun number(root: JSONObject?, key: String) = (path(root, key) as? Number)?.toDouble() ?: Double.NaN
}

private suspend fun requestLyrics(endpoint: LyricsEndpoint, parameters: Map<String, String>, headers: Map<String, String>): LyricsHttpResponse =
    runInterruptible(Dispatchers.IO) {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }
        val url = endpoint.url + (if (query.isBlank()) "" else (if ('?' in endpoint.url) "&" else "?") + query)
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json, text/plain, application/yaml")
            connection.setRequestProperty("User-Agent", "Klang/${BuildConfig.VERSION_NAME}")
            headers.forEach { (key, value) ->
                require('\r' !in value && '\n' !in value) { "Invalid plugin header" }
                connection.setRequestProperty(key, value)
            }
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream.use { stream ->
                val bytes = stream.readNBytes(2_000_001)
                if (bytes.size > 2_000_000) throw IOException("Lyrics response is too large")
                bytes.toString(Charsets.UTF_8)
            } else ""
            LyricsHttpResponse(status, body, connection.getHeaderField("Retry-After")?.toLongOrNull())
        } finally { connection.disconnect() }
    }
