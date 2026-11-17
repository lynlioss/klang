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
import app.lynli.klang.analytics.AnalyticsConsent
import app.lynli.klang.data.MusicRepository
import app.lynli.klang.data.Track
import app.lynli.klang.data.TrackDetails
import app.lynli.klang.data.PlaylistStore
import app.lynli.klang.data.SavedPlaylist
import app.lynli.klang.data.PlaylistCovers
import app.lynli.klang.data.audioFileIds
import app.lynli.klang.data.sameSong
import app.lynli.klang.data.byIdentity
import app.lynli.klang.data.retainSourceSong
import app.lynli.klang.plugins.SourceCollection
import app.lynli.klang.plugins.SourceConnection
import app.lynli.klang.plugins.SourceStreams
import app.lynli.klang.plugins.SourceLibrary
import app.lynli.klang.playback.PlaybackService
import app.lynli.klang.playback.PLAYBACK_SOURCE_KEY
import app.lynli.klang.playback.canResumeCollection
import app.lynli.klang.playback.collectionPlaybackStart
import app.lynli.klang.playback.dismissPausedPlayback
import app.lynli.klang.playback.playbackSourceOrNull
import app.lynli.klang.playback.removeDeletedAudio
import app.lynli.klang.playback.skipPlaybackTrack
import app.lynli.klang.playback.togglePlayback
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
    val playWhenReady: Boolean = false, val buffering: Boolean = false, val hasPreviousTrack: Boolean = false, val hasNextTrack: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val queue: List<Track> = emptyList(),
    val playbackDiscontinuity: Long = 0,
    val playbackSource: String? = null, val queueIndex: Int = 0, val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val favorites: Set<String> = emptySet(), val message: String? = null,
    val playlists: List<SavedPlaylist> = emptyList(),
    val sourceCollections: List<SourceCollection> = emptyList(), val sourceHome: List<SourceCollection> = emptyList(),
    val sourceConnections: List<SourceConnection> = emptyList(), val sourceSyncing: Boolean = false,
    val coverColor: Boolean = true, val dynamicColor: Boolean = false, val theme: String = "system", val sleepMinutes: Int = 0,
    val appIcon: AppIcon = AppIcon.Light,
    val sleepEndsAt: Long = 0, val sleepRemaining: Long = 0,
    val hiddenIds: Set<String> = emptySet(), val hiddenTracks: List<Track> = emptyList(),
    val onboardingComplete: Boolean = false, val recommendedPluginsSeen: Boolean = true, val notificationPermission: Boolean = false,
    val analyticsConsent: AnalyticsConsent = AnalyticsConsent.NOT_ASKED,
    val analyticsPromptPending: Boolean = false,
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
    private val analytics = (application as KlangApplication).analytics
    private val playlistStore = PlaylistStore(application)
    private val pluginRegistry = PluginRegistry(application)
    private val pluginStore = PluginStore(application) { pluginRegistry.manifests }
    private val pluginDownload = PluginDownload()
    private val mutablePluginCatalog = MutableStateFlow(PluginCatalog(installed = pluginRegistry.entries, error = pluginRegistry.loadError))
    val pluginCatalog = mutablePluginCatalog.asStateFlow()
    val pluginManifests get() = mutablePluginCatalog.value.installed.map { it.manifest }
    private val mutablePlugins = MutableStateFlow(pluginStore.read())
    val plugins = mutablePlugins.asStateFlow()
    internal val musicSources = app.lynli.klang.plugins.MusicSourceController(application, viewModelScope, {
        pluginRegistry.entries.filter { it.manifest.id in mutablePlugins.value.enabled }
    }, ::message)
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
        analyticsConsent = analytics.consent,
        analyticsPromptPending = prefs.getBoolean("analyticsPromptPending", false),
        recommendedPluginsSeen = prefs.getBoolean("recommendedPluginsSeen", prefs.getBoolean("onboardingComplete", false)),
        permission = repository.hasLibraryPermission(), notificationPermission = hasNotificationPermission(),
        favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet(),
        playlists = runCatching { playlistStore.read() }.getOrDefault(emptyList()),
        coverColor = prefs.getBoolean("contentColors", true),
        dynamicColor = prefs.getBoolean("dynamicColor", false), theme = prefs.getString("theme", "system")!!,
        appIcon = AppIcon.from(prefs.getString("appIcon", null)),
        hiddenIds = prefs.getStringSet("hiddenTracks", emptySet()).orEmpty().toSet()))
    val state = mutableState.asStateFlow()
    private val mutableAudioDeletion = MutableStateFlow<AudioDeletionState?>(null)
    internal val audioDeletion = mutableAudioDeletion.asStateFlow()
    private val deletedAudioIds = mutableSetOf<String>()
    private var controller: MediaController? = null
    private var refreshJob: Job? = null
    private var collectionQueueJob: Job? = null
    private var deviceTracks = emptyList<Track>()
    private var indexedTracks: List<Track>? = null
    private var indexedHidden: List<Track>? = null
    private var trackIndex = emptyMap<String, Track>()
    private val queueTracks = mutableMapOf<String, Track>()
    private var queueRevision = 0L
    private var shownQueueRevision = -1L
    private var shownLibrary: List<Track>? = null
    private var shownHidden: List<Track>? = null
    private var shownQueue = emptyList<Track>()
    private val favoriteJobs = mutableMapOf<String, Job>()
    private val favoriteOverrides = mutableMapOf<String, Boolean>()
    private val favoriteAccounts = mutableMapOf<String, Pair<String, String?>>()
    private var sleepJob: Job? = null
    private val future = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            mutableState.update { it.copy(playbackDiscontinuity = it.playbackDiscontinuity + 1) }
            syncPlayer()
        }
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueRevision++
            syncPlayer()
        }
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("KlangPlayback", "Playback failed: ${error.errorCodeName}", error)
            message("Playback failed (${error.errorCodeName}). Try playing again.")
        }
    }
    init {
        viewModelScope.launch {
            musicSources.changes.collect {
                musicSources.reloadLibrary()
                val current = mutableState.value.current
                if (current?.providerId != null) withContext(Dispatchers.IO) { musicSources.storedTrack(current.id) }?.let { fresh ->
                    mutableState.update { state -> if (state.current?.id == fresh.id) state.copy(current = fresh) else state }
                }
            }
        }
        viewModelScope.launch {
            musicSources.library.collectLatest { library ->
                applySourceLibrary(library)
            }
        }
        viewModelScope.launch { musicSources.refreshes.collectLatest { musicSources.synchronize() } }
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
    private suspend fun applySourceLibrary(library: SourceLibrary) {
        favoriteAccounts.toMap().forEach { (id, account) ->
            if (account.second != musicSources.accountRevision(account.first)) {
                favoriteJobs.remove(id)?.cancel(); favoriteOverrides.remove(id); favoriteAccounts.remove(id)
            }
        }
        val snapshot = mutableState.value
        val enabled = musicSources.entries().map { it.manifest.id }.toSet()
        val savedFavorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
        val ids = (snapshot.playlists.flatMap { it.trackIds } + savedFavorites + favoriteOverrides.filterValues { it }.keys).distinct()
        val (combined, retainedFavorites) = withContext(Dispatchers.IO) {
            val retained = ids.mapNotNull(musicSources::storedTrack).filter { track ->
                retainSourceSong(track.offline, track.providerId in enabled,
                    runCatching { org.json.JSONObject(track.sourcePayload.orEmpty()).optString("accountRevision") }.getOrNull(),
                    track.providerId?.let(musicSources::accountRevision))
            }
            musicSources.unify(deviceTracks + library.tracks + retained) to
                savedFavorites.filter { !it.startsWith("klang-source:") || retained.any { track -> track.id == it } }.toSet()
        }
        favoriteOverrides.entries.removeAll { (id, value) -> favoriteJobs[id]?.isActive != true && (id in library.favorites) == value }
        val rawFavorites = (retainedFavorites + library.favorites + favoriteOverrides.filterValues { it }.keys) - favoriteOverrides.filterValues { !it }.keys
        val favorites = rawFavorites + combined.filter { track -> track.identities.any { it in rawFavorites } }.flatMap { it.identities }
        mutableState.update { state -> state.copy(tracks = combined.filterNot { it.id in state.hiddenIds },
            hiddenTracks = combined.filter { it.id in state.hiddenIds }, favorites = favorites,
            sourceCollections = library.collections, sourceHome = library.home,
            sourceConnections = library.connections, sourceSyncing = library.syncing) }
    }
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val all = repository.tracks()
                deviceTracks = all
                musicSources.updateDeviceLibrary(all)
                // Local music is usable while the persisted source snapshot loads on an IO thread.
                mutableState.update { state -> if (!state.loading) state else state.copy(
                    tracks = all.filterNot { track -> track.id in state.hiddenIds },
                    hiddenTracks = all.filter { track -> track.id in state.hiddenIds }, loading = false,
                    permission = repository.hasLibraryPermission(), notificationPermission = hasNotificationPermission()) }
                musicSources.reloadLibrary()
                applySourceLibrary(musicSources.library.value)
                mutableState.update { it.copy(loading = false, permission = repository.hasLibraryPermission(),
                    notificationPermission = hasNotificationPermission()) }
                musicSources.requestSync()
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
        val local = track.localUri?.let { uri -> deviceTracks.find { it.uri == uri } ?: track.copy(uri = uri, providerId = null, sourceId = null) }
            ?: track
        if (mutableAudioDeletion.value == null) mutableAudioDeletion.value = AudioDeletionState(local)
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
        analytics.capture(event = "audio_deleted")
        message("Audio file deleted")
    }
    private fun hasNotificationPermission() = ContextCompat.checkSelfPermission(getApplication(),
        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun completePluginRecommendations() {
        prefs.edit().putBoolean("recommendedPluginsSeen", true).apply()
        mutableState.update { it.copy(recommendedPluginsSeen = true) }
    }
    fun refreshPluginServices() {
        val previousEntries = pluginRegistry.entries.map { it.manifest }
        val previous = previousEntries.map { it.id }.toSet()
        pluginRegistry.discoverServices()
        mutablePlugins.value = pluginStore.read()
        pluginRegistry.entries.filter { it.manifest.id !in previous && it.manifest.defaultEnabled }.forEach {
            mutablePlugins.value = pluginStore.enable(it.manifest.id, true)
        }
        mutablePluginCatalog.update { it.copy(installed = pluginRegistry.entries) }
        app.lynli.klang.plugins.ExtensionRuntime.active?.reload()
        if (previousEntries != pluginRegistry.entries.map { it.manifest }) SourceStreams.get(getApplication()).invalidate()
        musicSources.resumed()
    }
    fun completeOnboarding() {
        if (mutableState.value.onboardingComplete) return
        val askAnalytics = analytics.consent == AnalyticsConsent.NOT_ASKED
        prefs.edit().putBoolean("onboardingComplete", true).putBoolean("analyticsPromptPending", askAnalytics).apply()
        mutableState.update { it.copy(onboardingComplete = true, analyticsPromptPending = askAnalytics) }
        analytics.onboardingCompleted()
    }
    fun setAnalyticsConsent(enabled: Boolean) {
        val current = mutableState.value
        if (!current.onboardingComplete) return
        val saved = analytics.setConsent(enabled, if (current.analyticsPromptPending) "onboarding" else "settings")
        if (saved && current.analyticsPromptPending) prefs.edit().putBoolean("analyticsPromptPending", false).apply()
        mutableState.update { it.copy(analyticsConsent = analytics.consent,
            analyticsPromptPending = if (saved) false else it.analyticsPromptPending) }
        if (!saved) message("Couldn't save your analytics choice. Please try again.")
    }
    fun appOpened() = analytics.appOpened()
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            runCatching { repository.importFiles(uris) }.onSuccess { count ->
                analytics.capture(event = "music_imported", properties = mapOf("track_count" to count))
                message("$count tracks added to Music/Klang")
            }.onFailure { message("Import stopped: ${it.localizedMessage}. Any completed files are in your library.") }
            mutableState.update { it.copy(busy = false) }; refresh()
        }
    }
    fun play(tracks: List<Track>, index: Int? = null, shuffled: Boolean = false, sourceId: String? = null) {
        if (tracks.isEmpty()) return
        queueTracks.clear(); tracks.forEach { queueTracks[it.id] = it }
        collectionQueueJob?.cancel()
        val jamStart = collectionPlaybackStart(tracks.size, index, sourceId, shuffled)
        val jamOffset = (jamStart.index - 16).coerceIn(0, (tracks.size - 32).coerceAtLeast(0))
        if (app.lynli.klang.plugins.ExtensionRuntime.active?.routeSelection(tracks.drop(jamOffset).take(32).map { it.mediaItem() }, "replace", jamStart.index - jamOffset) == true) {
            app.lynli.klang.plugins.ExtensionRuntime.active?.routeControl("shuffle", org.json.JSONObject().put("enabled", jamStart.shuffle))
            return
        }
        val player = controller ?: return message("Connecting to playback…")
        player.setPlaylistMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            sourceId?.let { putString(PLAYBACK_SOURCE_KEY, it) }
        }).build())
        val start = collectionPlaybackStart(tracks.size, index, sourceId, shuffled)
        SourceStreams.get(getApplication()).buffering(true, tracks[start.index].id)
        player.setMediaItems(tracks.map { it.mediaItem().withPlaybackSource(sourceId) }, start.index, 0)
        player.shuffleModeEnabled = start.shuffle
        player.prepare(); player.play(); syncPlayer()
        analytics.capture(event = "playback_started", properties = mapOf(
            "queue_size" to tracks.size,
            "source_type" to when {
                sourceId == null -> "library"
                sourceId == "mix" -> "mix"
                sourceId.startsWith("playlist:") -> "playlist"
                else -> "collection"
            },
            "shuffled" to start.shuffle,
        ))
    }
    /** Start the visible songs now and extend the queue without restarting the current song. */
    internal fun playSourceCollection(providerId: String, entityId: String, visible: List<Track>,
        index: Int? = null, shuffled: Boolean = false, hasMore: Boolean = false) {
        val sourceId = app.lynli.klang.plugins.MusicSourceProtocol.identity(providerId, entityId)
        play(visible, index, shuffled, sourceId)
        if (!hasMore) return
        collectionQueueJob = viewModelScope.launch {
            try {
                val all = musicSources.collection(providerId, entityId)
                all.forEach { queueTracks[it.id] = it }
                val player = controller ?: return@launch
                if (player.playbackSourceOrNull() != sourceId) return@launch
                // Only append if the source still has the exact visible occurrence order.
                if (all.take(visible.size).map(Track::id) == visible.map(Track::id)) {
                    player.addMediaItems(all.drop(visible.size).map { it.mediaItem().withPlaybackSource(sourceId) })
                } else message("This playlist changed. Refresh it to play the updated order.")
            } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                message(e.message ?: "Couldn't load the rest of this playlist.")
            }
        }
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
        controller?.togglePlayback()
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
    fun enablePlugin(id: String, enabled: Boolean) {
        mutablePlugins.value = pluginStore.enable(id, enabled)
        app.lynli.klang.plugins.ExtensionRuntime.active?.reload()
        SourceStreams.get(getApplication()).invalidate()
        musicSources.resumed()
    }
    fun pluginSetting(id: String, key: String, value: String) {
        mutablePlugins.value = pluginStore.setting(id, key, value)
        app.lynli.klang.plugins.ExtensionRuntime.active?.reload()
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
                if (candidate.manifest.musicImport != null || candidate.manifest.extension != null || candidate.manifest.musicSource != null) {
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
                analytics.capture(event = "plugin_installed", properties = mapOf(
                    "plugin_type" to candidate.manifest.type.name.lowercase(),
                    "operation" to if (existing == null) "installed" else "updated",
                ))
                message(if (existing == null) "${candidate.manifest.name} installed" else "${candidate.manifest.name} updated")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutablePluginCatalog.update { it.copy(error = error.message ?: "Couldn't install this plugin.") } }
            finally { mutablePluginCatalog.update { it.copy(busyId = null) } }
        }
    }
    fun uninstallPlugin(id: String) {
        if (mutablePluginCatalog.value.busyId != null) return
        val pluginType = pluginRegistry.entries.find { it.manifest.id == id }?.manifest?.type?.name?.lowercase() ?: "unknown"
        mutablePluginCatalog.update { it.copy(busyId = id, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { pluginRegistry.uninstall(id) }
                pluginStore.remove(id)
                app.lynli.klang.plugins.ExtensionRuntime.active?.reload()
                mutablePlugins.value = pluginStore.read()
                mutablePluginCatalog.update { it.copy(installed = pluginRegistry.entries, updates = it.updates - id) }
                retryLyrics()
                // Disabling first cancels the in-flight lookup before deleting its cache.
                lyricsRepository.clearCache(id)
                analytics.capture(event = "plugin_uninstalled", properties = mapOf("plugin_type" to pluginType))
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
        queueTracks[track.id] = track
        if (app.lynli.klang.plugins.ExtensionRuntime.active?.routeSelection(listOf(track.mediaItem()), "next") == true) return
        controller?.let {
            if (it.mediaItemCount == 0) { play(listOf(track)); return }
            it.addMediaItem(it.currentMediaItemIndex + 1,
                track.mediaItem().withPlaybackSource(it.playbackSourceOrNull())); message("Added to play next")
        }
    }
    fun queuePlay(index: Int) { controller?.let { it.seekToDefaultPosition(index); it.prepare(); it.play() } }
    fun queueRemove(index: Int) { controller?.removeMediaItem(index) }
    fun favorite(song: Track) {
        val track = if (song.providerId != null) song else mutableState.value.tracks.firstOrNull { it.sameSong(song) } ?: song
        if (track.providerId != null && track.sourceId != null && musicSources.entries().any {
                it.manifest.id == track.providerId && "library.write" in requireNotNull(it.manifest.musicSource).capabilities }) {
            val value = track.identities.none { it in mutableState.value.favorites }
            // Migrate favorites created before source synchronization to provider-backed likes.
            prefs.edit().putStringSet("favorites", prefs.getStringSet("favorites", emptySet()).orEmpty() - track.identities).apply()
            favoriteOverrides[track.id] = value
            favoriteAccounts[track.id] = track.providerId to musicSources.accountRevision(track.providerId)
            favoriteJobs[track.id]?.cancel()
            mutableState.update { it.copy(favorites = if (value) it.favorites + track.identities else it.favorites - track.identities) }
            favoriteJobs[track.id] = viewModelScope.launch {
                try {
                    musicSources.call(track.providerId, if (value) "library.save" else "library.remove",
                        org.json.JSONObject().put("ids", org.json.JSONArray().put(track.sourceId)).apply {
                            track.sourcePayload?.let { payload -> org.json.JSONObject(payload).optString("accountRevision")
                                .takeIf { it.isNotBlank() }?.let { put("accountRevision", it) } }
                        })
                    musicSources.requestSync()
                } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                    favoriteOverrides.remove(track.id)
                    applySourceLibrary(musicSources.library.value)
                    message(e.message ?: "Couldn't update your favorites. Try again.")
                }
            }
            return
        }
        val updated = mutableState.value.favorites.toMutableSet().apply { if (!add(track.id)) remove(track.id) }.toSet()
        val saved = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
        prefs.edit().putStringSet("favorites", if (track.id in updated) saved + track.id else saved - track.id).apply()
        mutableState.update { it.copy(favorites = updated) }
        analytics.capture(event = "favorite_toggled", properties = mapOf("is_favorite" to (track.id in updated)))
    }
    fun createPlaylist(name: String): String? {
        if (name.trim().isEmpty()) return null
        val playlist = playlistStore.create(name)
        updatePlaylists { it + playlist }
        analytics.capture(event = "playlist_created")
        return playlist.id
    }
    fun renamePlaylist(id: String, name: String) {
        if (name.trim().isNotEmpty()) editPlaylist(id) { it.copy(name = name.trim()) }
    }
    fun deletePlaylist(id: String) {
        val cover = mutableState.value.playlists.find { it.id == id }?.coverFile
        updatePlaylists { items -> items.filterNot { it.id == id } }
        analytics.capture(event = "playlist_deleted")
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
    fun addToPlaylist(id: String, tracks: List<Track>) {
        editPlaylist(id) { playlist ->
            playlist.add(tracks.filter { track -> track.identities.none { it in playlist.trackIds } }.map(Track::id))
        }
    }
    fun removeFromPlaylist(id: String, trackId: String) {
        editPlaylist(id) { playlist ->
            val identities = mutableState.value.tracks.find { trackId in it.identities }?.identities ?: setOf(trackId)
            playlist.copy(trackIds = playlist.trackIds.filterNot { it in identities })
        }
    }
    fun reorderPlaylist(id: String, orderedIds: List<String>) {
        editPlaylist(id) { playlist ->
            val aliases = mutableState.value.tracks.associate { it.id to it.identities }
            playlist.reorder(orderedIds.map { canonical -> playlist.trackIds.firstOrNull { it in aliases[canonical].orEmpty() } ?: canonical })
        }
    }
    fun moveInPlaylist(id: String, trackId: String, offset: Int) {
        val available = mutableState.value.tracks.flatMap { it.identities }.toSet()
        editPlaylist(id) { playlist ->
            val visible = playlist.trackIds.filter { it in available }
            val aliases = mutableState.value.tracks.find { trackId in it.identities }?.identities ?: setOf(trackId)
            val actual = visible.firstOrNull { it in aliases } ?: trackId
            val index = visible.indexOf(actual)
            val target = index + offset
            if (index < 0 || target !in visible.indices) playlist
            else playlist.move(actual, playlist.trackIds.indexOf(visible[target]) - playlist.trackIds.indexOf(actual))
        }
    }
    private fun editPlaylist(id: String, edit: (SavedPlaylist) -> SavedPlaylist) {
        updatePlaylists { items -> items.map { if (it.id == id) edit(it) else it } }
    }
    private fun updatePlaylists(edit: (List<SavedPlaylist>) -> List<SavedPlaylist>) {
        val playlists = playlistStore.update(edit)
        mutableState.update { it.copy(playlists = playlists) }
        viewModelScope.launch { applySourceLibrary(musicSources.library.value) }
    }
    fun theme(value: String) { prefs.edit().putString("theme", value).apply(); mutableState.update { it.copy(theme = value) } }
    fun coverColor(value: Boolean) { prefs.edit().putBoolean("contentColors", value).apply(); mutableState.update { it.copy(coverColor = value) } }
    fun dynamicColor(value: Boolean) { prefs.edit().putBoolean("dynamicColor", value).apply(); mutableState.update { it.copy(dynamicColor = value) } }
    fun appIcon(icon: AppIcon) {
        AppIconSwitcher.apply(getApplication(), icon)
        prefs.edit().putString("appIcon", icon.storageValue).apply()
        mutableState.update { it.copy(appIcon = icon) }
    }
    fun sleep(minutes: Int) {
        sleepJob?.cancel()
        val duration = minutes.coerceIn(0, 600)
        val endsAt = if (duration > 0) System.currentTimeMillis() + duration * 60_000L else 0
        mutableState.update { it.copy(sleepMinutes = duration, sleepEndsAt = endsAt,
            sleepRemaining = if (endsAt > 0) duration * 60_000L else 0) }
        analytics.capture(event = "sleep_timer_set", properties = mapOf(
            "duration_minutes" to duration,
            "is_active" to (duration > 0),
        ))
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
    private fun track(item: MediaItem): Track {
        val state = mutableState.value
        if (indexedTracks !== state.tracks || indexedHidden !== state.hiddenTracks) {
            indexedTracks = state.tracks; indexedHidden = state.hiddenTracks
            trackIndex = (state.tracks + state.hiddenTracks + deviceTracks).byIdentity()
        }
        trackIndex[item.mediaId]?.let { return if (it.id == item.mediaId) it else it.copy(uri = Uri.parse(item.mediaId), aliases = it.identities) }
        return queueTracks.getOrPut(item.mediaId) {
            musicSources.storedTrack(item.mediaId) ?: Track(
                app.lynli.klang.plugins.ExtensionRuntime.active?.audioUri(item.mediaId) ?: Uri.parse(item.mediaId),
                item.mediaMetadata.title?.toString() ?: "Untitled", item.mediaMetadata.artist?.toString() ?: "Unknown artist",
                item.mediaMetadata.albumTitle?.toString() ?: "Unknown album", item.mediaMetadata.extras?.getLong("durationMs") ?: 0)
        }
    }
    private fun syncPlayer() {
        val player = controller ?: return
        val previous = mutableState.value
        if (shownQueueRevision != queueRevision || shownLibrary !== previous.tracks || shownHidden !== previous.hiddenTracks || shownQueue.size != player.mediaItemCount) {
            shownQueueRevision = queueRevision; shownLibrary = previous.tracks; shownHidden = previous.hiddenTracks
            shownQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                .filterNot { it.mediaId in deletedAudioIds }.map(::track)
        }
        val source = player.playbackSourceOrNull()
        if (player.isPlaying && (!previous.playing || previous.playbackSource != source)) {
            source?.takeIf { it.startsWith("playlist:") }?.removePrefix("playlist:")?.let { id ->
                if (previous.playlists.any { it.id == id }) editPlaylist(id) { it.copy(lastPlayedAt = System.currentTimeMillis()) }
            }
        }
        mutableState.update { it.copy(connected = true, playbackSource = player.playbackSourceOrNull(),
            current = player.currentMediaItem?.takeUnless { item -> item.mediaId in deletedAudioIds }?.let(::track),
            playWhenReady = player.playWhenReady, hasPreviousTrack = player.hasPreviousMediaItem(), hasNextTrack = player.hasNextMediaItem(),
            buffering = player.playbackState == Player.STATE_BUFFERING,
            queueIndex = player.currentMediaItemIndex, playing = player.isPlaying, position = player.currentPosition.coerceAtLeast(0),
            duration = player.duration.takeIf { value -> value > 0 } ?: player.currentMediaItem?.mediaMetadata?.extras?.getLong("durationMs") ?: 0,
            queue = shownQueue,
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
