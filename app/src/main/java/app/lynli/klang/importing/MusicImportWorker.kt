package app.lynli.klang.importing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.lynli.klang.MainActivity
import app.lynli.klang.R
import app.lynli.klang.data.MusicRepository
import app.lynli.klang.data.PlaylistStore
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext

/** Three downloads, fragmented transfers, and stream-copy tagging; never re-encode audio. */
class MusicImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = ImportStore.get(context)
    private val repository = MusicRepository(context)
    private val playlists = PlaylistStore(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        store.recover()
        // One unique worker owns this cache. Remove leftovers from a previous process death.
        File(applicationContext.cacheDir, "music-imports").deleteRecursively()
        if (store.batches.value.none { it.pending }) return@withContext Result.success()
        try {
            setForeground(notification())
            runInterruptible {
                ImportEngine.initialize(applicationContext)
                ImportEngine.refreshExtractor(applicationContext)
            }
            coroutineScope {
                val notificationJob = launch {
                    store.batches.collectLatest {
                        // Progress callbacks are already throttled; notification updates stay lightweight.
                        setForeground(notification())
                    }
                }
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val batch = store.batches.value.firstOrNull { it.pending } ?: break
                        val indices = batch.entries.indices.filter { batch.entries[it].phase == ImportPhase.QUEUED }
                        val next = AtomicInteger(0)
                        List(minOf(3, indices.size)) {
                            async {
                                while (true) {
                                    val cursor = next.getAndIncrement()
                                    if (cursor >= indices.size) break
                                    download(batch.id, indices[cursor])
                                }
                            }
                        }.awaitAll()
                        syncPlaylist(batch.id)
                    }
                } finally { notificationJob.cancel() }
            }
            store.flush()
            Result.success()
        } catch (cancelled: CancellationException) {
            // WorkManager will restart unfinished work when its constraints are met again.
            store.batches.value.filter { it.pending }.forEach { batch ->
                batch.entries.indices.forEach { ImportEngine.stop(ImportStore.processId(batch.id, it)) }
            }
            store.flush()
            throw cancelled
        } catch (error: LinkageError) {
            failPending(ImportEngineException(error))
            Result.failure()
        } catch (error: Exception) {
            failPending(error)
            Result.failure()
        }
    }

    private fun failPending(error: Exception) {
        android.util.Log.e("KlangImport", "Music import failed", error)
        store.batches.value.filter { it.pending }.forEach { batch ->
            batch.entries.indices.filter { !batch.entries[it].phase.finished }.forEach { index ->
                store.updateEntry(batch.id, index) { it.copy(phase = ImportPhase.FAILED, error = importError(error)) }
            }
        }
    }

    private suspend fun download(batchId: String, index: Int) {
        val processId = ImportStore.processId(batchId, index)
        val entry = store.batches.value.find { it.id == batchId }?.entries?.getOrNull(index) ?: return
        if (entry.phase != ImportPhase.QUEUED) return
        val directory = File(applicationContext.cacheDir, "music-imports/$batchId/$index").apply { mkdirs() }
        try {
            val previous = store.batches.value.flatMap { it.entries }.firstOrNull {
                it.song.sourceId == entry.song.sourceId && it.importRevision >= IMPORT_REVISION &&
                    it.phase == ImportPhase.COMPLETE && it.uri != null &&
                    runCatching { applicationContext.contentResolver.openFileDescriptor(Uri.parse(it.uri), "r")?.use { true } == true }.getOrDefault(false)
            }
            if (previous != null) {
                store.updateEntry(batchId, index) { it.copy(song = previous.song, recording = previous.recording,
                    audioFormat = previous.audioFormat, importRevision = previous.importRevision) }
                store.complete(batchId, index, requireNotNull(previous.uri))
                return
            }
            store.updateEntry(batchId, index) { it.copy(phase = ImportPhase.MATCHING) }
            val (song, catalog) = kotlinx.coroutines.withTimeout(45_000) {
                coroutineScope {
                    val metadata = async {
                        kotlinx.coroutines.withTimeoutOrNull(6_000) { SpotifyMetadata.enrich(entry.song) } ?: entry.song
                    }
                    val matches = async { if (entry.song.spotify) YouTubeCatalog.searchOrEmpty(entry.song) else emptyList() }
                    metadata.await() to matches.await()
                }
            }
            currentCoroutineContext().ensureActive()
            checkNotCancelled(batchId, index)
            store.updateEntry(batchId, index) { it.copy(song = song) }
            val info = kotlinx.coroutines.withTimeout(120_000) {
                if (song.spotify) {
                    findRecording(song, catalog, searchVideos = {
                        checkNotCancelled(batchId, index)
                        runInterruptible {
                            ImportEngine.info("ytsearch10:${song.artist} ${song.title} official audio", processId, flat = true)
                                .optJSONArray("entries")?.objects().orEmpty()
                        }
                    }, extract = { url ->
                        checkNotCancelled(batchId, index)
                        runInterruptible { ImportEngine.info(url, processId) }
                    })
                } else runInterruptible { ImportEngine.info(song.url, processId) }
            }
            currentCoroutineContext().ensureActive()
            checkNotCancelled(batchId, index)
            val metadata = if (song.spotify) song.copy(album = song.album.ifBlank { info.optString("album") },
                artwork = song.artwork ?: thumbnail(info))
            else {
                val linked = youtubeSong(info, info.getString("id"))
                if (linked.album.isBlank() && linked.artist.isNotBlank()) {
                    catalogMetadata(linked, YouTubeCatalog.searchOrEmpty(linked))
                } else linked
            }
            currentCoroutineContext().ensureActive()
            checkNotCancelled(batchId, index)
            val audioFormat = selectOriginalAudio(info)
            store.updateEntry(batchId, index) { it.copy(song = metadata, recording = song.url.takeUnless { song.spotify }
                ?: info.stringOrNull("webpage_url"), audioFormat = audioFormat.description,
                importRevision = IMPORT_REVISION, phase = ImportPhase.DOWNLOADING) }
            tagInfo(info, metadata)
            val infoFile = File(directory, "source.json").apply { writeText(info.toString()) }
            val request = ImportEngine.request().apply {
                // The saved extractor result avoids a second extraction request.
                addOption("--load-info-json", infoFile.absolutePath)
                addOption("--no-playlist")
                // Only codecs that ExtractAudio can stream-copy. Unsupported audio fails rather than transcoding.
                addOption("-f", audioFormat.id)
                addOption("--postprocessor-args", STREAM_COPY_ARGUMENTS)
                addOption("--extract-audio")
                addOption("--audio-format", "best")
                addOption("--embed-metadata")
                addOption("--embed-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption("--concurrent-fragments", 8)
                // yt-dlp also chunks direct HTTP streams; fragment concurrency is for DASH/HLS only.
                addOption("--http-chunk-size", "1M")
                addOption("--fragment-retries", 5)
                addOption("--abort-on-unavailable-fragments")
                addOption("--newline")
                addOption("--no-mtime")
                addOption("-o", File(directory, "audio.%(ext)s").absolutePath)
            }
            var lastUpdate = 0L
            runInterruptible {
                YoutubeDL.execute(request, processId) { percent, eta, line ->
                    val now = System.currentTimeMillis()
                    val tagging = listOf("[ExtractAudio]", "[Metadata]", "[EmbedThumbnail]").any { line.startsWith(it) }
                    if (now - lastUpdate >= 300 || tagging) {
                        lastUpdate = now
                        val speed = Regex(" at (\\S+/s)").find(line)?.groupValues?.get(1)
                        store.updateEntry(batchId, index, persist = false) { current ->
                            if (current.phase !in listOf(ImportPhase.DOWNLOADING, ImportPhase.TAGGING)) current else current.copy(phase = if (tagging || current.phase == ImportPhase.TAGGING) ImportPhase.TAGGING else ImportPhase.DOWNLOADING,
                                progress = if (percent >= 0) (percent / 100).coerceIn(0f, 1f) else current.progress,
                                speed = speed ?: current.speed, etaSeconds = eta)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            checkNotCancelled(batchId, index)
            val audio = directory.listFiles()?.firstOrNull {
                it.name.startsWith("audio.") && it.extension in listOf("m4a", "opus", "ogg", "flac", "mp3") && it.length() > 0
            } ?: error("No playable audio file was downloaded.")
            if (!store.beginSaving(batchId, index)) return
            // Finish this short atomic publication even if WorkManager loses its network constraint.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val track = repository.importDownload(audio)
                store.complete(batchId, index, track.id)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            store.updateEntry(batchId, index) { it.copy(phase = ImportPhase.FAILED,
                error = "Finding this recording took too long. Try again.", speed = null, etaSeconds = -1) }
        } catch (error: LinkageError) {
            store.updateEntry(batchId, index) { it.copy(phase = ImportPhase.FAILED,
                error = importError(ImportEngineException(error)), speed = null, etaSeconds = -1) }
        } catch (cancelled: CancellationException) {
            ImportEngine.stop(processId)
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("KlangImport", "Couldn't import ${entry.song.sourceId}", error)
            store.updateEntry(batchId, index) {
                if (it.phase == ImportPhase.QUEUED) it else it.copy(phase = ImportPhase.FAILED,
                    error = importError(error), speed = null, etaSeconds = -1)
            }
        } finally {
            // Failed attempts must not accumulate audio, artwork or extractor JSON in app storage.
            directory.deleteRecursively()
        }
    }

    private fun checkNotCancelled(batchId: String, index: Int) {
        if (store.batches.value.find { it.id == batchId }?.entries?.getOrNull(index)?.phase == ImportPhase.CANCELLED)
            throw YoutubeDL.CanceledException()
    }

    private fun syncPlaylist(batchId: String) {
        val batch = store.batches.value.find { it.id == batchId } ?: return
        val id = batch.playlistId ?: return
        val ordered = batch.entries.mapNotNull { it.uri }.distinct()
        playlists.update { items -> items.map { playlist -> if (playlist.id == id) {
            playlist.copy(trackIds = (ordered + playlist.trackIds).distinct())
        } else playlist } }
    }

    private fun notification(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Music imports", NotificationManager.IMPORTANCE_LOW))
        val batches = store.batches.value
        val complete = batches.sumOf { it.complete }
        val total = batches.sumOf { it.entries.size }
        val intent = PendingIntent.getActivity(applicationContext, 71,
            Intent(applicationContext, MainActivity::class.java).putExtra("showMusicImports", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = if (batches.isEmpty()) 0 else (batches.map { it.progress }.average() * 100).toInt()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_import_notification).setContentTitle("Adding music to Klang")
            .setContentText("$complete of $total songs added").setContentIntent(intent)
            .setProgress(100, progress, total == 0).setOnlyAlertOnce(true).setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
        return ForegroundInfo(71, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val CHANNEL = "music_imports"
        private const val IMPORT_REVISION = 1
    }
}
