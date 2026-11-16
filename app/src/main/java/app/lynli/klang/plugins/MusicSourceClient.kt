package app.lynli.klang.plugins

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** API 1 source RPC. Each request is bound to the installed service UID and a random token. */
internal object MusicSourceClient {
    const val CALL = 20
    suspend fun call(context: Context, plugin: InstalledPlugin, operation: String,
        args: JSONObject = JSONObject()): JSONObject = coroutineScope {
        val spec = requireNotNull(plugin.manifest.musicSource)
        val uid = context.packageManager.getApplicationInfo(spec.packageName, 0).uid
        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<JSONObject>()
        require(operation.length in 1..100 && args.toString().length <= 380_000) { "Source request is too large" }
        var remote: Messenger? = null
        var pipe: ParcelFileDescriptor? = null
        var reading = false
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                val incoming = message.data.getParcelable("metadata", ParcelFileDescriptor::class.java)
                if (message.sendingUid != uid || message.data.getString("token") != token || result.isCompleted || reading) {
                    incoming?.close(); return
                }
                when (message.what) {
                    12 -> result.completeExceptionally(IllegalStateException(message.data.getString("json")?.take(500) ?: "Source request failed"))
                    10 -> if (incoming != null) {
                        reading = true
                        pipe = incoming
                        launch(Dispatchers.IO) {
                            try {
                                val bytes = ParcelFileDescriptor.AutoCloseInputStream(incoming).use { it.readNBytes(16 * 1024 * 1024 + 1) }
                                require(bytes.size <= 16 * 1024 * 1024) { "Source returned too much metadata" }
                                result.complete(JSONObject(bytes.toString(Charsets.UTF_8)))
                            } catch (e: Exception) { result.completeExceptionally(e) }
                        }
                    } else try { result.complete(JSONObject(message.data.getString("json").orEmpty())) }
                        catch (e: Exception) { result.completeExceptionally(e) }
                    else -> incoming?.close()
                }
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    remote = Messenger(binder)
                    remote!!.send(Message.obtain(null, CALL).apply { replyTo = reply; data = Bundle().apply {
                        putString("token", token); putString("json", JSONObject().put("operation", operation).put("args", args).toString())
                    } })
                } catch (e: Exception) { result.completeExceptionally(e) }
            }
            override fun onServiceDisconnected(name: ComponentName) { result.completeExceptionally(IllegalStateException("The music source disconnected. Retry.")) }
            override fun onBindingDied(name: ComponentName) { result.completeExceptionally(IllegalStateException("The music source was updated. Retry.")) }
            override fun onNullBinding(name: ComponentName) { result.completeExceptionally(IllegalStateException("The music source is unavailable.")) }
        }
        var bound = false
        try {
            bound = context.bindService(Intent(MUSIC_SOURCE_ACTION).setComponent(ComponentName(spec.packageName, spec.service)), connection, Context.BIND_AUTO_CREATE)
            require(bound) { "Install and enable this music source in Plugins." }
            withTimeout(if (operation == "stream.resolve") 330_000L else 90_000L) { result.await() }
        } finally {
            if (!result.isCompleted || result.isCancelled) runCatching { remote?.send(Message.obtain(null, 3).apply {
                replyTo = reply; data = Bundle().apply { putString("token", token) }
            }) }
            pipe?.close()
            if (bound) runCatching { context.unbindService(connection) }
        }
    }
}
