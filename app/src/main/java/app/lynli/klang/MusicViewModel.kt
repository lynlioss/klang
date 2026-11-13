package app.lynli.klang

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import app.lynli.klang.data.MusicRepository
import app.lynli.klang.data.Track
import app.lynli.klang.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MusicState(
    val tracks: List<Track> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val permission: Boolean = false,
    val current: Track? = null, val playing: Boolean = false, val connected: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val queue: List<Track> = emptyList(),
    val queueIndex: Int = 0, val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val favorites: Set<String> = emptySet(), val message: String? = null,
    val coverColor: Boolean = false, val dynamicColor: Boolean = false, val theme: String = "system", val sleepMinutes: Int = 0,
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application)
    private val prefs = application.getSharedPreferences("klang", 0)
    private val mutableState = MutableStateFlow(MusicState(
        favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet(),
        coverColor = prefs.getBoolean("coverColor", false),
        dynamicColor = prefs.getBoolean("dynamicColor", false), theme = prefs.getString("theme", "system")!!))
    val state = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var refreshJob: Job? = null
    private var sleepJob: Job? = null
    private val future = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncPlayer() }
        override fun onPlayerError(error: PlaybackException) { message("Couldn't play this file. Check that it is still on your phone.") }
    }
    init {
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it; it.addListener(listener); syncPlayer()
            }.onFailure { message("Playback connection failed. Reopen Klang to try again.") }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch {
            if (repository.needsStarter()) installStarter() else refresh()
        }
        viewModelScope.launch {
            while (true) { syncPlayer(); delay(500) }
        }
    }
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            runCatching { repository.tracks() }.onSuccess { tracks ->
                mutableState.update { it.copy(tracks = tracks, loading = false, permission = repository.hasLibraryPermission()) }
                syncPlayer()
            }.onFailure {
                mutableState.update { it.copy(loading = false, permission = repository.hasLibraryPermission()) }
                message("Couldn't read your library. Check music access and refresh.")
            }
        }
    }
    fun installStarter() {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            runCatching { repository.installStarter() }.onSuccess {
                message(if (it == 0) "Starter tracks are already in Music/Klang" else "$it starter tracks installed in Music/Klang")
            }.onFailure { message("Couldn't install starter tracks: ${it.localizedMessage}") }
            mutableState.update { it.copy(busy = false) }; refresh()
        }
    }
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            runCatching { repository.importFiles(uris) }.onSuccess { message("$it tracks added to Music/Klang") }
                .onFailure { message("Import stopped: ${it.localizedMessage}. Any completed files are in your library.") }
            mutableState.update { it.copy(busy = false) }; refresh()
        }
    }
    fun play(tracks: List<Track>, index: Int = 0, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        val player = controller ?: return message("Connecting to playback…")
        player.setMediaItems(tracks.map { it.mediaItem() }, index.coerceIn(tracks.indices), 0)
        player.shuffleModeEnabled = shuffled
        player.prepare(); player.play(); syncPlayer()
    }
    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition(); it.play() } }
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)) }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF
    } } }
    fun playNext(track: Track) {
        controller?.let {
            if (it.mediaItemCount == 0) { play(listOf(track)); return }
            it.addMediaItem(it.currentMediaItemIndex + 1, track.mediaItem()); message("Added to play next")
        }
    }
    fun queuePlay(index: Int) { controller?.let { it.seekToDefaultPosition(index); it.prepare(); it.play() } }
    fun queueRemove(index: Int) { controller?.removeMediaItem(index) }
    fun favorite(track: Track) {
        val updated = mutableState.value.favorites.toMutableSet().apply { if (!add(track.id)) remove(track.id) }.toSet()
        prefs.edit().putStringSet("favorites", updated).apply()
        mutableState.update { it.copy(favorites = updated) }
    }
    fun theme(value: String) { prefs.edit().putString("theme", value).apply(); mutableState.update { it.copy(theme = value) } }
    fun coverColor(value: Boolean) { prefs.edit().putBoolean("coverColor", value).apply(); mutableState.update { it.copy(coverColor = value) } }
    fun dynamicColor(value: Boolean) { prefs.edit().putBoolean("dynamicColor", value).apply(); mutableState.update { it.copy(dynamicColor = value) } }
    fun sleep(minutes: Int) {
        sleepJob?.cancel(); mutableState.update { it.copy(sleepMinutes = minutes) }
        if (minutes > 0) sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L); controller?.pause(); mutableState.update { it.copy(sleepMinutes = 0) }
            message("Sleep timer finished")
        }
    }
    private fun track(item: MediaItem): Track = mutableState.value.tracks.find { it.id == item.mediaId }
        ?: Track(Uri.parse(item.mediaId), item.mediaMetadata.title?.toString() ?: "Untitled",
            item.mediaMetadata.artist?.toString() ?: "Unknown artist", item.mediaMetadata.albumTitle?.toString() ?: "Unknown album", 0)
    private fun syncPlayer() {
        val player = controller ?: return
        mutableState.update { it.copy(connected = true, current = player.currentMediaItem?.let(::track),
            queueIndex = player.currentMediaItemIndex, playing = player.isPlaying, position = player.currentPosition.coerceAtLeast(0),
            duration = player.duration.coerceAtLeast(0), queue = (0 until player.mediaItemCount).map { i -> track(player.getMediaItemAt(i)) },
            shuffle = player.shuffleModeEnabled, repeat = player.repeatMode) }
    }
    private fun message(text: String) { mutableState.update { it.copy(message = text) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    override fun onCleared() {
        controller?.removeListener(listener); MediaController.releaseFuture(future)
    }
}
