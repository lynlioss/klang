package app.lynli.klang.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val save = object : Runnable {
        override fun run() { saveQueue(); handler.postDelayed(this, 5000) }
    }
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
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
            override fun onEvents(player: Player, events: Player.Events) { saveQueue() }
        })
        handler.postDelayed(save, 5000)
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
            .putInt("repeat", player.repeatMode).putBoolean("shuffle", player.shuffleModeEnabled).apply()
    }
    private fun restoreQueue() {
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        runCatching {
            val array = JSONArray(prefs.getString("queue", "[]"))
            val items = (0 until array.length()).map { i -> array.getJSONObject(i).let {
                Track(Uri.parse(it.getString("uri")), it.optString("title"), it.optString("artist"),
                    it.optString("album"), 0).mediaItem()
            }}
            if (items.isNotEmpty()) {
                player.setMediaItems(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("position", 0))
                player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
                player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
                player.prepare()
            }
        }
    }
    override fun onDestroy() {
        handler.removeCallbacks(save)
        saveQueue(); session?.release(); player.release()
        super.onDestroy()
    }
}
