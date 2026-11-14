package app.lynli.klang.lyrics

import app.lynli.klang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs
import kotlin.random.Random

data class LyricsHttpResponse(val status: Int, val body: String, val retryAfterSeconds: Long? = null)
fun interface LyricsHttpClient { suspend fun get(path: String, parameters: Map<String, String>): LyricsHttpResponse }

class LrclibHttpClient : LyricsHttpClient {
    private val mutex = Mutex()
    private var nextRequestNanos = 0L
    override suspend fun get(path: String, parameters: Map<String, String>): LyricsHttpResponse = mutex.withLock {
        val waitMs = (nextRequestNanos - System.nanoTime()) / 1_000_000
        if (waitMs > 0) delay(waitMs + 1)
        try {
            val response = runInterruptible(Dispatchers.IO) {
                val query = parameters.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
                val connection = URI("https://lrclib.net$path?$query").toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "Klang v${BuildConfig.VERSION_NAME} (https://github.com/lynlioss/klang)")
                    val status = connection.responseCode
                    val retry = connection.getHeaderField("Retry-After")?.toLongOrNull()
                    val body = if (status in 200..299) {
                        if (!connection.contentType.orEmpty().contains("json", ignoreCase = true)) throw IOException("Unexpected lyrics response")
                        connection.inputStream.use { stream ->
                            val bytes = stream.readNBytes(2_000_001)
                            if (bytes.size > 2_000_000) throw IOException("Lyrics response is too large")
                            bytes.toString(Charsets.UTF_8)
                        }
                    } else ""
                    LyricsHttpResponse(status, body, retry)
                } finally { connection.disconnect() }
            }
            if (response.status == 429 || response.status == 503) {
                val seconds = (response.retryAfterSeconds ?: if (response.status == 429) 60 else 1).coerceAtLeast(1)
                nextRequestNanos = System.nanoTime() + seconds.coerceAtMost(86_400) * 1_000_000_000 + Random.nextLong(0, 300_000_000)
            }
            response
        } finally {
            nextRequestNanos = maxOf(nextRequestNanos, System.nanoTime() + 300_000_000)
        }
    }
}

class LrclibProvider(private val client: LyricsHttpClient = LrclibHttpClient()) : LyricsProvider {
    override val id = "lrclib"
    override suspend fun find(query: LyricsQuery): LyricsDocument? {
        if (query.title.isBlank() || query.artist.isBlank() || query.artist == "Unknown artist") return null
        val parameters = linkedMapOf("track_name" to query.title, "artist_name" to query.artist)
        if (query.album.isNotBlank() && query.album != "Unknown album") parameters["album_name"] = query.album
        if (query.durationMs in 1000..3_600_000) parameters["duration"] = (query.durationMs / 1000.0).toString()
        val exact = client.get("/api/get", parameters)
        val exactDocument = when (exact.status) {
            200 -> parse(JSONObject(exact.body), query)
            404 -> null
            else -> fail(exact)
        }
        if (exactDocument?.let { it.instrumental || it.hasWordTiming } == true) return exactDocument
        // An album-specific match can be an older, line-only copy of the same recording.
        // Require a known duration before looking for an upgrade across album editions.
        if (exactDocument != null && query.durationMs !in 1000..3_600_000) return exactDocument
        try {
            val search = client.get("/api/search", parameters.filterKeys { it == "track_name" || it == "artist_name" })
            if (search.status != 200) fail(search)
            val records = JSONArray(search.body)
            val candidates = (0 until records.length()).map(records::getJSONObject).filter { record ->
                normalized(record.optString("trackName")) == normalized(query.title) &&
                    normalized(record.optString("artistName")) == normalized(query.artist) &&
                    (query.durationMs <= 0 || abs(record.optDouble("duration", Double.NaN) * 1000 - query.durationMs) <= 2000)
            }.mapNotNull { record -> parse(record, query)?.let { Candidate(record, it) } }
                .filter { exactDocument == null || (!it.document.instrumental && it.document.timingQuality > exactDocument.timingQuality) }
                .sortedWith(compareByDescending<Candidate> { it.document.timingQuality }
                    .thenByDescending { normalized(it.record.optString("albumName")) == normalized(query.album) }
                    .thenBy { abs(it.record.optDouble("duration", 0.0) * 1000 - query.durationMs) })
            return candidates.firstOrNull()?.document ?: exactDocument
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Optional enrichment must never take usable lyrics away during a service failure.
            return exactDocument ?: throw failure
        }
    }
    private data class Candidate(val record: JSONObject, val document: LyricsDocument)
    private fun parse(record: JSONObject, query: LyricsQuery) = LyricsParser.record(record, query.durationMs)
        .takeIf { it.instrumental || it.plain.isNotBlank() || it.lines.isNotEmpty() }
    private fun fail(response: LyricsHttpResponse): Nothing = throw IOException(when (response.status) {
        429 -> "LRCLIB is receiving too many requests. Try again in ${response.retryAfterSeconds ?: 60} seconds."
        503 -> "LRCLIB is busy. Try again shortly."
        else -> "LRCLIB couldn't load lyrics (${response.status}). Try again."
    })
    companion object {
        internal fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
    }
}
