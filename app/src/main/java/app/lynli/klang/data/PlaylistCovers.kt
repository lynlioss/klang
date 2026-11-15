package app.lynli.klang.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-owned copies survive picker grants, restarts, and the original image being moved. */
internal object PlaylistCovers {
    private val lock = Mutex()
    private val images = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val seeds = LruCache<String, Int>(128)
    private fun directory(context: Context) = File(context.filesDir, "playlist-covers").apply { mkdirs() }
    private fun file(context: Context, name: String): File? =
        name.takeIf { it.matches(Regex("[a-f0-9-]+\\.jpg")) }?.let { File(directory(context), it) }

    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val ratio = minOf(1f, 1024f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1),
                (info.size.height * ratio).toInt().coerceAtLeast(1))
        }
        val name = "${UUID.randomUUID()}.jpg"
        val target = File(directory(context), name)
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            name
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally { bitmap.recycle() }
    }

    fun peek(name: String, large: Boolean = false): Bitmap? = images.get("$name:$large")
    fun peekSeed(name: String): Int? = seeds.get(name)

    suspend fun bitmap(context: Context, name: String, large: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = "$name:$large"
            images.get(key)?.let { return@withLock it }
            val path = file(context, name)?.path ?: return@withLock null
            val maxEdge = if (large) 1024 else 256
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / maxEdge).coerceAtLeast(1)
                }
                BitmapFactory.decodeFile(path, options)
            }.getOrNull()?.also { images.put(key, it) }
        }
    }

    suspend fun seed(context: Context, name: String): Int? {
        seeds.get(name)?.let { return it }
        val image = bitmap(context, name) ?: return null
        return withContext(Dispatchers.Default) {
            ArtworkColors.seed(image)?.also { seeds.put(name, it) }
        }
    }

    suspend fun delete(context: Context, name: String?) = withContext(Dispatchers.IO) {
        if (name != null) lock.withLock {
            file(context, name)?.delete()
            images.remove("$name:false"); images.remove("$name:true"); seeds.remove(name)
        }
    }
}
