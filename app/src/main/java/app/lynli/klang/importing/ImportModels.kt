package app.lynli.klang.importing

import app.lynli.klang.plugins.InstalledPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** The app validates links and chooses a provider; source-specific interpretation belongs to plugins. */
internal data class ImportLink(val url: String) {
    companion object {
        fun parse(input: String): ImportLink {
            val raw = input.trim()
            require(raw.length in 1..4096) { "Paste a music link." }
            val uri = try { URI(raw) } catch (error: java.net.URISyntaxException) {
                throw IllegalArgumentException("Paste a valid music link.", error)
            }
            require(uri.scheme != null && uri.userInfo == null && uri.scheme.lowercase() !in
                setOf("file", "content", "intent", "javascript", "data")) { "Paste a music link." }
            if (uri.scheme.lowercase() in setOf("https", "http")) require(!uri.host.isNullOrBlank()) { "Paste a music link." }
            return ImportLink(raw)
        }
    }
    fun supportedBy(plugin: InstalledPlugin): Boolean {
        val spec = plugin.manifest.musicImport ?: return false
        val uri = URI(url)
        return if (uri.scheme.lowercase() in setOf("https", "http")) uri.host.lowercase() in spec.hosts
            else uri.scheme.lowercase() in spec.schemes
    }
}

internal data class ImportSong(val sourceId: String, val url: String, val title: String, val artist: String = "",
    val album: String = "", val durationMs: Long = 0, val artwork: String? = null, val payload: String = "{}")

internal data class ImportPreview(val link: ImportLink, val providerId: String, val title: String,
    val songs: List<ImportSong>, val collection: Boolean = false, val artwork: String? = null, val notice: String? = null)

internal enum class ImportPhase(val label: String) {
    QUEUED("Queued"), MATCHING("Preparing"), DOWNLOADING("Downloading"), TAGGING("Finishing"), SAVING("Saving"),
    DUPLICATE("Already on this device"), COMPLETE("Downloaded"), FAILED("Needs attention"), CANCELLED("Skipped");
    val active get() = this in listOf(MATCHING, DOWNLOADING, TAGGING, SAVING)
    val finished get() = this in listOf(DUPLICATE, COMPLETE, FAILED, CANCELLED)
}
internal data class ImportEntry(val song: ImportSong, val phase: ImportPhase = ImportPhase.QUEUED,
    val progress: Float = 0f, val speed: String? = null, val etaSeconds: Long = -1, val error: String? = null,
    val uri: String? = null, val recording: String? = null, val audioFormat: String? = null,
    val duplicateUri: String? = null, val allowDuplicate: Boolean = false)
internal data class ImportBatch(val id: String = UUID.randomUUID().toString(), val title: String, val providerId: String,
    val entries: List<ImportEntry>, val playlistId: String? = null, val createdAt: Long = System.currentTimeMillis()) {
    val pending get() = entries.any { !it.phase.finished }
    val complete get() = entries.count { it.phase == ImportPhase.COMPLETE }
    val failed get() = entries.count { it.phase == ImportPhase.FAILED }
    val warnings get() = entries.any { it.phase in listOf(ImportPhase.FAILED, ImportPhase.DUPLICATE) }
    val progress get() = downloadProgress(entries)
}
internal fun downloadProgress(entries: List<ImportEntry>): Float {
    val downloads = entries.filter { it.phase !in listOf(ImportPhase.DUPLICATE, ImportPhase.CANCELLED, ImportPhase.FAILED) }
    return if (downloads.isEmpty()) 0f else downloads.sumOf {
        when(it.phase) {
            ImportPhase.COMPLETE -> 1.0
            ImportPhase.DOWNLOADING -> it.progress * .9
            ImportPhase.TAGGING -> .93
            ImportPhase.SAVING -> .98
            else -> 0.0
        }
    }.toFloat() / downloads.size
}
internal object ImportCodec {
    fun songJson(song: ImportSong) = JSONObject().put("id", song.sourceId).put("url", song.url).put("title", song.title)
        .put("artist", song.artist).put("album", song.album).put("duration", song.durationMs).put("artwork", song.artwork)
        .put("payload", song.payload)
    fun readSong(json: JSONObject) = ImportSong(json.getString("id"), json.getString("url"), json.getString("title"),
        json.optString("artist"), json.optString("album"), json.optLong("duration"), json.stringOrNull("artwork"),
        json.stringOrNull("payload") ?: json.toString()).also {
            require(it.title.isNotBlank() && it.title.length <= 1000 && it.artist.length <= 1000 && it.payload.length <= 128_000) {
                "The plugin returned invalid song metadata."
            }
        }
    fun encode(batches: List<ImportBatch>) = JSONArray(batches.map { batch ->
        JSONObject().put("id", batch.id).put("title", batch.title).put("providerId", batch.providerId)
            .put("playlistId", batch.playlistId).put("created", batch.createdAt).put("entries", JSONArray(batch.entries.map { entry ->
                JSONObject().put("song", songJson(entry.song)).put("phase", entry.phase.name).put("progress", entry.progress)
                    .put("speed", entry.speed).put("eta", entry.etaSeconds).put("error", entry.error).put("uri", entry.uri)
                    .put("recording", entry.recording).put("audioFormat", entry.audioFormat)
                    .put("duplicateUri", entry.duplicateUri).put("allowDuplicate", entry.allowDuplicate)
            }))
    }).toString()
    fun decode(raw: String) = JSONArray(raw).objects().map { batch ->
        ImportBatch(batch.getString("id"), batch.getString("title"), batch.optString("providerId", "music-import"),
            batch.getJSONArray("entries").objects().map { entry ->
                ImportEntry(readSong(entry.getJSONObject("song")), ImportPhase.valueOf(entry.getString("phase")),
                    entry.optDouble("progress", 0.0).toFloat(), entry.stringOrNull("speed"), entry.optLong("eta", -1),
                    entry.stringOrNull("error"), entry.stringOrNull("uri"), entry.stringOrNull("recording"),
                    entry.stringOrNull("audioFormat"), entry.stringOrNull("duplicateUri"), entry.optBoolean("allowDuplicate"))
            }, batch.stringOrNull("playlistId"), batch.getLong("created"))
    }
}
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.stringOrNull(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
