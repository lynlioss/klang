package app.lynli.klang.importing

import android.content.*
import android.os.*
import app.lynli.klang.plugins.InstalledPlugin
import app.lynli.klang.plugins.MUSIC_IMPORT_ACTION
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Binder messages carry commands; a file descriptor streams large previews or downloaded files.
 * Provider execution stays in a separate Android UID. No downloaded classes run inside Klang.
 */
internal object ImportProviderClient {
    private val cancellations = ConcurrentHashMap<String, () -> Unit>()
    fun stop(id: String) { cancellations[id]?.invoke() }

    suspend fun resolve(context: Context, provider: InstalledPlugin, link: ImportLink, id: String): ImportPreview = withContext(Dispatchers.IO) {
        val json = request(context, provider, RESOLVE, link.url, id)
        val songs = json.optJSONArray("songs")?.objects().orEmpty().map(ImportCodec::readSong)
        require(songs.isNotEmpty() && songs.size <= 10_000) { "No accessible songs found." }
        ImportPreview(link, provider.manifest.id, json.optString("title", "Music"), songs,
            json.optBoolean("collection"), json.stringOrNull("artwork"), json.stringOrNull("notice"))
    }
    suspend fun download(context: Context, provider: InstalledPlugin, song: ImportSong, file: File, id: String,
        progress: (JSONObject) -> Unit): JSONObject = request(context, provider, DOWNLOAD, song.payload, id, file, progress)

    private suspend fun request(context: Context, provider: InstalledPlugin, command: Int, payload: String, id: String,
        output: File? = null, progress: (JSONObject) -> Unit = {}): JSONObject = coroutineScope {
        val result = CompletableDeferred<JSONObject>()
        val token = UUID.randomUUID().toString()
        val spec = requireNotNull(provider.manifest.musicImport) { "Enable a music download plugin." }
        val intent = Intent(MUSIC_IMPORT_ACTION).setComponent(ComponentName(spec.packageName, spec.service))
        var remote: Messenger? = null
        var descriptor: ParcelFileDescriptor? = null
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.data.getString("token") != token || result.isCompleted) return
                try {
                    when(message.what) {
                        PROGRESS -> {
                            val state = JSONObject(message.data.getString("json").orEmpty())
                            launch(Dispatchers.IO) { progress(state) }
                        }
                        FAILURE -> result.completeExceptionally(IllegalStateException(message.data.getString("json") ?: "Download failed"))
                        RESULT -> {
                            val pipe = message.data.getParcelable("metadata", ParcelFileDescriptor::class.java)
                            if (pipe != null) launch(Dispatchers.IO) {
                                val cleanup = result.invokeOnCompletion { runCatching { pipe.close() } }
                                try {
                                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readNBytes(16 * 1024 * 1024 + 1) }
                                    require(bytes.size <= 16 * 1024 * 1024) { "Plugin returned too much metadata" }
                                    result.complete(JSONObject(bytes.toString(Charsets.UTF_8)))
                                } catch (error: Exception) { result.completeExceptionally(error) }
                                finally { cleanup.dispose() }
                            } else result.complete(JSONObject(message.data.getString("json").orEmpty()))
                        }
                    }
                } catch (error: Exception) { result.completeExceptionally(error) }
            }
        })
        fun cancel() {
            runCatching { remote?.send(Message.obtain(null, CANCEL).apply { replyTo = reply; data = Bundle().apply { putString("token", token) } }) }
            result.cancel()
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    remote = Messenger(binder)
                    if (output != null) descriptor = ParcelFileDescriptor.open(output,
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                    remote!!.send(Message.obtain(null, command).apply {
                        replyTo = reply; data = Bundle().apply {
                            putString("token", token); putString("json", payload); descriptor?.let { putParcelable("output", it) }
                        }
                    })
                    descriptor?.close(); descriptor = null
                } catch (error: Exception) { result.completeExceptionally(error) }
            }
            override fun onServiceDisconnected(name: ComponentName) { result.completeExceptionally(IllegalStateException("The download plugin stopped. Retry this song.")) }
            override fun onNullBinding(name: ComponentName) { result.completeExceptionally(IllegalStateException("The plugin service is unavailable.")) }
            override fun onBindingDied(name: ComponentName) { result.completeExceptionally(IllegalStateException("The plugin was updated. Retry this song.")) }
        }
        var bound = false
        try {
            cancellations[id] = ::cancel
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            require(bound) { "Install and enable the music download plugin in Settings → Plugins." }
            withTimeout(if (command == RESOLVE) 120_000L else 20 * 60_000L) { result.await() }
        } finally {
            cancellations.remove(id)
            if (!result.isCompleted || result.isCancelled) cancel()
            descriptor?.close()
            if (bound) runCatching { context.unbindService(connection) }
        }
    }
    private const val RESOLVE = 1
    private const val DOWNLOAD = 2
    private const val CANCEL = 3
    private const val RESULT = 10
    private const val PROGRESS = 11
    private const val FAILURE = 12
}
