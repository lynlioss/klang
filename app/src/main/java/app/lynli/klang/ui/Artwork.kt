package app.lynli.klang.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.lynli.klang.data.ArtworkColors
import app.lynli.klang.data.Track
import app.lynli.klang.data.PlaylistCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ArtworkStore {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val covers = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val fullCovers = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val seeds = LruCache<String, Int>(512)
    private val lastCleanup = java.util.concurrent.atomic.AtomicLong(0)
    private val missing = LruCache<String, Long>(512)
    private fun identity(track: Track) = track.artworkUrl ?: track.localUri?.toString() ?: track.id
    fun peek(track: Track?, large: Boolean = false) = track?.let {
        (if (large) fullCovers.get(identity(it)) else null) ?: covers.get(identity(it)) ?: covers.get(it.id)
    }
    fun peekSeed(track: Track) = seeds.get(track.id)
    private fun key(track: Track): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(track.id.toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun bitmap(context: Context, track: Track, large: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val identity = identity(track)
        val lock = locks.getOrPut(identity) { Mutex() }
        lock.withLock {
            val cache = if (large) fullCovers else covers
            val maxEdge = if (large) 1024 else 256
            cache.get(identity)?.let { return@withLock it }
            missing.get(identity)?.takeIf { android.os.SystemClock.elapsedRealtime() - it < 30_000 }?.let { return@withLock null }
            val dir = java.io.File(context.cacheDir, "artwork-v2").apply { mkdirs() }
            val artworkKey = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            val suffix = if (large) "-large.jpg" else ".jpg"
            val file = java.io.File(dir, artworkKey + suffix)
            val legacy = java.io.File(dir, key(track) + suffix)
            val cached = if (file.exists()) BitmapFactory.decodeFile(file.path) else if (legacy.exists()) BitmapFactory.decodeFile(legacy.path) else null
            fun keepThumbnail(image: Bitmap) {
                if (large && covers.get(identity) == null) {
                    val ratio = minOf(1f, 256f / maxOf(image.width, image.height))
                    covers.put(identity, Bitmap.createScaledBitmap(image, (image.width * ratio).toInt().coerceAtLeast(1),
                        (image.height * ratio).toInt().coerceAtLeast(1), true))
                }
            }
            cached?.let { keepThumbnail(it); cache.put(identity, it); return@withLock it }
            fun scale(image: Bitmap): Bitmap {
                val longest = maxOf(image.width, image.height)
                if (longest <= maxEdge) return image
                val ratio = maxEdge.toFloat() / longest
                return Bitmap.createScaledBitmap(image, (image.width * ratio).toInt().coerceAtLeast(1),
                    (image.height * ratio).toInt().coerceAtLeast(1), true)
            }
            val decoded = runCatching {
                val retriever = MediaMetadataRetriever()
                val bytes = try {
                    if (track.artworkUrl != null) {
                        val connection = java.net.URI(track.artworkUrl).toURL().openConnection() as java.net.HttpURLConnection
                        try {
                            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                            connection.instanceFollowRedirects = false
                            require(connection.responseCode in 200..299)
                            connection.inputStream.use { input -> input.readNBytes(8 * 1024 * 1024 + 1).also { require(it.size <= 8 * 1024 * 1024) } }
                        } finally { connection.disconnect() }
                    } else { retriever.setDataSource(context, track.localUri ?: track.uri); retriever.embeddedPicture }
                }
                    finally { retriever.release() }
                bytes?.let {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / maxEdge).coerceAtLeast(1)
                    }
                    BitmapFactory.decodeByteArray(it, 0, it.size, options)
                }
            }.getOrNull()?.let(::scale)?.also { cache.put(identity, it) }
            decoded?.let(::keepThumbnail)
            if (decoded == null) missing.put(identity, android.os.SystemClock.elapsedRealtime())
            else runCatching {
                val temp = java.io.File(dir, file.name + ".tmp")
                temp.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                temp.renameTo(file)
                val now = android.os.SystemClock.elapsedRealtime()
                val previous = lastCleanup.get()
                if (now - previous > 30_000 && lastCleanup.compareAndSet(previous, now)) {
                    val files = dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.lastModified() }
                    var total = files.sumOf { it.length() }
                    for (old in files) {
                        if (total <= 128L * 1024 * 1024) break
                        total -= old.length(); old.delete()
                    }
                }
            }
            decoded
        }.also { if (locks.size > 2048) locks.entries.removeIf { !it.value.isLocked } }
    }
    suspend fun seed(context: Context, track: Track): Int? {
        seeds.get(track.id)?.let { return it }
        val bitmap = bitmap(context, track) ?: return null
        return withContext(Dispatchers.Default) {
            ArtworkColors.seed(bitmap)?.also { seeds.put(track.id, it) }
        }
    }
}

@Composable
fun Artwork(track: Track?, modifier: Modifier = Modifier, description: String? = null, large: Boolean = false) {
    val context = LocalContext.current
    // A disposed lazy item starts with its existing image, without a placeholder frame.
    val bitmap = remember(track?.id, track?.artworkUrl, large) { mutableStateOf(ArtworkStore.peek(track, large)) }
    LaunchedEffect(track?.id, track?.artworkUrl, large) { bitmap.value = track?.let { ArtworkStore.bitmap(context, it, large) } }
    Box(modifier.background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        bitmap.value?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.MusicNote, description, tint = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
internal fun PlaylistArtwork(coverFile: String?, track: Track?, modifier: Modifier = Modifier,
    description: String? = null, large: Boolean = false) {
    if (coverFile == null) {
        Artwork(track, modifier, description, large)
        return
    }
    val context = LocalContext.current
    var bitmap by remember(coverFile, large) { mutableStateOf(PlaylistCovers.peek(coverFile, large)) }
    LaunchedEffect(coverFile, large) { bitmap = PlaylistCovers.bitmap(context, coverFile, large) }
    if (bitmap == null) Artwork(track, modifier, description, large)
    else Box(modifier) {
        Image(bitmap!!.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun PickedCover(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio = minOf(1f, 256f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}
