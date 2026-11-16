package app.lynli.klang.importing

import android.content.Context
import app.lynli.klang.data.MusicRepository
import app.lynli.klang.plugins.InstalledPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class ImportUiState(val resolving: Boolean = false, val preview: ImportPreview? = null,
    val error: String? = null, val starting: Boolean = false)
internal class ImportController(private val context: Context, private val scope: CoroutineScope,
    private val providers: () -> List<InstalledPlugin>) {
    val store = ImportStore.get(context)
    private val mutable = MutableStateFlow(ImportUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var processId: String? = null
    fun hasProvider() = providers().isNotEmpty()
    fun resolve(raw: String) {
        clear()
        val link = try { ImportLink.parse(raw) } catch (error: Exception) { mutable.value = ImportUiState(error = error.message); return }
        val provider = providers().firstOrNull { link.supportedBy(it) }
        if (provider == null) { mutable.value = ImportUiState(error = "Install or enable a plugin that supports this link."); return }
        val id = "preview-${UUID.randomUUID()}"
        processId = id
        mutable.value = ImportUiState(resolving = true)
        job = scope.launch {
            try { mutable.value = ImportUiState(preview = ImportProviderClient.resolve(context, provider, link, id)) } catch (timeout: TimeoutCancellationException) { mutable.value = ImportUiState(error = "Reading this link took too long. Try again.") } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { mutable.value = ImportUiState(error = error.message ?: "Couldn't read this link.") } finally { ImportProviderClient.stop(id) }
        }
    }
    fun clear() {
        job?.cancel(); processId?.let(ImportProviderClient::stop); processId = null
        mutable.value = ImportUiState()
    }
    suspend fun start(selected: Set<Int>): Boolean {
        val preview = mutable.value.preview ?: return false
        mutable.value = mutable.value.copy(starting = true, error = null)
        return try {
            val songs = preview.songs.filterIndexed { index, _ -> index in selected }
            val source = providers().firstOrNull { it.manifest.id == preview.providerId && it.manifest.musicSource != null }
            val media = app.lynli.klang.plugins.SourceMediaStore.get(context)
            val tracks = MusicRepository(context).tracks()
            val entries = songs.distinctBy { it.sourceId }.map { song ->
                if (source != null) withContext(Dispatchers.IO) { media.track(source.manifest, org.json.JSONObject(song.payload)) }
                val duplicate = duplicateRecording(song, tracks)
                if (source != null && duplicate != null) media.markOffline(source.manifest.id, song.sourceId, duplicate.id)
                ImportEntry(song, if (duplicate == null) ImportPhase.QUEUED else ImportPhase.DUPLICATE, duplicateUri = duplicate?.id)
            }
            store.enqueue(preview, entries)
            clear()
            true
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { mutable.value = mutable.value.copy(starting = false, error = error.message); false }
    }
}
