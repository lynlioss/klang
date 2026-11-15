package app.lynli.klang.importing

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

internal enum class ImportSource(val label: String) { SPOTIFY("Spotify"), YOUTUBE("YouTube Music") }

/** Only canonical, public song/playlist URLs cross into the downloader. */
internal data class ImportLink(val url: String, val source: ImportSource, val playlist: Boolean, val id: String) {
    companion object {
        fun parse(input: String): ImportLink {
            val raw = input.trim()
            val spotifyUri = Regex("spotify:(track|playlist):([A-Za-z0-9]{22})").matchEntire(raw)
            if (spotifyUri != null) return spotify(spotifyUri.groupValues[1], spotifyUri.groupValues[2])
            val uri = runCatching { URI(raw) }.getOrNull()
                ?: throw IllegalArgumentException("Paste a Spotify or YouTube Music song or playlist link.")
            require(uri.scheme?.lowercase() in listOf("https", "http") && uri.userInfo == null) {
                "Paste a Spotify or YouTube Music song or playlist link."
            }
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val path = uri.path.orEmpty().trim('/')
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()
            if (host == "open.spotify.com") {
                val parts = path.split('/').filterNot { it.startsWith("intl-") || it == "embed" }
                require(parts.size == 2 && parts[0] in listOf("track", "playlist") &&
                    parts[1].matches(Regex("[A-Za-z0-9]{22}"))) { "Use a Spotify song or playlist link." }
                return spotify(parts[0], parts[1])
            }
            require(host in listOf("music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be")) {
                "Use a Spotify or YouTube Music link."
            }
            // A shared watch URL with list= still represents the selected song.
            val video = if (host == "youtu.be") path else if (path == "watch") query["v"] else null
            if (video != null && video.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                return ImportLink("https://music.youtube.com/watch?v=$video", ImportSource.YOUTUBE, false, video)
            }
            val list = query["list"]
            require(path == "playlist" && list != null && list.matches(Regex("[A-Za-z0-9_-]{10,150}"))) {
                "Use a song link or a playlist link from the Share menu."
            }
            return ImportLink("https://music.youtube.com/playlist?list=$list", ImportSource.YOUTUBE, true, list)
        }
        private fun spotify(type: String, id: String) =
            ImportLink("https://open.spotify.com/$type/$id", ImportSource.SPOTIFY, type == "playlist", id)
    }
}

internal data class ImportSong(
    val sourceId: String, val url: String, val title: String, val artist: String = "",
    val album: String = "", val durationMs: Long = 0, val artwork: String? = null,
    val date: String? = null, val trackNumber: Int = 0, val discNumber: Int = 0,
    val spotify: Boolean = false,
)

internal const val ORIGINAL_AUDIO_FORMAT = "bestaudio[acodec~='^(opus|vorbis|mp4a[.].*|aac|flac|mp3)$']"
internal const val STREAM_COPY_ARGUMENTS = "ExtractAudio+ffmpeg_o:-c:a copy"

internal data class ImportPreview(
    val link: ImportLink, val title: String, val songs: List<ImportSong>, val artwork: String? = null,
    val notice: String? = null,
)

internal enum class ImportPhase(val label: String) {
    QUEUED("Queued"), MATCHING("Finding recording"), DOWNLOADING("Downloading"),
    TAGGING("Adding tags & artwork"), SAVING("Adding to library"),
    COMPLETE("Added"), FAILED("Needs attention"), CANCELLED("Cancelled");
    val active: Boolean get() = this in listOf(MATCHING, DOWNLOADING, TAGGING, SAVING)
    val finished: Boolean get() = this in listOf(COMPLETE, FAILED, CANCELLED)
}

internal data class ImportEntry(
    val song: ImportSong, val phase: ImportPhase = ImportPhase.QUEUED,
    val progress: Float = 0f, val speed: String? = null, val etaSeconds: Long = -1,
    val error: String? = null, val uri: String? = null, val recording: String? = null,
)

internal data class ImportBatch(
    val id: String = UUID.randomUUID().toString(), val title: String, val source: ImportSource,
    val entries: List<ImportEntry>, val playlistId: String? = null, val createdAt: Long = System.currentTimeMillis(),
) {
    val complete: Int get() = entries.count { it.phase == ImportPhase.COMPLETE }
    val failed: Int get() = entries.count { it.phase == ImportPhase.FAILED }
    val pending: Boolean get() = entries.any { !it.phase.finished }
    val progress: Float get() = if (entries.isEmpty()) 0f else entries.sumOf {
        when { it.phase.finished -> 1.0; it.phase == ImportPhase.DOWNLOADING -> it.progress.toDouble() * .9
            it.phase == ImportPhase.TAGGING -> .93; it.phase == ImportPhase.SAVING -> .98; else -> 0.0 }
    }.toFloat() / entries.size
}

internal object ImportCodec {
    fun encode(batches: List<ImportBatch>): String = JSONArray().apply {
        batches.forEach { batch -> put(JSONObject().apply {
            put("id", batch.id); put("title", batch.title); put("source", batch.source.name)
            put("playlistId", batch.playlistId); put("created", batch.createdAt)
            put("entries", JSONArray().apply { batch.entries.forEach { entry -> put(JSONObject().apply {
                put("song", songJson(entry.song)); put("phase", entry.phase.name); put("progress", entry.progress)
                put("speed", entry.speed); put("eta", entry.etaSeconds); put("error", entry.error)
                put("uri", entry.uri); put("recording", entry.recording)
            }) } })
        }) }
    }.toString()

    fun decode(raw: String): List<ImportBatch> = JSONArray(raw).objects().map { batch ->
        ImportBatch(batch.getString("id"), batch.getString("title"), ImportSource.valueOf(batch.getString("source")),
            batch.getJSONArray("entries").objects().map { entry ->
                ImportEntry(readSong(entry.getJSONObject("song")), ImportPhase.valueOf(entry.getString("phase")),
                    entry.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f), entry.stringOrNull("speed"),
                    entry.optLong("eta", -1), entry.stringOrNull("error"), entry.stringOrNull("uri"),
                    entry.stringOrNull("recording"))
            }, batch.stringOrNull("playlistId"), batch.getLong("created"))
    }

    private fun songJson(song: ImportSong) = JSONObject().apply {
        put("id", song.sourceId); put("url", song.url); put("title", song.title); put("artist", song.artist)
        put("album", song.album); put("duration", song.durationMs); put("artwork", song.artwork)
        put("date", song.date); put("track", song.trackNumber); put("disc", song.discNumber); put("spotify", song.spotify)
    }
    private fun readSong(json: JSONObject) = ImportSong(json.getString("id"), json.getString("url"),
        json.getString("title"), json.optString("artist"), json.optString("album"), json.optLong("duration"),
        json.stringOrNull("artwork"), json.stringOrNull("date"), json.optInt("track"), json.optInt("disc"),
        json.optBoolean("spotify"))
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.stringOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
