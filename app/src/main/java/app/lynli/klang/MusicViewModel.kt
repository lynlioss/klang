package app.lynli.klang

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import app.lynli.klang.data.MusicRepository
import app.lynli.klang.data.Track
import app.lynli.klang.data.TrackDetails
import app.lynli.klang.data.PlaylistStore
import app.lynli.klang.data.SavedPlaylist
import app.lynli.klang.playback.PlaybackService
import app.lynli.klang.playback.PLAYBACK_SOURCE_KEY
import app.lynli.klang.playback.canResumeCollection
import app.lynli.klang.playback.dismissPausedPlayback
import app.lynli.klang.playback.playbackSourceOrNull
import app.lynli.klang.playback.skipPlaybackTrack
import app.lynli.klang.playback.withPlaybackSource
import app.lynli.klang.plugins.PluginRegistry
import app.lynli.klang.plugins.PluginStore
import app.lynli.klang.update.UpdateInfo
import app.lynli.klang.update.Updater
import app.lynli.klang.lyrics.LyricsQuery
import app.lynli.klang.lyrics.LyricsRepository
import app.lynli.klang.lyrics.LyricsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class MusicState(
    val tracks: List<Track> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val permission: Boolean = false,
    val current: Track? = null, val playing: Boolean = false, val connected: Boolean = false,
    val playWhenReady: Boolean = false, val hasPreviousTrack: Boolean = false, val hasNextTrack: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val queue: List<Track> = emptyList(),
    val playbackSource: String? = null, val queueIndex: Int = 0, val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val favorites: Set<String> = emptySet(), val message: String? = null,
    val playlists: List<SavedPlaylist> = emptyList(),
    val coverColor: Boolean = true, val dynamicColor: Boolean = false, val theme: String = "system", val sleepMinutes: Int = 0,
    val sleepEndsAt: Long = 0, val sleepRemaining: Long = 0,
    val hiddenIds: Set<String> = emptySet(), val hiddenTracks: List<Track> = emptyList(),
    val onboardingComplete: Boolean = false, val notificationPermission: Boolean = false,
    val update: UpdateInfo? = null, val updateDismissed: Boolean = false, val updating: Boolean = false,
    val downloadProgress: Float = 0f,
) {
    fun isPlayingFrom(sourceId: String): Boolean = canResumeCollection(playbackSource, sourceId, current != null)
}

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application)
    private val prefs = application.getSharedPreferences("klang", 0)
    private val playlistStore = PlaylistStore(application)
    private val pluginRegistry = PluginRegistry(application)
    private val pluginStore = PluginStore(application, pluginRegistry.manifests)
    val pluginManifests = pluginRegistry.manifests
    private val mutablePlugins = MutableStateFlow(pluginStore.read())
    val plugins = mutablePlugins.asStateFlow()
    private val lyricsRepository = LyricsRepository(application, pluginRegistry)
    private val mutableLyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyrics = mutableLyrics.asStateFlow()
    private val lyricsViewers = MutableStateFlow(0)
    private val lyricsRetry = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(MusicState(
        onboardingComplete = prefs.getBoolean("onboardingComplete", false),
        permission = repository.hasLibraryPermission(), notificationPermission = hasNotificationPermission(),
        favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet(),
        playlists = runCatching { playlistStore.read() }.getOrDefault(emptyList()),
        coverColor = prefs.getBoolean("contentColors", true),
        dynamicColor = prefs.getBoolean("dynamicColor", false), theme = prefs.getString("theme", "system")!!,
        hiddenIds = prefs.getStringSet("hiddenTracks", emptySet()).orEmpty().toSet()))
    val state = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var refreshJob: Job? = null
    private var sleepJob: Job? = null
    private val future = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncPlayer() }
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("KlangPlayback", "Playback failed: ${error.errorCodeName}", error)
            message("Playback failed (${error.errorCodeName}). Try playing again.")
        }
    }
    init {
        viewModelScope.launch {
            combine(mutableState.map { state -> state.current?.let { track ->
                LyricsQuery(track.title, track.artist, track.album, state.duration.takeIf { it > 0 } ?: track.durationMs)
            } }.distinctUntilChanged(), mutablePlugins, lyricsViewers, lyricsRetry) { query, config, viewers, _ ->
                Triple(query, config, viewers)
            }.collectLatest { (query, config, viewers) ->
                if (viewers == 0 || query == null) mutableLyrics.value = LyricsState.Idle
                else {
                    mutableLyrics.value = LyricsState.Loading
                    mutableLyrics.value = lyricsRepository.find(query, config)
                }
            }
        }
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it; it.addListener(listener); syncPlayer()
            }.onFailure { message("Playback connection failed. Reopen Klang to try again.") }
        }, ContextCompat.getMainExecutor(application))
        refresh()
        viewModelScope.launch {
            try {
                // A previous update's APK is no longer needed once this version runs.
                Updater.clearDownloads(getApplication())
                Updater.latest()?.let { info -> mutableState.update { it.copy(update = info) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Offline, private repository or rate limited: no banner is correct.
                android.util.Log.d("KlangUpdate", "Update check skipped: ${error.message}")
            }
        }
        viewModelScope.launch {
            while (true) { syncPlayer(); tickSleep(); delay(500) }
        }
    }
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val all = repository.tracks()
                val hidden = mutableState.value.hiddenIds
                mutableState.update { it.copy(tracks = all.filterNot { track -> track.id in hidden },
                    hiddenTracks = all.filter { track -> track.id in hidden },
                    loading = false, permission = repository.hasLibraryPermission(),
                    notificationPermission = hasNotificationPermission()) }
                syncPlayer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("KlangLibrary", "Library scan failed", error)
                mutableState.update { it.copy(loading = false, permission = repository.hasLibraryPermission(),
                    notificationPermission = hasNotificationPermission()) }
                message("Couldn't read your library. Check music access and refresh.")
            }
        }
    }
    /** Removes a file from the app without deleting it from the phone. */
    fun hide(track: Track) {
        val hidden = mutableState.value.hiddenIds + track.id
        prefs.edit().putStringSet("hiddenTracks", hidden).apply()
        mutableState.update { state -> state.copy(hiddenIds = hidden,
            tracks = state.tracks.filterNot { it.id == track.id },
            hiddenTracks = (state.hiddenTracks + track).distinctBy(Track::id)) }
        message("${track.title} hidden · restore it in Settings → Hidden audio files")
    }
    fun restore(track: Track) {
        val hidden = mutableState.value.hiddenIds - track.id
        prefs.edit().putStringSet("hiddenTracks", hidden).apply()
        mutableState.update { state -> state.copy(hiddenIds = hidden,
            hiddenTracks = state.hiddenTracks.filterNot { it.id == track.id }) }
        refresh()
    }
    fun restoreAllHidden() {
        prefs.edit().putStringSet("hiddenTracks", emptySet()).apply()
        mutableState.update { it.copy(hiddenIds = emptySet(), hiddenTracks = emptyList()) }
        refresh()
    }
    suspend fun details(track: Track): TrackDetails = repository.details(track)
    private fun hasNotificationPermission() = ContextCompat.checkSelfPermission(getApplication(),
        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun completeOnboarding() {
        prefs.edit().putBoolean("onboardingComplete", true).apply()
        mutableState.update { it.copy(onboardingComplete = true) }
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
    fun play(tracks: List<Track>, index: Int = 0, shuffled: Boolean = false, sourceId: String? = null) {
        if (tracks.isEmpty()) return
        val player = controller ?: return message("Connecting to playback…")
        player.setPlaylistMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            sourceId?.let { putString(PLAYBACK_SOURCE_KEY, it) }
        }).build())
        player.setMediaItems(tracks.map { it.mediaItem().withPlaybackSource(sourceId) },
            index.coerceIn(tracks.indices), 0)
        player.shuffleModeEnabled = shuffled
        player.prepare(); player.play(); syncPlayer()
    }
    /** Resume/pause a matching collection without replacing the queue or resetting the position. */
    fun playOrToggle(tracks: List<Track>, sourceId: String) {
        val player = controller
        if (canResumeCollection(player?.playbackSourceOrNull(),
                sourceId, player?.currentMediaItem != null)) togglePlay()
        else play(tracks, sourceId = sourceId)
    }
    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition(); it.play() } }
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    /** A mini player swipe selects the adjacent track, even after the restart threshold. */
    fun skipTrack(expectedTrackId: String, forward: Boolean): Boolean {
        val changed = controller?.skipPlaybackTrack(expectedTrackId, forward) == true
        if (changed) syncPlayer()
        return changed
    }
    /** Clearing the paused queue also clears its saved service state and playback notification. */
    fun dismissPlayer(expectedTrackId: String): Boolean {
        val dismissed = controller?.dismissPausedPlayback(expectedTrackId) == true
        if (dismissed) { sleep(0); syncPlayer() }
        return dismissed
    }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)); syncPlayer() }
    fun playbackPosition(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: mutableState.value.position
    fun lyricsOpened() { lyricsViewers.update { it + 1 } }
    fun lyricsClosed() { lyricsViewers.update { (it - 1).coerceAtLeast(0) } }
    fun retryLyrics() { lyricsRetry.update { it + 1 } }
    fun enablePlugin(id: String, enabled: Boolean) { mutablePlugins.value = pluginStore.enable(id, enabled) }
    fun pluginSetting(id: String, key: String, value: String) {
        mutablePlugins.value = pluginStore.setting(id, key, value)
        if (key == "cacheLyrics" && value == "false") viewModelScope.launch { lyricsRepository.clearCache(id) }
    }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF
    } } }
    fun playNext(track: Track) {
        controller?.let {
            if (it.mediaItemCount == 0) { play(listOf(track)); return }
            it.addMediaItem(it.currentMediaItemIndex + 1,
                track.mediaItem().withPlaybackSource(it.playbackSourceOrNull())); message("Added to play next")
        }
    }
    fun queuePlay(index: Int) { controller?.let { it.seekToDefaultPosition(index); it.prepare(); it.play() } }
    fun queueRemove(index: Int) { controller?.removeMediaItem(index) }
    fun favorite(track: Track) {
        val updated = mutableState.value.favorites.toMutableSet().apply { if (!add(track.id)) remove(track.id) }.toSet()
        prefs.edit().putStringSet("favorites", updated).apply()
        mutableState.update { it.copy(favorites = updated) }
    }
    fun createPlaylist(name: String): String? {
        if (name.trim().isEmpty()) return null
        val playlist = playlistStore.create(name)
        savePlaylists(mutableState.value.playlists + playlist)
        return playlist.id
    }
    fun renamePlaylist(id: String, name: String) {
        if (name.trim().isNotEmpty()) editPlaylist(id) { it.copy(name = name.trim()) }
    }
    fun deletePlaylist(id: String) { savePlaylists(mutableState.value.playlists.filterNot { it.id == id }) }
    fun addToPlaylist(id: String, tracks: List<Track>) { editPlaylist(id) { it.add(tracks.map(Track::id)) } }
    fun removeFromPlaylist(id: String, trackId: String) { editPlaylist(id) { it.remove(trackId) } }
    fun moveInPlaylist(id: String, trackId: String, offset: Int) {
        val available = mutableState.value.tracks.map { it.id }.toSet()
        editPlaylist(id) { playlist ->
            val visible = playlist.trackIds.filter { it in available }
            val index = visible.indexOf(trackId)
            val target = index + offset
            if (index < 0 || target !in visible.indices) playlist
            else playlist.move(trackId, playlist.trackIds.indexOf(visible[target]) - playlist.trackIds.indexOf(trackId))
        }
    }
    private fun editPlaylist(id: String, edit: (SavedPlaylist) -> SavedPlaylist) {
        savePlaylists(mutableState.value.playlists.map { if (it.id == id) edit(it) else it })
    }
    private fun savePlaylists(playlists: List<SavedPlaylist>) {
        playlistStore.write(playlists)
        mutableState.update { it.copy(playlists = playlists) }
    }
    fun theme(value: String) { prefs.edit().putString("theme", value).apply(); mutableState.update { it.copy(theme = value) } }
    fun coverColor(value: Boolean) { prefs.edit().putBoolean("contentColors", value).apply(); mutableState.update { it.copy(coverColor = value) } }
    fun dynamicColor(value: Boolean) { prefs.edit().putBoolean("dynamicColor", value).apply(); mutableState.update { it.copy(dynamicColor = value) } }
    fun sleep(minutes: Int) {
        sleepJob?.cancel()
        val duration = minutes.coerceIn(0, 600)
        val endsAt = if (duration > 0) System.currentTimeMillis() + duration * 60_000L else 0
        mutableState.update { it.copy(sleepMinutes = duration, sleepEndsAt = endsAt,
            sleepRemaining = if (endsAt > 0) duration * 60_000L else 0) }
        if (duration > 0) sleepJob = viewModelScope.launch {
            delay(duration * 60_000L)
            controller?.pause()
            mutableState.update { it.copy(sleepMinutes = 0, sleepEndsAt = 0, sleepRemaining = 0) }
            message("Sleep timer finished")
        }
    }
    private fun tickSleep() {
        val endsAt = mutableState.value.sleepEndsAt
        if (endsAt <= 0) return
        val remaining = (endsAt - System.currentTimeMillis()).coerceAtLeast(0)
        mutableState.update { if (it.sleepRemaining == remaining) it else it.copy(sleepRemaining = remaining) }
    }
    private fun track(item: MediaItem): Track = mutableState.value.tracks.find { it.id == item.mediaId }
        ?: mutableState.value.hiddenTracks.find { it.id == item.mediaId }
        ?: Track(Uri.parse(item.mediaId), item.mediaMetadata.title?.toString() ?: "Untitled",
            item.mediaMetadata.artist?.toString() ?: "Unknown artist", item.mediaMetadata.albumTitle?.toString() ?: "Unknown album", 0)
    private fun syncPlayer() {
        val player = controller ?: return
        mutableState.update { it.copy(connected = true, playbackSource = player.playbackSourceOrNull(), current = player.currentMediaItem?.let(::track),
            playWhenReady = player.playWhenReady, hasPreviousTrack = player.hasPreviousMediaItem(), hasNextTrack = player.hasNextMediaItem(),
            queueIndex = player.currentMediaItemIndex, playing = player.isPlaying, position = player.currentPosition.coerceAtLeast(0),
            duration = player.duration.coerceAtLeast(0), queue = (0 until player.mediaItemCount).map { i -> track(player.getMediaItemAt(i)) },
            shuffle = player.shuffleModeEnabled, repeat = player.repeatMode) }
    }
    private fun message(text: String) { mutableState.update { it.copy(message = text) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    /** Hides the banner for this session only; a relaunch shows it again. */
    fun dismissUpdate() { mutableState.update { it.copy(updateDismissed = true) } }
    /** Downloads the released APK and hands it to the system installer. */
    fun downloadUpdate() {
        val info = mutableState.value.update ?: return
        if (mutableState.value.updating) return
        viewModelScope.launch {
            mutableState.update { it.copy(updating = true, downloadProgress = 0f) }
            try {
                val apk = Updater.download(getApplication(), info) { fraction ->
                    mutableState.update { it.copy(downloadProgress = fraction) }
                }
                Updater.install(getApplication(), apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("KlangUpdate", "Update failed", error)
                message("Update failed. Check your connection and try again.")
            } finally {
                mutableState.update { it.copy(updating = false, downloadProgress = 0f) }
            }
        }
    }
    override fun onCleared() {
        controller?.removeListener(listener); MediaController.releaseFuture(future)
    }
}
