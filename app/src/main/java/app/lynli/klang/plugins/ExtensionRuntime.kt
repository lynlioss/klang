@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.plugins

import android.content.*
import android.net.Uri
import android.os.*
import androidx.media3.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** API 1 extensions run under their own UID. Only declared capabilities cross this bridge.
 * Owned by the playback service, rather than an Activity, for background sessions. */
class ExtensionRuntime(private val context: Context, private val player: Player) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var registry = PluginRegistry(context)
    private val config = PluginStore(context) { registry.manifests }
    private val connections = linkedMapOf<String, Connection>()
    private val root = File(context.cacheDir, "extensions")
    private val audio = mutableMapOf<String, MutableMap<String, File>>()
    private val localAudio = mutableMapOf<String, MutableMap<String, Uri>>()
    private val userActions = mutableMapOf<String, Long>()
    private val receipts = mutableMapOf<String, Deferred<File>>()
    private var beforeExtension: Pair<List<MediaItem>, Pair<Int, Long>>? = null
    private var playbackOwner: String? = null
    init {
        active = this
        // A killed session must never restore or retain received files.
        root.deleteRecursively()
        reload()
        scope.launch { while (isActive) { connections.values.toList().forEach { it.snapshot() }; delay(250) } }
    }
    fun reload() {
        registry = PluginRegistry(context)
        registry.discoverServices()
        val enabled = config.read()
        val entries = registry.entries.filter { it.manifest.extension != null && it.manifest.id in enabled.enabled }
        connections.keys.toList().filter { id -> entries.none { it.manifest.id == id } }.forEach(::disconnect)
        entries.forEach { entry ->
            val existing = connections[entry.manifest.id]
            if (existing == null) Connection(entry).also { connections[entry.manifest.id] = it; it.bind() }
            else existing.configure()
        }
        mutableActions.value = entries.flatMap { entry -> entry.manifest.extension!!.actions.map { entry.manifest.id to it } }
        pendingLink?.let { link -> if (dispatchLink(link)) pendingLink = null }
    }
    private fun disconnect(id: String) {
        connections.remove(id)?.close()
        cleanup(id)
        mutableStates.value = mutableStates.value - id
        if (mutablePanel.value == id) mutablePanel.value = null
    }
    private fun cleanup(id: String) {
        if (playbackOwner == id) {
            player.setPlaybackSpeed(1f)
            player.pause(); player.clearMediaItems()
            beforeExtension?.let { (items, position) ->
                if (items.isNotEmpty()) { player.setMediaItems(items, position.first.coerceIn(items.indices), position.second); player.prepare() }
            }
            beforeExtension = null; playbackOwner = null
        }
        receipts.keys.filter { it.startsWith("$id:") }.forEach { receipts.remove(it)?.cancel() }
        audio.remove(id); localAudio.remove(id); userActions.remove(id)
        val retired = File(root, "removed-${UUID.randomUUID()}")
        val directory = File(root, id)
        if (directory.exists() && directory.renameTo(retired)) scope.launch(Dispatchers.IO) { retired.deleteRecursively() }
    }
    fun action(id: String, action: String, values: JSONObject = JSONObject()) {
        if (action != "control" && action != "link" && action != "open") userActions[id] = SystemClock.elapsedRealtime()
        connections[id]?.send(2, values.put("action", action))
    }
    private fun dispatchLink(link: String): Boolean {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return false
        val entry = connections.values.firstOrNull { c ->
            if (c.remote == null) return@firstOrNull false
            val spec = c.entry.manifest.extension!!
            "links" in spec.capabilities && (uri.scheme == "https" && uri.host in spec.hosts || uri.scheme in spec.schemes)
        } ?: return false
        mutablePanel.value = entry.entry.manifest.id
        action(entry.entry.manifest.id, "link", JSONObject().put("url", link))
        return true
    }
    fun routeControl(operation: String, payload: JSONObject = JSONObject()): Boolean {
        val owner = mutableStates.value.entries.firstOrNull { it.value.optBoolean("interceptControls") } ?: return false
        action(owner.key, "control", payload.put("operation", operation))
        return true
    }
    fun audioUri(trackId: String): Uri? = audio.values.firstNotNullOfOrNull { it[trackId]?.let(Uri::fromFile) }
        ?: localAudio.values.firstNotNullOfOrNull { it[trackId] }
    val ownsPlayback: Boolean get() = playbackOwner != null
    fun close() {
        connections.keys.toList().forEach(::disconnect)
        scope.cancel(); active = null; root.deleteRecursively()
        mutableStates.value = emptyMap(); mutableActions.value = emptyList(); mutablePanel.value = null
    }
    private inner class Connection(val entry: InstalledPlugin) : ServiceConnection {
        val id = entry.manifest.id
        val spec = entry.manifest.extension!!
        val token = UUID.randomUUID().toString()
        var remote: Messenger? = null
        var bound = false
        var uid = -1
        private var lastSnapshot = ""
        private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.sendingUid != uid || message.data.getString("token") != token) return
                runCatching {
                    val source = message.data.getString("json").orEmpty()
                    require(source.length <= 64 * 1024)
                    val json = JSONObject(source)
                    when (message.what) {
                        10 -> {
                            require("ui.panel".lowercase() in spec.capabilities)
                            mutableStates.value = mutableStates.value + (id to json)
                        }
                        11 -> scope.launch {
                            try { request(json) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (e: Exception) { send(12, JSONObject().put("requestId", json.optString("requestId")).put("error", e.message ?: "Extension request failed")) }
                        }
                    }
                }
            }
        })
        fun bind() {
            uid = runCatching { context.packageManager.getApplicationInfo(spec.packageName, 0).uid }.getOrDefault(-1)
            bound = context.bindService(Intent(EXTENSION_ACTION).setComponent(ComponentName(spec.packageName, spec.service)), this, Context.BIND_AUTO_CREATE)
        }
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = Messenger(binder)
            configure(); snapshot()
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; cleanup(id); mutableStates.value = mutableStates.value - id }
        override fun onBindingDied(name: ComponentName) { disconnect(id) }
        override fun onNullBinding(name: ComponentName) { disconnect(id) }
        fun configure() {
            val settings = JSONObject()
            val configuration = config.read()
            entry.manifest.settings.forEach { settings.put(it.id, configuration.value(entry.manifest, it)) }
            send(1, JSONObject().put("apiVersion", 1).put("settings", settings))
            pendingLink?.let { link -> if (dispatchLink(link)) pendingLink = null }
        }
        fun send(what: Int, json: JSONObject, fd: ParcelFileDescriptor? = null) {
            val endpoint = remote ?: return
            runCatching { endpoint.send(Message.obtain(null, what).apply {
                replyTo = reply
                data = Bundle().apply { putString("token", token); putString("json", json.toString()); fd?.let { putParcelable("audio", it) } }
            }) }
        }
        fun snapshot() {
            if ("playback.observe".lowercase() !in spec.capabilities) return
            fun metadata(item: MediaItem): JSONObject = JSONObject().put("id", item.mediaId)
                .put("title", item.mediaMetadata.title?.toString().orEmpty()).put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                .put("album", item.mediaMetadata.albumTitle?.toString().orEmpty())
            val json = JSONObject().put("positionMs", player.currentPosition.coerceAtLeast(0)).put("durationMs", player.duration.coerceAtLeast(0))
                .put("playing", player.playWhenReady).put("ready", player.playbackState == Player.STATE_READY)
                .put("elapsedMs", SystemClock.elapsedRealtime()).put("shuffle", player.shuffleModeEnabled).put("repeat", player.repeatMode)
            player.currentMediaItem?.let { json.put("track", metadata(it)) }
            if (player.nextMediaItemIndex in 0 until player.mediaItemCount) json.put("next", metadata(player.getMediaItemAt(player.nextMediaItemIndex)))
            val text = json.toString()
            if (text != lastSnapshot) { send(3, json); lastSnapshot = text }
        }
        suspend fun request(json: JSONObject) {
            val requestId = json.getString("requestId").also { require(it.length <= 100) }
            val result = JSONObject().put("requestId", requestId)
            fun capability(name: String) { require(name.lowercase() in spec.capabilities) { "Capability $name was not declared" } }
            when (json.getString("operation")) {
                "audio.find" -> {
                    capability("audio.receive")
                    val hash = json.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    val match = withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val collection = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        val candidates = mutableListOf<Uri>()
                        runCatching {
                            resolver.query(collection, arrayOf("_id", "_size"), "title = ? AND artist = ?",
                                arrayOf(json.optString("title"), json.optString("artist")), null)?.use { cursor ->
                                while (cursor.moveToNext() && candidates.size < 3) {
                                    if (cursor.getLong(1) in 1..MAX_AUDIO_BYTES) candidates += ContentUris.withAppendedId(collection, cursor.getLong(0))
                                }
                            }
                        }
                        candidates.firstOrNull { uri ->
                            runCatching {
                                val digest = MessageDigest.getInstance("SHA-256")
                                resolver.openInputStream(uri)?.use { input ->
                                    val buffer = ByteArray(64 * 1024); var total = 0L
                                    while (true) {
                                        ensureActive(); val count = input.read(buffer); if (count < 0) break
                                        total += count; require(total <= MAX_AUDIO_BYTES); digest.update(buffer, 0, count)
                                    }
                                } ?: return@runCatching false
                                digest.digest().joinToString("") { "%02x".format(it) } == hash
                            }.getOrDefault(false)
                        }
                    }
                    if (match != null) localAudio.getOrPut(id) { mutableMapOf() }[hash] = match
                    send(12, result.put("found", match != null))
                }
                "audio.read" -> {
                    capability("audio.read")
                    val trackId = json.getString("trackId")
                    // No arbitrary URI/file reads: a plugin may only share the current or next item.
                    val allowed = listOfNotNull(player.currentMediaItem, player.nextMediaItemIndex.takeIf { it in 0 until player.mediaItemCount }?.let(player::getMediaItemAt))
                    val item = allowed.firstOrNull { it.mediaId == trackId } ?: error("Audio is outside the playback queue")
                    val uri = item.localConfiguration?.uri ?: Uri.parse(trackId)
                    require(uri.scheme == "content" && (uri.authority == "media" || context.contentResolver.getType(uri)?.startsWith("audio/") == true) ||
                        uri.scheme == "file" && audio[id]?.values?.any { Uri.fromFile(it) == uri } == true)
                    withContext(Dispatchers.IO) {
                        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
                        descriptor.use { send(12, result, it) }
                    }
                }
                "audio.receive" -> {
                    capability("audio.receive")
                    val hash = json.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    val size = json.getLong("bytes").also { require(it in 1..MAX_AUDIO_BYTES) }
                    require(receipts.size < 6 && audio.values.flatMap { it.values }.sumOf { it.length() } + size <= 512 * 1024 * 1024L)
                    val key = "$id:$hash"
                    val pipe = ParcelFileDescriptor.createReliablePipe()
                    val directory = File(root, id).apply { mkdirs() }
                    val output = File(directory, "$hash.part")
                    require(!receipts.containsKey(key))
                    val watchdog = scope.launch { delay(120_000); runCatching { pipe[0].close() } }
                    receipts[key] = scope.async(Dispatchers.IO) {
                        try {
                            withTimeout(120_000) {
                                val digest = MessageDigest.getInstance("SHA-256")
                                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input -> output.outputStream().use { stream ->
                                    var total = 0L; val buffer = ByteArray(16 * 1024)
                                    while (true) {
                                        ensureActive()
                                        val count = input.read(buffer); if (count < 0) break
                                        total += count; require(total <= size); digest.update(buffer, 0, count); stream.write(buffer, 0, count)
                                    }
                                    require(total == size && digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Audio checksum mismatch" }
                                } }
                                validateAudio(output)
                                val final = File(directory, "$hash.audio")
                                require(output.renameTo(final)); final
                            }
                        } finally { watchdog.cancel(); output.delete(); runCatching { pipe[0].close() } }
                    }
                    pipe[1].use { send(12, result, it) }
                }
                "audio.commit" -> {
                    capability("audio.receive")
                    val hash = json.getString("sha256")
                    val file = withTimeout(120_000) { requireNotNull(receipts.remove("$id:$hash")).await() }
                    audio.getOrPut(id) { mutableMapOf() }[hash] = file
                    send(12, result.put("trackId", hash))
                }
                "audio.save" -> {
                    capability("audio.receive")
                    require(SystemClock.elapsedRealtime() - (userActions.remove(id) ?: -60_000) < 30_000) { "Saving audio requires a user action" }
                    val file = requireNotNull(audio[id]?.get(json.getString("sha256")))
                    withContext(Dispatchers.IO) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "${json.optString("title", "Jam track").replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(100)}.${validateAudio(file)}")
                            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, when (validateAudio(file)) { "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; else -> "audio/ogg" })
                            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/Klang")
                            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
                        try {
                            requireNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
                            values.clear(); values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
                            result.put("uri", uri.toString())
                        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                    }
                    send(12, result)
                }
                "audio.release" -> {
                    capability("audio.receive")
                    val keep = json.optJSONArray("keep")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
                    audio[id]?.keys?.toList()?.filter { hash -> hash !in keep && hash != player.currentMediaItem?.mediaId }?.forEach { hash ->
                        audio[id]?.remove(hash)?.let { file -> scope.launch(Dispatchers.IO) { file.delete() } }
                    }
                    localAudio[id]?.keys?.toList()?.filter { it !in keep && it != player.currentMediaItem?.mediaId }?.forEach { localAudio[id]?.remove(it) }
                    send(12, result)
                }
                "playback.apply" -> {
                    capability("playback.control")
                    require(playbackOwner == null || playbackOwner == id) { "Another extension owns playback" }
                    val hash = json.optString("sha256")
                    if (hash.isNotBlank()) {
                        val file = audio[id]?.get(hash)
                        val uri = file?.let(Uri::fromFile) ?: localAudio[id]?.get(hash) ?: error("Audio is not ready")
                        if (playbackOwner == null) {
                            beforeExtension = (0 until player.mediaItemCount).map(player::getMediaItemAt) to (player.currentMediaItemIndex to player.currentPosition)
                            playbackOwner = id
                        }
                        if (player.currentMediaItem?.mediaId != hash) {
                            player.setMediaItem(MediaItem.Builder().setMediaId(hash).setUri(uri).setMediaMetadata(
                                MediaMetadata.Builder().setTitle(json.optString("title")).setArtist(json.optString("artist")).setAlbumTitle(json.optString("album")).build()).build())
                            player.prepare()
                        }
                    }
                    json.optLong("positionMs", -1).takeIf { it >= 0 }?.let(player::seekTo)
                    if (json.has("speed")) player.setPlaybackSpeed(json.getDouble("speed").toFloat().coerceIn(0.97f, 1.03f))
                    if (json.has("playing")) player.playWhenReady = json.getBoolean("playing")
                    send(12, result.put("positionMs", player.currentPosition).put("elapsedMs", SystemClock.elapsedRealtime()).put("playing", player.playWhenReady)); snapshot()
                }
                "playback.command" -> {
                    capability("playback.control")
                    when (json.getString("command")) {
                        "play" -> { player.prepare(); player.play() }; "pause" -> player.pause()
                        "next" -> player.seekToNextMediaItem(); "previous" -> player.seekToPreviousMediaItem()
                        "seek" -> player.seekTo(json.getLong("positionMs").coerceAtLeast(0))
                        "shuffle" -> player.shuffleModeEnabled = json.getBoolean("enabled")
                        "repeat" -> player.repeatMode = json.getInt("mode").also { require(it in 0..2) }
                        else -> error("Unsupported playback command")
                    }
                    send(12, result.put("positionMs", player.currentPosition).put("elapsedMs", SystemClock.elapsedRealtime()).put("playing", player.playWhenReady)); snapshot()
                }
                "session.clear" -> { cleanup(id); send(12, result) }
                else -> error("Unsupported extension operation")
            }
        }
        fun close() { send(4, JSONObject()); remote = null; if (bound) runCatching { context.unbindService(this) } }
    }
    companion object {
        const val MAX_AUDIO_BYTES = 256 * 1024 * 1024L
        var active: ExtensionRuntime? = null; private set
        private var pendingLink: String? = null
        private val mutableStates = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
        val states = mutableStates.asStateFlow()
        private val mutableActions = MutableStateFlow<List<Pair<String, ExtensionAction>>>(emptyList())
        val actions = mutableActions.asStateFlow()
        private val mutablePanel = MutableStateFlow<String?>(null)
        val panel = mutablePanel.asStateFlow()
        fun open(id: String) { mutablePanel.value = id; active?.action(id, "open") }
        fun dismiss() { mutablePanel.value = null }
        fun openLink(link: String) { if (active?.dispatchLink(link) != true) pendingLink = link }
        private fun audioMime(file: File): String {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                require(extractor.trackCount in 1..8)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormatKey) ?: "" }
                require(formats.all { it.startsWith("audio/") }) { "Only audio files are accepted" }
                return formats.first()
            } finally { extractor.release() }
        }
        private const val MediaFormatKey = "mime"
        private fun validateAudio(file: File): String {
            val header = file.inputStream().use { it.readNBytes(32) }
            require(header.size >= 12)
            val ascii = header.toString(Charsets.ISO_8859_1)
            val extension = when {
                ascii.startsWith("ID3") || (header[0].toInt() and 255) == 255 && (header[1].toInt() and 224) == 224 -> "mp3"
                ascii.startsWith("fLaC") -> "flac"
                ascii.startsWith("OggS") -> "ogg"
                ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WAVE" -> "wav"
                ascii.substring(4, 8) == "ftyp" -> "m4a"
                else -> error("Unsupported audio container")
            }
            audioMime(file)
            return extension
        }
    }
}

/** All native controls, including media notifications, obey an extension's control lease. */
class ExtensionPlayer(player: Player, private val runtime: ExtensionRuntime) : ForwardingPlayer(player) {
    override fun play() { if (!runtime.routeControl("play")) super.play() }
    override fun pause() { if (!runtime.routeControl("pause")) super.pause() }
    override fun setPlayWhenReady(playWhenReady: Boolean) { if (!runtime.routeControl(if (playWhenReady) "play" else "pause")) super.setPlayWhenReady(playWhenReady) }
    override fun seekTo(positionMs: Long) { if (!runtime.routeControl("seek", JSONObject().put("positionMs", positionMs))) super.seekTo(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { if (!runtime.routeControl("seek", JSONObject().put("positionMs", positionMs))) super.seekTo(mediaItemIndex, positionMs) }
    override fun seekToNextMediaItem() { if (!runtime.routeControl("next")) super.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() { if (!runtime.routeControl("previous")) super.seekToPreviousMediaItem() }
    override fun seekToNext() { if (!runtime.routeControl("next")) super.seekToNext() }
    override fun seekToPrevious() { if (!runtime.routeControl("previous")) super.seekToPrevious() }
    override fun seekToDefaultPosition() { if (!runtime.routeControl("seek", JSONObject().put("positionMs", 0))) super.seekToDefaultPosition() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { if (!runtime.routeControl("next")) super.seekToDefaultPosition(mediaItemIndex) }
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) { if (!runtime.routeControl("shuffle", JSONObject().put("enabled", shuffleModeEnabled))) super.setShuffleModeEnabled(shuffleModeEnabled) }
    override fun setRepeatMode(repeatMode: Int) { if (!runtime.routeControl("repeat", JSONObject().put("mode", repeatMode))) super.setRepeatMode(repeatMode) }
    override fun setMediaItems(mediaItems: List<MediaItem>) { if (!runtime.routeControl("queue")) super.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) { if (!runtime.routeControl("queue")) super.setMediaItems(mediaItems, resetPosition) }
    override fun setMediaItem(mediaItem: MediaItem) { if (!runtime.routeControl("queue")) super.setMediaItem(mediaItem) }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) { if (!runtime.routeControl("queue")) super.setMediaItem(mediaItem, startPositionMs) }
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) { if (!runtime.routeControl("queue")) super.setMediaItem(mediaItem, resetPosition) }
    override fun addMediaItem(mediaItem: MediaItem) { if (!runtime.routeControl("queue")) super.addMediaItem(mediaItem) }
    override fun addMediaItem(index: Int, mediaItem: MediaItem) { if (!runtime.routeControl("queue")) super.addMediaItem(index, mediaItem) }
    override fun addMediaItems(mediaItems: List<MediaItem>) { if (!runtime.routeControl("queue")) super.addMediaItems(mediaItems) }
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) { if (!runtime.routeControl("queue")) super.moveMediaItem(currentIndex, newIndex) }
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) { if (!runtime.routeControl("queue")) super.moveMediaItems(fromIndex, toIndex, newIndex) }
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) { if (!runtime.routeControl("queue")) super.replaceMediaItem(index, mediaItem) }
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) { if (!runtime.routeControl("queue")) super.replaceMediaItems(fromIndex, toIndex, mediaItems) }
    override fun removeMediaItem(index: Int) { if (!runtime.routeControl("queue")) super.removeMediaItem(index) }
    override fun clearMediaItems() { if (!runtime.routeControl("queue")) super.clearMediaItems() }
    override fun seekBack() { if (!runtime.routeControl("seek", JSONObject().put("positionMs", (currentPosition - seekBackIncrement).coerceAtLeast(0)))) super.seekBack() }
    override fun seekForward() { if (!runtime.routeControl("seek", JSONObject().put("positionMs", currentPosition + seekForwardIncrement))) super.seekForward() }
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) { if (!runtime.routeControl("queue")) super.setMediaItems(mediaItems, startIndex, startPositionMs) }
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) { if (!runtime.routeControl("queue")) super.addMediaItems(index, mediaItems) }
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) { if (!runtime.routeControl("queue")) super.removeMediaItems(fromIndex, toIndex) }
}
