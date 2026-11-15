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
import app.lynli.klang.data.PlaylistCovers
import app.lynli.klang.data.audioFileIds
import app.lynli.klang.playback.PlaybackService
import app.lynli.klang.playback.PLAYBACK_SOURCE_KEY
import app.lynli.klang.playback.canResumeCollection
import app.lynli.klang.playback.collectionPlaybackStart
import app.lynli.klang.playback.dismissPausedPlayback
import app.lynli.klang.playback.playbackSourceOrNull
import app.lynli.klang.playback.removeDeletedAudio
import app.lynli.klang.playback.skipPlaybackTrack
import app.lynli.klang.playback.withPlaybackSource
import app.lynli.klang.plugins.PluginRegistry
import app.lynli.klang.plugins.PluginStore
import app.lynli.klang.plugins.PluginCatalog
import app.lynli.klang.plugins.PluginCandidate
import app.lynli.klang.plugins.PluginDownload
import app.lynli.klang.plugins.GitHubPluginRepo
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MusicState(
    val tracks: List<Track> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val permission: Boolean = false,
    val current: Track? = null, val playing: Boolean = false, val connected: Boolean = false,
    val playWhenReady: Boolean = false, val hasPreviousTrack: Boolean = false, val hasNextTrack: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val queue: List<Track> = emptyList(),
    val playbackDiscontinuity: Long = 0,
    val playbackSource: String? = null, val queueIndex: Int = 0, val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val favorites: Set<String> = emptySet(), val message: String? = null,
    val playlists: List<SavedPlaylist> = emptyList(),
    val coverColor: Boolean = true, val dynamicColor: Boolean = false, val theme: String = "system", val sleepMinutes: Int = 0,
    val sleepEndsAt: Long = 0, val sleepRemaining: Long = 0,
    val hiddenIds: Set<String> = emptySet(), val hiddenTracks: List<Track> = emptyList(),
    val onboardingComplete: Boolean = false, val recommendedPluginsSeen: Boolean = true, val notificationPermission: Boolean = false,
    val update: UpdateInfo? = null, val updateDismissed: Boolean = false, val updating: Boolean = false,
    val downloadProgress: Float = 0f,
) {
    fun isPlayingFrom(sourceId: String): Boolean = canResumeCollection(playbackSource, sourceId, current != null)
}

internal data class AudioDeletionState(val track: Track, val deleting: Boolean = false,
    val confirmation: android.app.PendingIntent? = null)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application)
    private val prefs = application.getSharedPreferences("klang", 0)
    private val playlistStore = PlaylistStore(application)
    private val pluginRegistry = PluginRegistry(application)
    private val pluginStore = PluginStore(application) { pluginRegistry.manifests }
    private val pluginDownload = PluginDownload()
    private val mutablePluginCatalog = MutableStateFlow(PluginCatalog(installed = pluginRegistry.entries, error = pluginRegistry.loadError))
    val pluginCatalog = mutablePluginCatalog.asStateFlow()
    val pluginManifests get() = mutablePluginCatalog.value.installed.map { it.manifest }
    private val mutablePlugins = MutableStateFlow(pluginStore.read())
    val plugins = mutablePlugins.asStateFlow()
    internal val musicImports = app.lynli.klang.importing.ImportController(application, viewModelScope) {
        pluginRegistry.entries.filter { it.manifest.musicImport != null && it.manifest.id in mutablePlugins.value.enabled }
    }
    private val lyricsRepository = LyricsRepository(application, pluginRegistry)
    private val mutableLyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyrics = mutableLyrics.asStateFlow()
    private val lyricsViewers = MutableStateFlow(0)
    private val lyricsRetry = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(MusicState(
        onboardingComplete = prefs.getBoolean("onboardingComplete", false),
        recommendedPluginsSeen = prefs.getBoolean("recommendedPluginsSeen", prefs.getBoolean("onboardingComplete", false)),
        permission = repository.hasLibraryPermission(), notificationPermission = hasNotificationPermission(),
        favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet(),
        playlists = runCatching { playlistStore.read() }.getOrDefault(emptyList()),
        coverColor = prefs.getBoolean("contentColors", true),
        dynamicColor = prefs.getBoolean("dynamicColor", false), theme = prefs.getString("theme", "system")!!,
        hiddenIds = prefs.getStringSet("hiddenTracks", emptySet()).orEmpty().toSet()))
    val state = mutableState.asStateFlow()
    private val mutableAudioDeletion = MutableStateFlow<AudioDeletionState?>(null)
    internal val audioDeletion = mutableAudioDeletion.asStateFlow()
    private val deletedAudioIds = mutableSetOf<String>()
    private var controller: MediaController? = null
    private var refreshJob: Job? = null
    private var sleepJob: Job? = null
    private val future = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            mutableState.update { it.copy(playbackDiscontinuity = it.playbackDiscontinuity + 1) }
            syncPlayer()
        }
        override fun onEvents(player: Player, events: Player.Events) { syncPlayer() }
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("KlangPlayback", "Playback failed: ${error.errorCodeName}", error)
            message("Playback failed (${error.errorCodeName}). Try playing again.")
        }
    }
    init {
        viewModelScope.launch {
            var completedIds = emptySet<String>()
            var importPlaylistIds = emptySet<String>()
            musicImports.store.batches.collect { batches ->
                val ids = batches.flatMap { it.entries }.mapNotNull { it.uri }.toSet()
                val playlistIds = batches.mapNotNull { it.playlistId }.toSet()
                if (ids != completedIds || playlistIds != importPlaylistIds) {
                    completedIds = ids; importPlaylistIds = playlistIds
                    mutableState.update { it.copy(playlists = playlistStore.read()) }
                    refresh()
                }
            }
        }
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
                controller = it; it.addListener(listener)
                deletedAudioIds.forEach { id -> it.removeDeletedAudio(id) }
                syncPlayer()
            }.onFailure { message("Playback connection failed. Reopen Klang to try again.") }
        }, ContextCompat.getMainExecutor(application))
        refresh()
        checkPluginUpdates(clearError = false)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // The downloader moved to a separate APK; reclaim its old unpacked host runtimes.
                    java.io.File(getApplication<Application>().noBackupFilesDir, "youtubedl-android").deleteRecursively()
                    java.io.File(getApplication<Application>().filesDir, "music-imports.json").delete()
                }
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
    internal fun requestAudioDeletion(track: Track) {
        if (mutableAudioDeletion.value == null) mutableAudioDeletion.value = AudioDeletionState(track)
    }
    internal fun cancelAudioDeletion() { mutableAudioDeletion.value = null }
    internal fun confirmAudioDeletion() {
        val request = mutableAudioDeletion.value?.takeUnless { it.deleting } ?: return
        mutableAudioDeletion.value = request.copy(deleting = true)
        viewModelScope.launch {
            try {
                val confirmation = repository.deleteAudio(request.track)
                if (confirmation == null) audioDeleted(request.track.id)
                else mutableAudioDeletion.value = request.copy(deleting = true, confirmation = confirmation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                audioDeletionFailed(error)
            }
        }
    }
    internal fun audioDeletionResult(trackId: String, deleted: Boolean) {
        if (deleted) audioDeleted(trackId)
        else {
            if (mutableAudioDeletion.value?.track?.id == trackId) mutableAudioDeletion.value = null
            message("Audio file kept")
        }
    }
    internal fun audioDeletionFailed(error: Exception) {
        mutableAudioDeletion.value = null
        android.util.Log.e("KlangLibrary", "Audio deletion failed", error)
        message("Couldn't delete the audio file. Try again.")
    }
    /** Called only after MediaStore deleted the file or the system returned deletion success. */
    private fun audioDeleted(id: String) {
        val ids = audioFileIds(id)
        deletedAudioIds += ids
        refreshJob?.cancel()
        musicImports.store.forgetAudioFile(id)
        val favorites = mutableState.value.favorites - ids
        val hidden = mutableState.value.hiddenIds - ids
        prefs.edit().putStringSet("favorites", favorites).putStringSet("hiddenTracks", hidden).apply()
        val playlists = playlistStore.update { items -> items.map { it.copy(trackIds = it.trackIds.filterNot { trackId -> trackId in ids }) } }
        controller?.removeDeletedAudio(id)
        mutableAudioDeletion.value = null
        mutableState.update { state -> state.copy(
            tracks = state.tracks.filterNot { it.id in ids }, hiddenTracks = state.hiddenTracks.filterNot { it.id in ids },
            favorites = favorites, hiddenIds = hidden, playlists = playlists,
            current = state.current?.takeUnless { it.id in ids }, queue = state.queue.filterNot { it.id in ids }) }
        if (mutableState.value.queue.isEmpty()) sleep(0)
        syncPlayer()
        refresh()
        message("Audio file deleted")
    }
    private fun hasNotificationPermission() = ContextCompat.checkSelfPermission(getApplication(),
        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun completePluginRecommendations() {
        prefs.edit().putBoolean("recommendedPluginsSeen", true).apply()
        mutableState.update { it.copy(recommendedPluginsSeen = true) }
    }
    fun refreshPluginServices() {
        val previous = pluginRegistry.entries.map { it.manifest.id }.toSet()
        pluginRegistry.discoverServices()
        mutablePlugins.value = pluginStore.read()
        pluginRegistry.entries.filter { it.manifest.id !in previous && it.manifest.defaultEnabled }.forEach {
            mutablePlugins.value = pluginStore.enable(it.manifest.id, true)
        }
        mutablePluginCatalog.update { it.copy(installed = pluginRegistry.entries) }
    }
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
    fun play(tracks: List<Track>, index: Int? = null, shuffled: Boolean = false, sourceId: String? = null) {
        if (tracks.isEmpty()) return
        val player = controller ?: return message("Connecting to playback…")
        player.setPlaylistMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            sourceId?.let { putString(PLAYBACK_SOURCE_KEY, it) }
        }).build())
        val start = collectionPlaybackStart(tracks.size, index, sourceId, shuffled)
        player.setMediaItems(tracks.map { it.mediaItem().withPlaybackSource(sourceId) }, start.index, 0)
        player.shuffleModeEnabled = start.shuffle
        player.prepare(); player.play(); syncPlayer()
    }
    /** Resume/pause a matching collection without replacing the queue or resetting the position. */
    fun playOrToggle(tracks: List<Track>, sourceId: String) {
        val player = controller
        if (canResumeCollection(player?.playbackSourceOrNull(),
                sourceId, player?.currentMediaItem != null)) {
            if (sourceId == "mix" && player?.isPlaying == false) player.shuffleModeEnabled = true
            togglePlay()
        }
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
    suspend fun inspectPlugin(link: String): PluginCandidate = requireNotNull(pluginDownload.latest(GitHubPluginRepo.parse(link)))
    fun clearPluginError() { mutablePluginCatalog.update { it.copy(error = null) } }
    fun dismissPluginUpdateNotice() { mutablePluginCatalog.update { it.copy(updateNoticeDismissed = true) } }
    fun installRecommendedPlugin(link: String) {
        if (mutablePluginCatalog.value.busyId != null) return
        mutablePluginCatalog.update { it.copy(busyId = "recommended", error = null) }
        viewModelScope.launch {
            try {
                val candidate = inspectPlugin(link)
                mutablePluginCatalog.update { it.copy(busyId = null) }
                installPlugin(candidate, enable = true)
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { mutablePluginCatalog.update { it.copy(busyId = null, error = error.message) } }
        }
    }
    fun installPlugin(candidate: PluginCandidate, enable: Boolean = false) {
        if (mutablePluginCatalog.value.busyId != null) return
        mutablePluginCatalog.update { it.copy(busyId = candidate.manifest.id, error = null) }
        viewModelScope.launch {
            try {
                val existing = pluginRegistry.entries.find { it.manifest.id == candidate.manifest.id }
                // Preserve the previous enabled state when updating. New plugins need a user toggle.
                val enabled = enable || existing != null && existing.manifest.id in mutablePlugins.value.enabled
                if (candidate.manifest.musicImport != null) {
                    val existingRepository = pluginRegistry.entries.find { it.manifest.id == candidate.manifest.id }?.repository
                    require(existingRepository == null || existingRepository == candidate.repository) { "This plugin ID belongs to another repository." }
                    val apk = pluginDownload.downloadApk(getApplication(), candidate)
                    pluginRegistry.allowReinstall(candidate.manifest.id)
                    app.lynli.klang.update.Updater.install(getApplication(), apk)
                    return@launch
                }
                withContext(Dispatchers.IO) { pluginRegistry.install(candidate) }
                mutablePlugins.value = pluginStore.enable(candidate.manifest.id, enabled)
                mutablePluginCatalog.update { it.copy(installed = pluginRegistry.entries, updates = it.updates - candidate.manifest.id) }
                retryLyrics()
                message(if (existing == null) "${candidate.manifest.name} installed" else "${candidate.manifest.name} updated")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutablePluginCatalog.update { it.copy(error = error.message ?: "Couldn't install this plugin.") } }
            finally { mutablePluginCatalog.update { it.copy(busyId = null) } }
        }
    }
    fun uninstallPlugin(id: String) {
        if (mutablePluginCatalog.value.busyId != null) return
        mutablePluginCatalog.update { it.copy(busyId = id, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { pluginRegistry.uninstall(id) }
                pluginStore.remove(id)
                mutablePlugins.value = pluginStore.read()
                mutablePluginCatalog.update { it.copy(installed = pluginRegistry.entries, updates = it.updates - id) }
                retryLyrics()
                // Disabling first cancels the in-flight lookup before deleting its cache.
                lyricsRepository.clearCache(id)
                message("Plugin uninstalled")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutablePluginCatalog.update { it.copy(error = "Couldn't uninstall this plugin.") } }
            finally { mutablePluginCatalog.update { it.copy(busyId = null) } }
        }
    }
    fun checkPluginUpdates(clearError: Boolean = true) {
        if (mutablePluginCatalog.value.checking) return
        mutablePluginCatalog.update { it.copy(checking = true, error = if (clearError) null else it.error) }
        viewModelScope.launch {
            var failures = 0
            try {
                for (entry in pluginRegistry.entries) {
                    val repo = entry.repository ?: continue
                    try {
                        val candidate = pluginDownload.latest(repo, entry.manifest.version)
                        if (candidate == null) {
                            mutablePluginCatalog.update { it.copy(updates = it.updates - entry.manifest.id) }
                            continue
                        }
                        require(candidate.manifest.id == entry.manifest.id) { "Plugin ID changed in this release" }
                        mutablePluginCatalog.update { catalog ->
                            val current = catalog.installed.find { it.manifest.id == entry.manifest.id }
                            if (current != null && app.lynli.klang.plugins.PluginVersion.parse(candidate.manifest.version) >
                                app.lynli.klang.plugins.PluginVersion.parse(current.manifest.version))
                                catalog.copy(updates = catalog.updates + (entry.manifest.id to candidate))
                            else catalog.copy(updates = catalog.updates - entry.manifest.id)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failures++ }
                }
            } finally {
                mutablePluginCatalog.update { it.copy(checking = false, checked = true,
                    error = if (failures > 0) "Couldn't check $failures plugin ${if (failures == 1) "repository" else "repositories"}. Try again when online." else it.error) }
            }
        }
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
        updatePlaylists { it + playlist }
        return playlist.id
    }
    fun renamePlaylist(id: String, name: String) {
        if (name.trim().isNotEmpty()) editPlaylist(id) { it.copy(name = name.trim()) }
    }
    fun deletePlaylist(id: String) {
        val cover = mutableState.value.playlists.find { it.id == id }?.coverFile
        updatePlaylists { items -> items.filterNot { it.id == id } }
        viewModelScope.launch { PlaylistCovers.delete(getApplication(), cover) }
    }
    fun setPlaylistCover(id: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val cover = PlaylistCovers.import(getApplication(), uri)
                val playlist = mutableState.value.playlists.find { it.id == id }
                if (playlist == null) PlaylistCovers.delete(getApplication(), cover)
                else {
                    editPlaylist(id) { it.copy(coverFile = cover) }
                    PlaylistCovers.delete(getApplication(), playlist.coverFile)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message("Couldn't use that image. Choose another photo.")
            }
        }
    }
    fun removePlaylistCover(id: String) {
        val cover = mutableState.value.playlists.find { it.id == id }?.coverFile
        editPlaylist(id) { it.copy(coverFile = null) }
        viewModelScope.launch { PlaylistCovers.delete(getApplication(), cover) }
    }
    fun addToPlaylist(id: String, tracks: List<Track>) { editPlaylist(id) { it.add(tracks.map(Track::id)) } }
    fun removeFromPlaylist(id: String, trackId: String) { editPlaylist(id) { it.remove(trackId) } }
    fun reorderPlaylist(id: String, orderedIds: List<String>) { editPlaylist(id) { it.reorder(orderedIds) } }
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
        updatePlaylists { items -> items.map { if (it.id == id) edit(it) else it } }
    }
    private fun updatePlaylists(edit: (List<SavedPlaylist>) -> List<SavedPlaylist>) {
        val playlists = playlistStore.update(edit)
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
        val previous = mutableState.value
        val source = player.playbackSourceOrNull()
        if (player.isPlaying && (!previous.playing || previous.playbackSource != source)) {
            source?.takeIf { it.startsWith("playlist:") }?.removePrefix("playlist:")?.let { id ->
                if (previous.playlists.any { it.id == id }) editPlaylist(id) { it.copy(lastPlayedAt = System.currentTimeMillis()) }
            }
        }
        mutableState.update { it.copy(connected = true, playbackSource = player.playbackSourceOrNull(),
            current = player.currentMediaItem?.takeUnless { item -> item.mediaId in deletedAudioIds }?.let(::track),
            playWhenReady = player.playWhenReady, hasPreviousTrack = player.hasPreviousMediaItem(), hasNextTrack = player.hasNextMediaItem(),
            queueIndex = player.currentMediaItemIndex, playing = player.isPlaying, position = player.currentPosition.coerceAtLeast(0),
            duration = player.duration.coerceAtLeast(0), queue = (0 until player.mediaItemCount).map { i -> player.getMediaItemAt(i) }
                .filterNot { item -> item.mediaId in deletedAudioIds }.map(::track),
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
