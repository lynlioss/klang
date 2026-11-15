package app.lynli.klang.importing

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal object ImportEngine {
    fun initialize(context: Context) {
        YoutubeDL.init(context.applicationContext)
        FFmpeg.init(context.applicationContext)
    }

    @Synchronized
    fun refreshExtractor(context: Context) {
        val prefs = context.getSharedPreferences("music_import_engine", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("updated", 0) < 24 * 60 * 60 * 1000L) return
        // Bundled engine remains usable when GitHub is unavailable.
        runCatching { YoutubeDL.updateYoutubeDL(context) }.onSuccess {
            prefs.edit().putLong("updated", now).apply()
        }
    }

    fun request(url: String? = null) = YoutubeDLRequest(url?.let { listOf(it) }.orEmpty()).apply {
        addOption("--ignore-config")
        addOption("--socket-timeout", 25)
        addOption("--retries", 3)
        addOption("--extractor-retries", 2)
    }

    fun info(url: String, processId: String, flat: Boolean = false): JSONObject {
        val request = request(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            if (flat) addOption("--flat-playlist")
            else addOption("--no-playlist")
        }
        return JSONObject(YoutubeDL.execute(request, processId, null).out)
    }
}

internal class ImportResolver(private val context: Context) {
    suspend fun resolve(link: ImportLink, processId: String): ImportPreview = withContext(Dispatchers.IO) {
        if (link.source == ImportSource.SPOTIFY) kotlinx.coroutines.runInterruptible { SpotifyMetadata.preview(link) }
        else {
            ImportEngine.initialize(context)
            val json = kotlinx.coroutines.runInterruptible { ImportEngine.info(link.url, processId, flat = link.playlist) }
            coroutineContext.ensureActive()
            val entries = if (link.playlist) json.optJSONArray("entries")?.objects().orEmpty() else listOf(json)
            val songs = entries.mapNotNull { item ->
                val id = item.stringOrNull("id") ?: return@mapNotNull null
                if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) ||
                    item.optString("title") in listOf("[Private video]", "[Deleted video]")) return@mapNotNull null
                youtubeSong(item, id)
            }
            require(songs.isNotEmpty()) { "No accessible songs found. Check that the playlist is public." }
            val omitted = entries.size - songs.size
            ImportPreview(link, json.stringOrNull("title") ?: "YouTube Music", songs,
                thumbnail(json), if (omitted > 0) "$omitted unavailable entries can't be imported." else null)
        }
    }
}

internal fun youtubeSong(json: JSONObject, id: String): ImportSong = ImportSong(
    "youtube:$id", "https://music.youtube.com/watch?v=$id",
    json.stringOrNull("track") ?: json.stringOrNull("title") ?: "Untitled song",
    json.stringOrNull("artist") ?: json.stringOrNull("uploader")?.removeSuffix(" - Topic").orEmpty(),
    json.stringOrNull("album").orEmpty(), (json.optDouble("duration", 0.0) * 1000).toLong(),
    thumbnail(json), json.stringOrNull("release_date"), json.optInt("track_number"), json.optInt("disc_number"),
)

internal fun thumbnail(json: JSONObject): String? = json.optJSONArray("thumbnails")?.objects()
    ?.maxByOrNull { it.optLong("width", 0) * it.optLong("height", 0) }?.stringOrNull("url")
    ?: json.stringOrNull("thumbnail")

/** Public metadata only. Never reads Spotify audio previews as a download source. */
internal object SpotifyMetadata {
    fun preview(link: ImportLink): ImportPreview {
        val kind = if (link.playlist) "playlist" else "track"
        val embedHtml = getText("https://open.spotify.com/embed/$kind/${link.id}")
        val embed = parseEmbed(embedHtml)
        val entity = embed.getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
            .getJSONObject("data").getJSONObject("entity")
        val regular = runCatching { publicEntity(getText(link.url), "spotify:$kind:${link.id}") }.getOrNull()
        if (!link.playlist) {
            val song = regular?.let { parsePublicTrack(it) } ?: parseEmbedTrack(entity)
            return ImportPreview(link, song.title, listOf(song), song.artwork)
        }
        val rich = regular?.optJSONObject("content")?.optJSONArray("items")?.objects().orEmpty()
            .mapNotNull { it.optJSONObject("itemV2")?.optJSONObject("data")?.let(::parsePublicTrack) }
            .associateBy { it.sourceId }
        var songs = entity.optJSONArray("trackList")?.objects().orEmpty().mapNotNull { item ->
            val uri = item.optString("uri")
            if (!uri.startsWith("spotify:track:")) return@mapNotNull null
            rich[uri] ?: parseEmbedTrack(item)
        }
        require(regular != null || songs.size < 100) {
            "Spotify didn't expose the full playlist count. Try again later to avoid an incomplete import."
        }
        val total = regular?.optJSONObject("content")?.optInt("totalCount", songs.size) ?: songs.size
        // Public embeds currently stop at 100. Fetch remaining pages, or fail explicitly.
        if (total > songs.size) {
            val state = embed.getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
            val token = state.getJSONObject("settings").getJSONObject("session").getString("accessToken")
            songs = fullPlaylist(link.id, token, total)
        }
        require(songs.isNotEmpty()) { "No songs found. Spotify playlists must be public." }
        return ImportPreview(link, entity.stringOrNull("title") ?: "Spotify playlist", songs,
            image(entity.optJSONObject("coverArt")) ?: image(entity.optJSONObject("visualIdentity")))
    }

    fun enrich(song: ImportSong): ImportSong {
        if (!song.spotify || (song.album.isNotBlank() && song.artwork != null)) return song
        val entity = publicEntity(getText(song.url), song.sourceId)
            ?: throw IllegalArgumentException("Spotify couldn't provide the song's album and artwork. Try again later.")
        val rich = parsePublicTrack(entity)
        return rich.copy(date = rich.date ?: song.date, durationMs = rich.durationMs.takeIf { it > 0 } ?: song.durationMs)
    }

    private fun fullPlaylist(id: String, token: String, total: Int): List<ImportSong> {
        val songs = mutableListOf<ImportSong>()
        var offset = 0
        while (offset < total) {
            val page = try {
                JSONObject(getText("https://api.spotify.com/v1/playlists/$id/tracks?limit=100&offset=$offset", token))
            } catch (error: Exception) {
                throw IllegalArgumentException("Spotify didn't expose the full playlist ($total songs). Try again later or import smaller playlists.", error)
            }
            val items = page.getJSONArray("items").objects()
            require(items.isNotEmpty()) { "Spotify returned an incomplete playlist. Try again later." }
            items.mapNotNullTo(songs) { wrapper ->
                val item = wrapper.optJSONObject("track") ?: wrapper.optJSONObject("item")
                item?.takeIf { it.optString("type") == "track" && !it.optBoolean("is_local") }?.let(::parseApiTrack)
            }
            offset += items.size
        }
        return songs
    }

    internal fun parseEmbed(html: String): JSONObject {
        val raw = Regex("<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Spotify couldn't read this link. Make sure it is public and try again.")
        return JSONObject(raw)
    }

    internal fun publicEntity(html: String, uri: String): JSONObject? {
        val encoded = Regex("<script[^>]*id=\"initialState\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        return JSONObject(String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8))
            .optJSONObject("entities")?.optJSONObject("items")?.optJSONObject(uri)
    }

    internal fun parseEmbedTrack(json: JSONObject): ImportSong {
        val uri = json.getString("uri")
        val artist = json.optJSONArray("artists")?.objects()?.joinToString(", ") { it.optString("name") }
            ?: json.optString("subtitle").replace('\u00a0', ' ')
        return ImportSong(uri, "https://open.spotify.com/track/${uri.substringAfterLast(':')}",
            json.stringOrNull("title") ?: json.optString("name"), artist, durationMs = json.optLong("duration"),
            artwork = image(json.optJSONObject("visualIdentity")),
            date = json.optJSONObject("releaseDate")?.stringOrNull("isoString")?.take(10), spotify = true)
    }

    internal fun parsePublicTrack(json: JSONObject): ImportSong {
        val uri = json.getString("uri")
        val album = json.optJSONObject("albumOfTrack")
        return ImportSong(uri, "https://open.spotify.com/track/${uri.substringAfterLast(':')}", json.getString("name"),
            json.optJSONObject("artists")?.optJSONArray("items")?.objects()?.joinToString(", ") {
                it.optJSONObject("profile")?.optString("name").orEmpty()
            }.orEmpty(), album?.stringOrNull("name").orEmpty(),
            json.optJSONObject("duration")?.optLong("totalMilliseconds") ?: 0,
            image(album?.optJSONObject("coverArt")), album?.optJSONObject("date")?.stringOrNull("isoString")?.take(10),
            json.optInt("trackNumber"), json.optInt("discNumber"), spotify = true)
    }

    private fun parseApiTrack(json: JSONObject): ImportSong {
        val album = json.optJSONObject("album")
        return ImportSong("spotify:track:${json.getString("id")}", "https://open.spotify.com/track/${json.getString("id")}",
            json.getString("name"), json.getJSONArray("artists").objects().joinToString(", ") { it.getString("name") },
            album?.stringOrNull("name").orEmpty(), json.optLong("duration_ms"),
            album?.optJSONArray("images")?.objects()?.maxByOrNull { it.optInt("width") }?.stringOrNull("url"),
            album?.stringOrNull("release_date"), json.optInt("track_number"), json.optInt("disc_number"), true)
    }

    private fun image(json: JSONObject?): String? {
        val images = json?.optJSONArray("sources") ?: json?.optJSONArray("image")
        return images?.objects()?.maxByOrNull { it.optInt("width", it.optInt("maxWidth")) }?.stringOrNull("url")
    }
}

internal fun getText(url: String, bearer: String? = null): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 25_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
    connection.setRequestProperty("Accept-Language", "en")
    bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
    try {
        require(connection.responseCode in 200..299) {
            when (connection.responseCode) {
                429 -> "The music service is busy. Try again in a few minutes."
                401, 403, 404 -> "This link isn't publicly accessible. Check its sharing settings."
                else -> "Couldn't read the music link (${connection.responseCode}). Try again."
            }
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}

/** Match duration, artist, title and version; reject ambiguous covers/live recordings. */
internal fun recordingScore(song: ImportSong, candidate: JSONObject): Double {
    fun words(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .split(' ').filter { it.isNotBlank() }.toSet()
    val title = candidate.optString("title")
    val text = "$title ${candidate.optString("uploader")} ${candidate.optString("artist")}".lowercase()
    val expectedTitle = words(song.title)
    val expectedArtist = words(song.artist.substringBefore(','))
    if (expectedTitle.isEmpty() || expectedArtist.isEmpty()) return 0.0
    val actual = words(text)
    val titleMatch = expectedTitle.intersect(actual).size.toDouble() / expectedTitle.size
    val artistMatch = expectedArtist.intersect(actual).size.toDouble() / expectedArtist.size
    if (titleMatch < .65 || artistMatch < .5) return 0.0
    val duration = candidate.optDouble("duration", 0.0) * 1000
    val delta = abs(duration - song.durationMs)
    if (song.durationMs > 0 && duration > 0 && delta > maxOf(12_000.0, song.durationMs * .08)) return 0.0
    val versions = listOf("live", "cover", "remix", "karaoke", "instrumental", "sped", "slowed", "nightcore")
    if (versions.any { it in words(title) && it !in words(song.title) }) return 0.0
    val durationMatch = if (song.durationMs > 0 && duration > 0) (1 - delta / 15_000).coerceIn(0.0, 1.0) else .3
    val official = if (text.contains("topic") || text.contains("official audio")) .05 else 0.0
    return titleMatch * .45 + artistMatch * .3 + durationMatch * .2 + official
}
