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
    private val mutex = Mutex()
    private val covers = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val seeds = LruCache<String, Int>(128)
    suspend fun bitmap(context: Context, track: Track): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            covers.get(track.id) ?: runCatching {
                val retriever = MediaMetadataRetriever()
                val bytes = try { retriever.setDataSource(context, track.uri); retriever.embeddedPicture }
                    finally { retriever.release() }
                bytes?.let {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 720).coerceAtLeast(1)
                    }
                    BitmapFactory.decodeByteArray(it, 0, it.size, options)
                }
            }.getOrNull()?.also { covers.put(track.id, it) }
                ?: bundled(track)?.let { BitmapFactory.decodeResource(context.resources, it) }?.also { covers.put(track.id, it) }
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
fun Artwork(track: Track?, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, track?.id) {
        value = track?.let { ArtworkStore.bitmap(context, it) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Rounded.MusicNote, description, tint = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}
