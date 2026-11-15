package app.lynli.klang.importing

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal data class ImportUiState(
    val resolving: Boolean = false, val preview: ImportPreview? = null, val error: String? = null,
)

internal class ImportController(context: Context, private val scope: CoroutineScope) {
    val store = ImportStore.get(context)
    private val resolver = ImportResolver(context)
    private val mutable = MutableStateFlow(ImportUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var processId: String? = null

    fun resolve(raw: String) {
        clear()
        val link = try { ImportLink.parse(raw) } catch (error: Exception) {
            mutable.value = ImportUiState(error = error.message); return
        }
        val id = "music-preview-${UUID.randomUUID()}"
        processId = id
        mutable.value = ImportUiState(resolving = true)
        job = scope.launch {
            try {
                val result = resolver.resolve(link, id)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                mutable.value = ImportUiState(preview = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { mutable.value = ImportUiState(error = importError(error)) }
        }
    }

    fun clear() {
        job?.cancel()
        processId?.let { YoutubeDL.destroyProcessById(it) }
        processId = null
        mutable.value = ImportUiState()
    }

    fun start(selected: Set<Int>) {
        val preview = mutable.value.preview ?: return
        try {
            store.enqueue(preview, preview.songs.filterIndexed { index, _ -> index in selected })
            clear()
        } catch (error: Exception) { mutable.value = mutable.value.copy(error = importError(error)) }
    }
}
