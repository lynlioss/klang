package app.lynli.klang.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** Reads technical metadata for the properties sheet. Missing values stay null. */
    suspend fun details(track: Track): TrackDetails = withContext(Dispatchers.IO) {
        var displayName: String? = null
        var relativePath: String? = null
        var size: Long? = null
        var mimeFromStore: String? = null
        runCatching {
            resolver.query(track.uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    displayName = cursor.getString(0)
                    relativePath = cursor.getString(1)
                    size = if (cursor.isNull(2)) null else cursor.getLong(2)
                    mimeFromStore = cursor.getString(3)
                }
            }
        }
        var duration = track.durationMs
        var bitrate: Int? = null
        var sampleRate: Int? = null
        var mime = mimeFromStore
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: duration
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
                sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull()?.takeIf { it > 0 }
                if (mime.isNullOrBlank()) mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            } finally { retriever.release() }
        }
        // Fall back to the file size when the container omits an average bitrate.
        val estimated = bitrate ?: size?.takeIf { duration > 0 }?.let { bytes ->
            ((bytes * 8) / duration).toInt().takeIf { it in 8..10_000 }
        }
        val name = displayName ?: track.uri.lastPathSegment?.substringAfterLast('/')
        TrackDetails(
            durationMs = duration,
            format = formatLabel(mime, name),
            mimeType = mime?.takeIf { it.isNotBlank() },
            bitrateKbps = estimated,
            sampleRateHz = sampleRate,
            sizeBytes = size,
            location = when {
                name == null -> null
                relativePath.isNullOrBlank() -> name
                else -> relativePath.trimEnd('/') + "/" + name
            },
        )
    }

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
