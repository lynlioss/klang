@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.R
import app.lynli.klang.importing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Indeterminate wavy progress becomes determinate, then a check; unresolved warnings persist. */
@Composable
internal fun DownloadStatusButton(controller: ImportController, open: () -> Unit) {
    val batches by controller.store.batches.collectAsStateWithLifecycle()
    val pending = batches.any { it.pending }
    val warning = batches.any { it.warnings }
    val completed = batches.any { it.complete > 0 }
    val progress = downloadProgress(batches.filter { it.pending }.flatMap { it.entries })
    val motion = motionEnabled()
    val scheme = MaterialTheme.motionScheme
    LaunchedEffect(pending, warning, completed) {
        if (!pending && !warning && completed) { delay(3_000); controller.store.clearFinished() }
        else if (!pending && !warning && batches.isNotEmpty()) controller.store.clearFinished()
    }
    AnimatedVisibility(pending || warning || completed,
        enter = fadeIn(if (motion) scheme.fastEffectsSpec() else snap()) + scaleIn(if (motion) scheme.fastSpatialSpec() else snap()),
        exit = fadeOut(if (motion) scheme.fastEffectsSpec() else snap()) + scaleOut(if (motion) scheme.fastSpatialSpec() else snap())) {
        val description = if (pending) "View music downloads" else if (warning) "Review download warnings" else "Downloads complete"
        IconButton(open, modifier = Modifier.semantics { contentDescription = description }) {
            AnimatedContent(if (pending) 0 else if (warning) 1 else 2, transitionSpec = {
                (fadeIn(if (motion) scheme.fastEffectsSpec() else snap()) + scaleIn(if (motion) scheme.fastSpatialSpec() else snap())) togetherWith
                    (fadeOut(if (motion) scheme.fastEffectsSpec() else snap()) + scaleOut(if (motion) scheme.fastSpatialSpec() else snap()))
            }, label = "Download status") { mode ->
                when(mode) {
                    0 -> DownloadProgress(progress, Modifier.size(28.dp))
                    1 -> Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.tertiary)
                    else -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(progress: Float, modifier: Modifier = Modifier) {
    val motion = motionEnabled()
    val scheme = MaterialTheme.motionScheme
    val value by animateFloatAsState(progress, if (motion) scheme.defaultEffectsSpec() else snap(), label = "Download progress")
    if (progress <= 0f) CircularWavyProgressIndicator(modifier = modifier)
    else CircularWavyProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = modifier)
}

@Composable
internal fun MusicImportSheet(controller: ImportController, localFiles: () -> Unit, dismiss: () -> Unit,
    createPlaylist: (() -> Unit)? = null, initialLink: String? = null, recommended: () -> Unit = {}) {
    val ui by controller.state.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf(initialLink.orEmpty()) }
    var selected by remember(ui.preview) { mutableStateOf(ui.preview?.songs?.indices?.toSet().orEmpty()) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun close(after: () -> Unit = {}) { scope.launch { sheet.hide(); dismiss(); after() } }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, shape = KlangSheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.padding(top = 10.dp)) }) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .imePadding().padding(start = 24.dp, end = 16.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Import music", Modifier.weight(1f), style = MaterialTheme.typography.headlineMediumEmphasized)
                IconButton({ close() }) { Icon(Icons.Rounded.Close, "Close import music") }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(link, { value -> link = value; if (!ui.resolving) controller.clear() },
                enabled = !ui.resolving && !ui.starting, singleLine = true, shape = KlangFieldShape,
                label = { Text("Music link") }, placeholder = { Text("Song, album, EP or playlist") },
                isError = ui.error != null, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) controller.resolve(link) }),
                trailingIcon = {
                    IconButton({ scope.launch {
                        clipboard.getClipEntry()?.clipData?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                            link = it; controller.clear()
                        }
                    } }, enabled = !ui.resolving && !ui.starting) {
                        Icon(ImageVector.vectorResource(R.drawable.content_paste), "Paste music link")
                    }
                })
            ui.error?.let { Text(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!controller.hasProvider()) {
                TextButton({ close(recommended) }, Modifier.padding(top = 8.dp)) { Text("Add a download plugin") }
            }
            if (ui.resolving) Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularWavyProgressIndicator(Modifier.size(28.dp))
                Text("Reading music…", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                TextButton(controller::clear) { Text("Cancel") }
            }
            ui.preview?.let { preview ->
                Text(preview.title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLargeEmphasized,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                preview.notice?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
                if (preview.songs.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${preview.songs.size} songs", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ selected = if (selected.size == preview.songs.size) emptySet() else preview.songs.indices.toSet() }) {
                        Text(if (selected.size == preview.songs.size) "Clear" else "Select all")
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(preview.songs.size) { index ->
                        val song = preview.songs[index]
                        Surface(onClick = { if (!ui.starting) selected = if (index in selected) selected - index else selected + index },
                            shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Checkbox(index in selected, { checked -> selected = if (checked) selected + index else selected - index }, enabled = !ui.starting)
                            }
                        }
                    }
                }
            }
            val height = ButtonDefaults.MediumContainerHeight
            Button({
                if (ui.preview == null) controller.resolve(link)
                else scope.launch { if (controller.start(selected)) close() }
            }, enabled = link.isNotBlank() && !ui.resolving && !ui.starting && (ui.preview == null || selected.isNotEmpty()),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = height),
                shapes = ButtonDefaults.shapesFor(height), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
                if (ui.starting) LoadingIndicator(Modifier.size(24.dp))
                else Text(if (ui.preview == null) "Import music" else "Download ${selected.size} ${if (selected.size == 1) "song" else "songs"}",
                    style = ButtonDefaults.textStyleFor(height))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ close(localFiles) }, Modifier.weight(1f)) { Text("Audio files") }
                createPlaylist?.let { create -> TextButton({ close(create) }, Modifier.weight(1f)) { Text("New playlist") } }
            }
        }
    }
}

@Composable
internal fun DownloadProgressSheet(controller: ImportController, dismiss: () -> Unit) {
    val batches by controller.store.batches.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, shape = KlangSheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.padding(top = 10.dp)) }) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", Modifier.weight(1f), style = MaterialTheme.typography.headlineMediumEmphasized)
                IconButton({ scope.launch { sheet.hide(); dismiss() } }) { Icon(Icons.Rounded.Close, "Close downloads") }
            }
            if (batches.isEmpty()) Text("All done", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 16.dp)) {
                batches.forEach { batch ->
                    if (batch.entries.any { it.phase == ImportPhase.DUPLICATE }) item(key = "warning-${batch.id}") {
                        Text("These songs were found on your device already. Download only if you want another copy.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val visible = batch.entries.indices.filter { index ->
                        val phase = batch.entries[index].phase
                        phase != ImportPhase.CANCELLED && (phase != ImportPhase.COMPLETE || batch.pending || !batch.warnings)
                    }
                    items(visible, key = { "${batch.id}-$it" }) { index ->
                        val entry = batch.entries[index]
                        if (entry.phase != ImportPhase.CANCELLED) Surface(shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    when {
                                        entry.phase == ImportPhase.DUPLICATE || entry.phase == ImportPhase.FAILED -> Icon(Icons.Rounded.Warning, null,
                                            tint = MaterialTheme.colorScheme.tertiary)
                                        entry.phase == ImportPhase.COMPLETE -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        else -> DownloadProgress(if (entry.phase == ImportPhase.QUEUED || entry.phase == ImportPhase.MATCHING) 0f else entry.progress,
                                            Modifier.size(28.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.song.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (!entry.phase.finished && entry.phase != ImportPhase.SAVING) IconButton({ controller.store.cancelEntry(batch.id, index) }) {
                                        Icon(Icons.Rounded.Close, "Cancel ${entry.song.title}")
                                    }
                                }
                                Text(entry.error ?: buildString {
                                    append(entry.phase.label)
                                    if (entry.phase == ImportPhase.DOWNLOADING) append(" · ${(entry.progress * 100).toInt()}%")
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (entry.phase == ImportPhase.DUPLICATE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    FilledTonalButton({ controller.store.resolveDuplicate(batch.id, index, true) }) { Text("Download anyway") }
                                    IconButton({ controller.store.resolveDuplicate(batch.id, index, false) }) { Icon(Icons.Rounded.Close, "Skip ${entry.song.title}") }
                                }
                                if (entry.phase == ImportPhase.FAILED) Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({ controller.store.retryEntry(batch.id, index) }) { Text("Retry") }
                                    TextButton({ controller.store.dismissEntry(batch.id, index) }) { Text("Dismiss") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
