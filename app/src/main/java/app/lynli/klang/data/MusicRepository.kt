package app.lynli.klang.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Local source boundary; future streaming sources can supply the same MediaItem contract. */
data class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val added: Long = 0,
) {
    val id: String get() = uri.toString()
    fun mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .setAlbumTitle(album).build()).build()
}

class MusicRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val prefs = context.getSharedPreferences("klang", Context.MODE_PRIVATE)

    fun hasLibraryPermission(): Boolean = ContextCompat.checkSelfPermission(context,
        Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    suspend fun tracks(): List<Track> = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED)
        val result = mutableListOf<Track>()
        // Android also exposes app-owned MediaStore entries without broad library permission.
        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.IS_PENDING} = 0",
            null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                val filename = cursor.getString(5).orEmpty().substringBeforeLast('.')
                result += Track(uri, usable(cursor.getString(1), filename),
                    usable(cursor.getString(2), "Unknown artist"), usable(cursor.getString(3), "Unknown album"),
                    cursor.getLong(4), cursor.getLong(6))
            }
        }
        result
    }

    /** Pending rows hide partial files. A failed copy is rolled back, and retries never duplicate packs. */
    suspend fun installStarter(): Int = withContext(Dispatchers.IO) {
        val catalog = JSONArray(context.assets.open("starter/catalog.json").bufferedReader().use { it.readText() })
        var count = 0
        for (i in 0 until catalog.length()) {
            val entry = catalog.getJSONObject(i)
            val name = entry.getString("file")
            val exists = resolver.query(collection, arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0",
                arrayOf("Music/Klang/", name), null)?.use { it.moveToFirst() } ?: false
            if (exists) continue
            writeMusic(name, entry.getString("title"), entry.getString("artist"),
                entry.getString("album"), entry.getLong("durationMs"), if (name.endsWith(".m4a")) "audio/mp4" else "audio/mpeg") { output ->
                context.assets.open("starter/$name").use { it.copyTo(output) }
            }
            count++
        }
        prefs.edit().putBoolean("starterInstalled", true).apply()
        count
    }
    fun needsStarter() = !prefs.getBoolean("starterInstalled", false)

    suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (source in uris) {
            val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported audio.mp3"
            val mime = resolver.getType(source).orEmpty()
            require(mime.startsWith("audio/") || name.substringAfterLast('.').lowercase() in
                listOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")) { "Please choose an audio file" }
            val retriever = MediaMetadataRetriever()
            val track = try {
                retriever.setDataSource(context, source)
                Track(source, usable(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), name.substringBeforeLast('.')),
                    usable(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), "Unknown artist"),
                    usable(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), "Unknown album"),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
            } finally { retriever.release() }
            writeMusic(name, track.title, track.artist, track.album, track.durationMs, mime.ifBlank { "audio/mpeg" }) { output ->
                requireNotNull(resolver.openInputStream(source)) { "Could not open $name" }.use { it.copyTo(output) }
            }
            count++
        }
        count
    }

    private fun writeMusic(name: String, title: String, artist: String, album: String, duration: Long,
        mime: String = "audio/mpeg", copy: (java.io.OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Klang/")
            put(MediaStore.Audio.Media.TITLE, title); put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album); put(MediaStore.Audio.Media.DURATION, duration)
            put(MediaStore.Audio.Media.IS_MUSIC, 1); put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val dest = requireNotNull(resolver.insert(collection, values)) { "Could not create music file" }
        try {
            requireNotNull(resolver.openOutputStream(dest)).use(copy)
            resolver.update(dest, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) { resolver.delete(dest, null, null); throw error }
    }
}

internal fun usable(value: String?, fallback: String) = value?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: fallback
