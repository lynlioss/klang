@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.lynli.klang.playback

import android.content.Context
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaRouter2
import android.media.MediaRoute2Info
import android.media.RouteDiscoveryPreference
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import app.lynli.klang.equalizer.EqAudioProcessor
import app.lynli.klang.equalizer.EqStore
import app.lynli.klang.equalizer.EqRuntime
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.lynli.klang.MainActivity
import app.lynli.klang.data.Track
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class PlaybackService : MediaSessionService() {
    private lateinit var eq: EqStore
    private lateinit var router: MediaRouter2
    private lateinit var audioManager: AudioManager
    private var audioTrack: android.media.AudioTrack? = null
    private var destroyed = false
    private val routeCallback = object : MediaRouter2.ControllerCallback() {
        override fun onControllerUpdated(controller: MediaRouter2.RoutingController) { updateEqRoute() }
    }
    private val discoveryCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) { updateEqRoute() }
    }
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val save = object : Runnable {
        override fun run() { saveQueue(); handler.postDelayed(this, 5000) }
    }
    override fun onCreate() {
        super.onCreate()
        eq = EqStore.get(this)
        router = MediaRouter2.getInstance(this)
        audioManager = getSystemService(AudioManager::class.java)
        val processor = EqAudioProcessor(eq)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean): AudioSink {
                val provider = AudioTrackAudioOutputProvider.Builder(context).build()
                val outputProvider = object : ForwardingAudioOutputProvider(provider) {
                    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                        val output = provider.getAudioOutput(config)
                        val track = output.audioTrack
                        fun updateRouting() {
                            if (destroyed || audioTrack !== track) return
                            val devices = track.routedDevices.map { it.outputDevice() }
                            PlaybackOutput.update(devices)
                            updateEqRoute()
                        }
                        val routingListener = AudioRouting.OnRoutingChangedListener { updateRouting() }
                        track.addOnRoutingChangedListener(routingListener, handler)
                        handler.post { if (!destroyed) { audioTrack = track; updateRouting() } }
                        return object : ForwardingAudioOutput(output) {
                            override fun play() { super.play(); handler.post { updateRouting() } }
                            override fun pause() { super.pause(); handler.post { updateRouting() } }
                            override fun stop() { super.stop(); handler.post { updateRouting() } }
                            override fun release() {
                                track.removeOnRoutingChangedListener(routingListener)
                                handler.post {
                                    if (!destroyed && audioTrack === track) {
                                        audioTrack = null
                                        PlaybackOutput.update(emptyList())
                                        updateEqRoute()
                                    }
                                }
                                super.release()
                            }
                        }
                    }
                }
                val sink = DefaultAudioSink.Builder(context).setAudioOutputProvider(outputProvider).setAudioProcessors(arrayOf(processor))
                    .setEnableFloatOutput(false).build()
                return object : ForwardingAudioSink(sink) {
                    // Decode compressed audio even on HDMI/USB devices supporting passthrough.
                    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
                    override fun getFormatSupport(format: Format): Int = if (format.sampleMimeType == MimeTypes.AUDIO_RAW)
                        super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
                    override fun getFormatOffloadSupport(format: Format) = AudioOffloadSupport.DEFAULT_UNSUPPORTED
                }
            }
        }
        player = ExoPlayer.Builder(this, renderers).build().apply {
            // Offload/passthrough would bypass PCM effects; keep all music in the processing chain.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder().setAudioOffloadMode(
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(intent).build()
        restoreQueue()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { saveQueue(); updateEqRoute() }
        })
        router.registerControllerCallback(mainExecutor, routeCallback)
        // Passive discovery registers for route changes without continuous Bluetooth scanning.
        router.registerRouteCallback(mainExecutor, discoveryCallback,
            RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build())
        updateEqRoute()
        handler.postDelayed(save, 5000)
    }
    private fun updateEqRoute() {
        val controller = router.systemController
        val routes = (controller.selectedRoutes + controller.transferableRoutes + router.routes).distinctBy { it.id }
        val devices = currentAudioOutputs(audioManager, PlaybackOutput.devices.value)
        val selection = outputSelection(routes.map { it.outputRoute() }, controller.selectedRoutes.map { it.id }.toSet(), devices)
        val route = routes.firstOrNull { it.id in selection.routeIds }
        val device = devices.firstOrNull()
        val bluetooth = device?.let { isBluetoothOutput(it.type) } ?: (route?.type in setOf(MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
            MediaRoute2Info.TYPE_BLE_HEADSET, MediaRoute2Info.TYPE_HEARING_AID))
        eq.runtime(EqRuntime(player.currentMediaItem?.mediaId,
            if (bluetooth) route?.id ?: device?.let { "audio:${it.type}:${it.address.ifBlank { it.name }}" } else null,
            route?.name?.toString() ?: device?.name ?: "This phone"))
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    private fun saveQueue() {
        val items = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            items.put(JSONObject().put("uri", item.mediaId).put("title", item.mediaMetadata.title)
                .put("artist", item.mediaMetadata.artist).put("album", item.mediaMetadata.albumTitle))
        }
        getSharedPreferences("playback", MODE_PRIVATE).edit().putString("queue", items.toString())
            .putInt("index", player.currentMediaItemIndex.coerceAtLeast(0)).putLong("position", player.currentPosition)
            .putString("source", player.playbackSourceOrNull())
            .putInt("repeat", player.repeatMode).putBoolean("shuffle", player.shuffleModeEnabled).apply()
    }
    private fun restoreQueue() {
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        runCatching {
            val array = JSONArray(prefs.getString("queue", "[]"))
            val source = prefs.getString("source", null)
            val items = (0 until array.length()).map { i -> array.getJSONObject(i).let {
                Track(Uri.parse(it.getString("uri")), it.optString("title"), it.optString("artist"),
                    it.optString("album"), 0).mediaItem().withPlaybackSource(source)
            }}
            if (items.isNotEmpty()) {
                player.setPlaylistMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                    prefs.getString("source", null)?.let { putString(PLAYBACK_SOURCE_KEY, it) }
                }).build())
                player.setMediaItems(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("position", 0))
                player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
                player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
                player.prepare()
            }
        }
    }
    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(save)
        router.unregisterControllerCallback(routeCallback)
        router.unregisterRouteCallback(discoveryCallback)
        saveQueue(); session?.release(); player.release()
        audioTrack = null
        PlaybackOutput.update(emptyList())
        super.onDestroy()
    }
}
