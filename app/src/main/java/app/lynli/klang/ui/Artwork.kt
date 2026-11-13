package app.lynli.klang.ui

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
import androidx.compose.ui.res.painterResource
import app.lynli.klang.R
import app.lynli.klang.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val covers = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}
@Composable
fun Artwork(track: Track?, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, track?.id) {
        value = track?.let { t -> covers.get(t.id) ?: withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, t.uri)
                    retriever.embeddedPicture?.let { bytes ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 900).coerceAtLeast(1)
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                } finally { retriever.release() }
            }.getOrNull()?.also { covers.put(t.id, it) }
        } }
    }
    val bundled = when (track?.title) { "KYOTO", "HESTER - KYOTO" -> R.drawable.kyoto; "Recall" -> R.drawable.recall; "EYES ON US" -> R.drawable.eyes_on_us; else -> null }
    Box(modifier.background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            bundled != null -> Image(painterResource(bundled), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Icon(Icons.Rounded.MusicNote, description, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
