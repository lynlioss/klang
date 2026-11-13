package app.lynli.klang.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.palette.graphics.Palette
import app.lynli.klang.R
import app.lynli.klang.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ArtworkStore {
    private val locks = Array(8) { Mutex() }
    private val covers = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val fullCovers = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val seeds = LruCache<String, Int>(512)
    private val lastCleanup = java.util.concurrent.atomic.AtomicLong(0)
    private val missing = LruCache<String, Boolean>(512)
    fun peek(track: Track?, large: Boolean = false) = track?.let {
        (if (large) fullCovers.get(it.id) else null) ?: covers.get(it.id)
    }
    fun peekSeed(track: Track) = seeds.get(track.id)
    private fun key(track: Track): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(track.id.toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun bitmap(context: Context, track: Track, large: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        locks[(track.id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val cache = if (large) fullCovers else covers
            val maxEdge = if (large) 1024 else 256
            cache.get(track.id)?.let { return@withLock it }
            if (missing.get(track.id) == true) return@withLock null
            val dir = java.io.File(context.cacheDir, "artwork-v2").apply { mkdirs() }
            val file = java.io.File(dir, key(track) + (if (large) "-large.jpg" else ".jpg"))
            val cached = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            fun keepThumbnail(image: Bitmap) {
                if (large && covers.get(track.id) == null) {
                    val ratio = minOf(1f, 256f / maxOf(image.width, image.height))
                    covers.put(track.id, Bitmap.createScaledBitmap(image, (image.width * ratio).toInt().coerceAtLeast(1),
                        (image.height * ratio).toInt().coerceAtLeast(1), true))
                }
            }
            cached?.let { keepThumbnail(it); cache.put(track.id, it); return@withLock it }
            fun scale(image: Bitmap): Bitmap {
                val longest = maxOf(image.width, image.height)
                if (longest <= maxEdge) return image
                val ratio = maxEdge.toFloat() / longest
                return Bitmap.createScaledBitmap(image, (image.width * ratio).toInt().coerceAtLeast(1),
                    (image.height * ratio).toInt().coerceAtLeast(1), true)
            }
            val decoded = runCatching {
                val retriever = MediaMetadataRetriever()
                val bytes = try { retriever.setDataSource(context, track.uri); retriever.embeddedPicture }
                    finally { retriever.release() }
                bytes?.let {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / maxEdge).coerceAtLeast(1)
                    }
                    BitmapFactory.decodeByteArray(it, 0, it.size, options)
                }
            }.getOrNull()?.let(::scale)?.also { cache.put(track.id, it) }
                ?: bundled(track)?.let { BitmapFactory.decodeResource(context.resources, it) }?.let(::scale)?.also { cache.put(track.id, it) }
            decoded?.let(::keepThumbnail)
            if (decoded == null) missing.put(track.id, true)
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
        }
    }
    suspend fun seed(context: Context, track: Track): Int? {
        seeds.get(track.id)?.let { return it }
        val bitmap = bitmap(context, track) ?: return null
        return withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).maximumColorCount(12).resizeBitmapArea(4096).generate()
            val value = palette.vibrantSwatch?.rgb ?: palette.darkVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb ?: palette.dominantSwatch?.rgb
            value?.also { seeds.put(track.id, it) }
        }
    }
    private fun bundled(track: Track) = when (track.title) {
        "KYOTO", "HESTER - KYOTO" -> R.drawable.kyoto
        "Recall" -> R.drawable.recall
        "EYES ON US" -> R.drawable.eyes_on_us
        else -> null
    }
}

@Composable
fun Artwork(track: Track?, modifier: Modifier = Modifier, description: String? = null, large: Boolean = false) {
    val context = LocalContext.current
    // A disposed lazy item starts with its existing image, without a placeholder frame.
    val bitmap = remember(track?.id, large) { mutableStateOf(ArtworkStore.peek(track, large)) }
    LaunchedEffect(track?.id, large) { bitmap.value = track?.let { ArtworkStore.bitmap(context, it, large) } }
    Box(modifier.background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        bitmap.value?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.MusicNote, description, tint = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}
