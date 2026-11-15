package app.lynli.klang.importing

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.lynli.klang.MainActivity
import app.lynli.klang.R
import app.lynli.klang.data.MusicRepository
import app.lynli.klang.plugins.PluginRegistry
import app.lynli.klang.plugins.PluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** App-owned queue: check the device, invoke any API 1 provider, then publish a tagged file atomically. */
class MusicImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = ImportStore.get(context)
    private val repository = MusicRepository(context)
    private val recordingLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val publicationLock = Mutex()
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        store.recover()
        if (store.batches.value.none { it.pending }) return@withContext Result.success()
        File(applicationContext.cacheDir, "music-imports").deleteRecursively()
        try {
            setForeground(notification())
            coroutineScope {
                val notificationJob = launch { store.batches.collectLatest { setForeground(notification()) } }
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val batch = store.batches.value.firstOrNull { it.pending } ?: break
                        val indices = batch.entries.indices.filter { batch.entries[it].phase == ImportPhase.QUEUED }
                        val next = AtomicInteger(0)
                        List(minOf(3, indices.size)) { async {
                            while (true) { val cursor = next.getAndIncrement(); if (cursor >= indices.size) break; download(batch.id, indices[cursor]) }
                        } }.awaitAll()
                        store.batches.value.find { it.id == batch.id }?.let { DownloadNotifications.finished(applicationContext, it) }
                    }
                } finally { notificationJob.cancel() }
            }
            store.flush()
            Result.success()
        } catch (cancelled: CancellationException) { store.flush(); throw cancelled } catch (error: Exception) {
            store.batches.value.filter { it.pending }.forEach { batch -> batch.entries.indices.forEach { index ->
                store.updateEntry(batch.id, index) { if (it.phase.finished) it else it.copy(phase = ImportPhase.FAILED, error = error.message) }
            } }
            store.batches.value.forEach { DownloadNotifications.finished(applicationContext, it) }
            Result.failure()
        }
    }
    private suspend fun download(batchId: String, index: Int) {
        val song = store.batches.value.find { it.id == batchId }?.entries?.getOrNull(index)?.song ?: return
        val key = "${normalizedRecording(song.artist)}:${normalizedRecording(song.title)}"
        recordingLocks.getOrPut(key) { Mutex() }.withLock { downloadOne(batchId, index) }
    }
    private suspend fun downloadOne(batchId: String, index: Int) {
        val batch = store.batches.value.find { it.id == batchId } ?: return
        val entry = batch.entries.getOrNull(index) ?: return
        if (entry.phase != ImportPhase.QUEUED) return
        val process = ImportStore.processId(batchId, index)
        val directory = File(applicationContext.cacheDir, "music-imports/$batchId/$index").apply { mkdirs() }
        try {
            store.updateEntry(batchId, index) { it.copy(phase = ImportPhase.MATCHING) }
            if (!entry.allowDuplicate) {
                duplicateRecording(entry.song, repository.tracks())?.let { duplicate ->
                    store.updateEntry(batchId, index) { it.copy(phase = ImportPhase.DUPLICATE, duplicateUri = duplicate.id) }
                    return
                }
            }
            val registry = PluginRegistry(applicationContext)
            val enabled = PluginStore(applicationContext) { registry.manifests }.read().enabled
            val provider = registry.entries.firstOrNull { it.manifest.id == batch.providerId && it.manifest.id in enabled }
                ?: error("Install and enable this download plugin in Settings → Plugins.")
            val output = File(directory, "download.bin")
            val result = ImportProviderClient.download(applicationContext, provider, entry.song, output, process) { progress ->
                store.updateEntry(batchId, index, persist = false) { current ->
                    if (!current.phase.active || current.phase == ImportPhase.SAVING) current else current.copy(
                    phase = when(progress.optString("phase")) {
                        "downloading" -> ImportPhase.DOWNLOADING; "tagging" -> ImportPhase.TAGGING; else -> ImportPhase.MATCHING
                    }, progress = progress.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
                    audioFormat = progress.stringOrNull("audioFormat") ?: current.audioFormat, etaSeconds = progress.optLong("eta", -1)) }
            }
            currentCoroutineContext().ensureActive()
            val extension = result.getString("extension")
            require(extension in setOf("mp3", "m4a", "opus", "ogg", "flac")) { "The plugin returned an unsupported file." }
            val audio = File(directory, "audio.$extension")
            require(output.renameTo(audio)) { "Couldn't save the downloaded file." }
            val metadata = result.optJSONObject("song")?.let(ImportCodec::readSong) ?: entry.song
            // Serialize the final check and publication even if a provider corrected the title/artist.
            publicationLock.withLock {
                if (!entry.allowDuplicate) {
                    duplicateRecording(metadata, repository.tracks())?.let { duplicate ->
                        store.updateEntry(batchId, index) { it.copy(song = metadata, phase = ImportPhase.DUPLICATE, duplicateUri = duplicate.id) }
                        return@withLock
                    }
                }
                store.updateEntry(batchId, index) { it.copy(song = metadata, recording = result.stringOrNull("recording")) }
                if (!store.beginSaving(batchId, index)) return@withLock
                withContext(NonCancellable) { val track = repository.importDownload(audio); store.complete(batchId, index, track.id) }
            }
        } catch (timeout: TimeoutCancellationException) {
            ImportProviderClient.stop(process)
            store.updateEntry(batchId, index) { it.copy(phase = ImportPhase.FAILED, error = "The download took too long. Retry this song.") }
        } catch (cancelled: CancellationException) {
            ImportProviderClient.stop(process)
            if (store.batches.value.find { it.id == batchId }?.entries?.getOrNull(index)?.phase != ImportPhase.CANCELLED) throw cancelled
        } catch (error: Exception) {
            store.updateEntry(batchId, index) { it.copy(phase = ImportPhase.FAILED, error = error.message ?: "Download failed.") }
        } finally { directory.deleteRecursively() }
    }
    private fun notification(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("music_downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(applicationContext, 71, Intent(applicationContext, MainActivity::class.java)
            .putExtra("showMusicImports", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pending = store.batches.value.filter { it.pending }
        val notification = NotificationCompat.Builder(applicationContext, "music_downloads")
            .setSmallIcon(R.drawable.ic_import_notification).setContentTitle("Downloading music")
            .setContentText("${pending.sumOf { it.complete }} downloaded").setContentIntent(intent)
            .setProgress(100, (downloadProgress(pending.flatMap { it.entries }) * 100).toInt(), pending.all { it.progress <= 0f })
            .setSilent(true).setLocalOnly(true).setOnlyAlertOnce(true).setOngoing(true).build()
        return ForegroundInfo(71, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
