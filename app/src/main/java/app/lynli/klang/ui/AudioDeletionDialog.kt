package app.lynli.klang.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel

/** Keep Android's deletion result outside the song row, which disappears when the file is deleted. */
@Composable
internal fun AudioDeletionDialog(state: MusicState, model: MusicViewModel) {
    val request by model.audioDeletion.collectAsStateWithLifecycle()
    var systemTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val id = systemTrackId
        systemTrackId = null
        if (id != null) model.audioDeletionResult(id, result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(request?.confirmation) {
        val deletion = request ?: return@LaunchedEffect
        val confirmation = deletion.confirmation ?: return@LaunchedEffect
        if (systemTrackId != null) return@LaunchedEffect
        systemTrackId = deletion.track.id
        try { launcher.launch(IntentSenderRequest.Builder(confirmation).build()) }
        catch (error: Exception) {
            systemTrackId = null
            model.audioDeletionFailed(error)
        }
    }
    request?.takeUnless { it.deleting }?.let { deletion ->
        ContentTheme(state.coverColor, deletion.track) {
            AlertDialog(onDismissRequest = model::cancelAudioDeletion,
                shape = MaterialTheme.shapes.extraLarge,
                icon = { Icon(Icons.Rounded.Delete, null) },
                title = { Text("Delete audio file?") },
                text = { Text("Permanently delete “${deletion.track.title}” from your device and remove it from your library and playlists?") },
                dismissButton = { TextButton(model::cancelAudioDeletion) { Text("Cancel") } },
                confirmButton = {
                    TextButton(model::confirmAudioDeletion,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                })
        }
    }
}
