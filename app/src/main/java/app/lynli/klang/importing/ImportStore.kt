package app.lynli.klang.importing

import android.content.Context
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.lynli.klang.data.PlaylistStore
import app.lynli.klang.data.audioFileIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/** One process-wide journal shared by UI and WorkManager, including after process recreation. */
internal class ImportStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "music-downloads-v2.json"))
    private val mutable = MutableStateFlow(runCatching {
        file.openRead().bufferedReader().use { ImportCodec.decode(it.readText()) }
    }.getOrDefault(emptyList()))
    val batches = mutable.asStateFlow()
    private var persistedAt = 0L

    @Synchronized
    fun enqueue(preview: ImportPreview, selected: List<ImportEntry>): ImportBatch {
        require(selected.isNotEmpty()) { "Select at least one song." }
        val playlists = PlaylistStore(app)
        val playlist = if (preview.collection) playlists.create(preview.title).also { added ->
            playlists.update { it + added }
        } else null
        val batch = ImportBatch(title = preview.title, providerId = preview.providerId,
            entries = selected, playlistId = playlist?.id)
        publish(mutable.value + batch, persist = true)
        schedule()
        return batch
    }

    @Synchronized
    fun updateEntry(batchId: String, index: Int, persist: Boolean = true, edit: (ImportEntry) -> ImportEntry) {
        publish(mutable.value.map { batch -> if (batch.id != batchId) batch else batch.copy(
            entries = batch.entries.mapIndexed { i, entry ->
                if (i == index && entry.phase != ImportPhase.CANCELLED) edit(entry) else entry
            }) }, persist)
    }

    @Synchronized
    fun beginSaving(batchId: String, index: Int): Boolean {
        val phase = mutable.value.find { it.id == batchId }?.entries?.getOrNull(index)?.phase ?: return false
        if (phase.finished) return false
        updateEntry(batchId, index) { it.copy(phase = ImportPhase.SAVING, speed = null, etaSeconds = -1) }
        return true
    }

    @Synchronized
    fun complete(batchId: String, index: Int, uri: String) {
        val batch = mutable.value.find { it.id == batchId } ?: return
        val entries = batch.entries.mapIndexed { i, entry ->
            if (i == index) entry.copy(phase = ImportPhase.COMPLETE, progress = 1f, uri = uri,
                error = null, speed = null, etaSeconds = -1) else entry
        }
        batch.playlistId?.let { playlistId ->
            val ordered = entries.mapNotNull { it.uri }.distinct()
            PlaylistStore(app).update { items -> items.map { playlist ->
                if (playlist.id == playlistId) playlist.copy(trackIds = (ordered + playlist.trackIds).distinct()) else playlist
            } }
        }
        publish(mutable.value.map { if (it.id == batchId) it.copy(entries = entries) else it }, true)
    }

    @Synchronized
    fun cancel(batchId: String) {
        val batch = mutable.value.find { it.id == batchId } ?: return
        publish(mutable.value.map { if (it.id != batchId) it else it.copy(entries = it.entries.map { entry ->
            if (!entry.phase.finished && entry.phase != ImportPhase.SAVING) entry.copy(phase = ImportPhase.CANCELLED,
                speed = null, etaSeconds = -1) else entry
        }) }, true)
        // Kill only this batch's native processes. A publication already in progress can finish.
        batch.entries.indices.filter { batch.entries[it].phase != ImportPhase.SAVING }
            .forEach { ImportProviderClient.stop(processId(batchId, it)) }
    }
    @Synchronized fun cancelEntry(batchId: String, index: Int) {
        if (mutable.value.find { it.id == batchId }?.entries?.getOrNull(index)?.phase == ImportPhase.SAVING) return
        updateEntry(batchId, index) { if (!it.phase.finished && it.phase != ImportPhase.SAVING)
            it.copy(phase = ImportPhase.CANCELLED) else it }
        ImportProviderClient.stop(processId(batchId, index))
    }

    @Synchronized
    fun retry(batchId: String) {
        app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE).edit().remove("finished-$batchId").apply()
        publish(mutable.value.map { if (it.id != batchId) it else it.copy(entries = it.entries.map { entry ->
            if (entry.phase in listOf(ImportPhase.FAILED, ImportPhase.CANCELLED)) entry.copy(
                phase = ImportPhase.QUEUED, progress = 0f, error = null, speed = null, etaSeconds = -1,
                audioFormat = null, recording = null) else entry
        }) }, true)
        schedule()
    }

    /** Requeue every failed song. Deliberately skipped songs stay skipped. */
    @Synchronized
    fun retryAll() {
        val failed = mutable.value.filter { batch -> batch.entries.any { it.phase == ImportPhase.FAILED } }
        if (failed.isEmpty()) return
        val edit = app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE).edit()
        failed.forEach { edit.remove("finished-${it.id}") }
        edit.apply()
        publish(mutable.value.map { batch -> batch.copy(entries = batch.entries.map { entry ->
            if (entry.phase == ImportPhase.FAILED) entry.copy(phase = ImportPhase.QUEUED, progress = 0f,
                error = null, speed = null, etaSeconds = -1, audioFormat = null, recording = null) else entry
        }) }, true)
        schedule()
    }

    @Synchronized
    fun dismiss(batchId: String) {
        publish(mutable.value.filterNot { it.id == batchId && !it.pending }, true)
        if (mutable.value.none { it.id == batchId }) app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE)
            .edit().remove("finished-$batchId").apply()
        File(app.cacheDir, "music-imports/$batchId").deleteRecursively()
    }

    /** Clear the whole download history while leaving active downloads running. */
    @Synchronized
    fun dismissAll() {
        val removed = mutable.value.filter { !it.pending }
        val edit = app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE).edit()
        removed.forEach { edit.remove("finished-${it.id}") }
        publish(mutable.value.mapNotNull { batch ->
            when {
                !batch.pending -> null
                batch.entries.any { it.phase.finished } -> batch.copy(entries = batch.entries.map { entry ->
                    if (entry.phase.finished) entry.copy(phase = ImportPhase.CANCELLED, error = null,
                        speed = null, etaSeconds = -1) else entry
                })
                else -> batch
            }
        }, true)
        edit.apply()
        removed.forEach { File(app.cacheDir, "music-imports/${it.id}").deleteRecursively() }
    }

    /** Apply one decision to every song that is already on this device. */
    @Synchronized
    fun resolveAllDuplicates(download: Boolean) {
        val touched = mutable.value.filter { batch -> batch.entries.any { it.phase == ImportPhase.DUPLICATE } }
        if (touched.isEmpty()) return
        val edit = app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE).edit()
        if (download) touched.forEach { edit.remove("finished-${it.id}") }
        edit.apply()
        publish(mutable.value.map { batch -> batch.copy(entries = batch.entries.map { entry ->
            if (entry.phase != ImportPhase.DUPLICATE) entry else entry.copy(
                phase = if (download) ImportPhase.QUEUED else ImportPhase.CANCELLED,
                allowDuplicate = download, progress = 0f, error = null, speed = null, etaSeconds = -1)
        }) }, true)
        if (download) schedule()
    }

    @Synchronized fun resolveDuplicate(batchId: String, index: Int, download: Boolean) {
        if (download) app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE).edit().remove("finished-$batchId").apply()
        updateEntry(batchId, index) { entry ->
            if (entry.phase != ImportPhase.DUPLICATE) entry else entry.copy(
                phase = if (download) ImportPhase.QUEUED else ImportPhase.CANCELLED, allowDuplicate = download,
                progress = 0f, error = null)
        }
        if (download) schedule()
    }
    @Synchronized fun clearFinished() {
        val removed = mutable.value.filter { !it.pending && !it.warnings }
        publish(mutable.value.filter { it.pending || it.warnings }, true)
        val edit = app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE).edit()
        removed.forEach { edit.remove("finished-${it.id}") }
        edit.apply()
    }

    /** Remove completed songs but keep failures and duplicates around for review. */
    @Synchronized fun clearFinishedEntries() {
        val removed = mutable.value.filter { !it.pending && !it.warnings }
        publish(mutable.value.mapNotNull { batch ->
            if (!batch.pending && !batch.warnings) null else batch.copy(entries = batch.entries.map { entry ->
                if (entry.phase == ImportPhase.COMPLETE) entry.copy(phase = ImportPhase.CANCELLED) else entry
            })
        }, true)
        val edit = app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE).edit()
        removed.forEach { edit.remove("finished-${it.id}") }
        edit.apply()
    }
    @Synchronized fun dismissEntry(batchId: String, index: Int) {
        updateEntry(batchId, index) { if (it.phase.finished) it.copy(phase = ImportPhase.CANCELLED, error = null) else it }
    }
    @Synchronized fun retryEntry(batchId: String, index: Int) {
        app.getSharedPreferences("download_notifications", Context.MODE_PRIVATE).edit().remove("finished-$batchId").apply()
        updateEntry(batchId, index) { if (it.phase == ImportPhase.FAILED)
            it.copy(phase = ImportPhase.QUEUED, progress = 0f, error = null) else it }
        schedule()
    }

    /** A later completion in the same batch must not add a deliberately deleted file back to a playlist. */
    @Synchronized fun forgetAudioFile(uri: String) {
        val ids = audioFileIds(uri)
        publish(mutable.value.map { batch -> batch.copy(entries = batch.entries.map { entry ->
            if (entry.uri in ids) entry.copy(uri = null, phase = ImportPhase.CANCELLED) else entry
        }) }, true)
    }

    @Synchronized
    fun recover() {
        publish(mutable.value.map { it.copy(entries = it.entries.map { entry ->
            if (entry.phase.active) entry.copy(phase = ImportPhase.QUEUED, progress = 0f) else entry
        }) }, true)
    }

    @Synchronized
    fun flush() = publish(mutable.value, true)

    private fun publish(value: List<ImportBatch>, persist: Boolean) {
        mutable.value = value
        val now = System.currentTimeMillis()
        if (!persist && now - persistedAt < 1500) return
        val output = file.startWrite()
        try {
            output.write(ImportCodec.encode(value).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            persistedAt = now
        } catch (error: Exception) { file.failWrite(output); throw error }
    }

    fun schedule() {
        val work = OneTimeWorkRequestBuilder<MusicImportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_NAME).build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    companion object {
        const val WORK_NAME = "klang-music-import"
        @Volatile private var instance: ImportStore? = null
        fun get(context: Context): ImportStore = instance ?: synchronized(this) {
            instance ?: ImportStore(context).also { instance = it }
        }
        fun processId(batchId: String, index: Int) = "music-$batchId-$index"
    }
}
