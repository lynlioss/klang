@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.R
import app.lynli.klang.importing.*
import kotlinx.coroutines.launch

@Composable
internal fun MusicImportStatus(controller: ImportController, open: () -> Unit, modifier: Modifier = Modifier) {
    val batches by controller.store.batches.collectAsStateWithLifecycle()
    val pending = batches.filter { it.pending }
    AnimatedVisibility(batches.isNotEmpty(), modifier) {
        val complete = batches.sumOf { it.complete }
        val failed = batches.sumOf { it.failed }
        Surface(onClick = open, shape = MaterialTheme.shapes.medium,
            color = if (pending.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (pending.isNotEmpty()) LoadingIndicator(Modifier.size(32.dp))
                    else Icon(if (failed > 0) Icons.Rounded.Refresh else Icons.Rounded.Check, null)
                    Column(Modifier.weight(1f)) {
                        Text(if (pending.isNotEmpty()) "Adding your music" else if (failed > 0) "Some songs need attention" else "Music added",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(if (pending.isNotEmpty()) "$complete added · ${pending.sumOf { it.entries.count { entry -> !entry.phase.finished } }} remaining"
                            else "$complete added${if (failed > 0) " · $failed to retry" else " · View imports"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.KeyboardArrowRight, "View music imports")
                }
                if (pending.isNotEmpty()) LinearProgressIndicator(
                    progress = { pending.map { it.progress }.average().toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun MusicImportSheet(controller: ImportController, localFiles: () -> Unit, dismiss: () -> Unit,
    createPlaylist: (() -> Unit)? = null, initialLink: String? = null) {
    val ui by controller.state.collectAsStateWithLifecycle()
    val batches by controller.store.batches.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf(initialLink.orEmpty()) }
    var showDownloads by rememberSaveable { mutableStateOf(initialLink == null && batches.isNotEmpty() && ui.preview == null) }
    var selected by remember(ui.preview) { mutableStateOf(ui.preview?.songs?.indices?.toSet().orEmpty()) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, shape = KlangSheetShape,
        containerColor = colors.surfaceContainerLow) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (showDownloads) "Your imports" else "Add music", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold)
                    Text(if (showDownloads) "Keep listening. We’ll handle the rest." else "Bring your favorites with you.",
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                UtilityIconButton(dismiss) { Icon(Icons.Rounded.Close, "Close add music") }
            }
            if (batches.isNotEmpty()) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showDownloads, { showDownloads = false }, { Text("Add music") }, shapes = klangFilterChipShapes())
                FilterChip(showDownloads, { showDownloads = true }, { Text("Imports · ${batches.sumOf { it.entries.size }}") },
                    shapes = klangFilterChipShapes())
            }
            AnimatedContent(showDownloads, label = "Import page", modifier = Modifier.weight(1f, fill = false)) { downloads ->
                if (downloads) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(batches, key = { it.id }) { batch -> ImportBatchCard(batch, controller) }
                        if (batches.isEmpty()) item {
                            EmptyState("All caught up", "Add a song or playlist to start an import.")
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).animateContentSize()) {
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SourcePill(R.drawable.spotify, "Spotify")
                            SourcePill(R.drawable.youtube_music, "YouTube Music")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(link, { value -> link = value; if (!ui.resolving) controller.clear() },
                            enabled = !ui.resolving, singleLine = true, shape = KlangFieldShape,
                            label = { Text("Song or playlist link") }, placeholder = { Text("Paste a shared music link") },
                            isError = ui.error != null, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) controller.resolve(link) }),
                            trailingIcon = {
                                IconButton({ scope.launch {
                                    clipboard.getClipEntry()?.clipData?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                                        link = it; controller.clear()
                                    }
                                } }, enabled = !ui.resolving) { Icon(ImageVector.vectorResource(R.drawable.content_paste), "Paste music link") }
                            })
                        ui.error?.let { Text(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = colors.error, style = MaterialTheme.typography.bodySmall) }
                        if (ui.resolving) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                LoadingIndicator(Modifier.size(40.dp))
                                Text("Finding your music…", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(controller::clear) { Text("Cancel") }
                            }
                        } else if (ui.preview == null) {
                            Button({ controller.resolve(link) }, enabled = link.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 56.dp)) {
                                Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Find music")
                            }
                        }
                        ui.preview?.let { preview ->
                            Column(Modifier.padding(top = 16.dp)) {
                                Text(preview.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${preview.songs.size} ${if (preview.songs.size == 1) "song" else "songs"} · ${preview.link.source.label}",
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    if (preview.songs.size > 1) TextButton({ selected = if (selected.size == preview.songs.size) emptySet()
                                        else preview.songs.indices.toSet() }) { Text(if (selected.size == preview.songs.size) "Clear" else "Select all") }
                                }
                                preview.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(preview.songs.size, key = { it }) { index ->
                                        val song = preview.songs[index]
                                        Surface(onClick = { selected = if (index in selected) selected - index else selected + index },
                                            shape = MaterialTheme.shapes.small, color = colors.surfaceContainerHigh) {
                                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Checkbox(index in selected, { checked -> selected = if (checked) selected + index else selected - index })
                                            }
                                        }
                                    }
                                }
                                Button({ controller.start(selected); if (controller.state.value.preview == null) {
                                    link = ""; showDownloads = true
                                } }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 56.dp)) {
                                    Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp))
                                    Text("Add ${selected.size} ${if (selected.size == 1) "song" else "songs"}")
                                }
                            }
                        }
                        Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = MaterialTheme.shapes.medium,
                            color = colors.secondaryContainer) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Rounded.GraphicEq, null, tint = colors.onSecondaryContainer)
                                Column {
                                    Text("Best available audio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                        color = colors.onSecondaryContainer)
                                    Text("Original audio quality, with tags and cover art. No upscaling or audio conversion.",
                                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
                                    Text("Spotify songs are matched to recordings on YouTube Music.", Modifier.padding(top = 6.dp),
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ localFiles(); dismiss() }, Modifier.weight(1f)) {
                                Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Audio files")
                            }
                            createPlaylist?.let { create -> TextButton({ dismiss(); create() }, Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("New playlist")
                            } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePill(icon: Int, label: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(icon), null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ImportBatchCard(batch: ImportBatch, controller: ImportController) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var expanded by rememberSaveable(batch.id) { mutableStateOf(batch.pending || batch.failed > 0) }
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(batch.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${batch.complete} of ${batch.entries.size} added · ${batch.source.label}",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                IconButton({ expanded = !expanded }) { Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    if (expanded) "Hide song details" else "Show song details") }
            }
            if (batch.pending) LinearProgressIndicator(progress = { batch.progress }, modifier = Modifier.fillMaxWidth())
            AnimatedVisibility(expanded) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(batch.entries) { entry ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(when (entry.phase) {
                                    ImportPhase.COMPLETE -> Icons.Rounded.Check
                                    ImportPhase.FAILED -> Icons.Rounded.Refresh
                                    ImportPhase.CANCELLED -> Icons.Rounded.Close
                                    else -> Icons.Rounded.MusicNote
                                }, null, Modifier.size(18.dp), tint = if (entry.phase == ImportPhase.FAILED) colors.error else colors.onSurfaceVariant)
                                Text(entry.song.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (entry.phase == ImportPhase.DOWNLOADING) Text("${(entry.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            Text(entry.error ?: buildString {
                                append(entry.phase.label)
                                entry.speed?.takeIf { entry.phase == ImportPhase.DOWNLOADING }?.let { append(" · $it") }
                                if (entry.phase == ImportPhase.DOWNLOADING && entry.etaSeconds >= 0) append(" · ${entry.etaSeconds}s left")
                            }, Modifier.padding(start = 26.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall,
                                color = if (entry.phase == ImportPhase.FAILED) colors.error else colors.onSurfaceVariant)
                            entry.recording?.let { url ->
                                TextButton({ runCatching { uriHandler.openUri(url) } },
                                    modifier = Modifier.padding(start = 18.dp)) { Text("View recording") }
                            }
                            if (entry.phase == ImportPhase.DOWNLOADING) LinearProgressIndicator(progress = { entry.progress },
                                modifier = Modifier.fillMaxWidth().padding(start = 26.dp, top = 6.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (batch.pending) TextButton({ controller.store.cancel(batch.id) }) { Text("Cancel import") }
                else {
                    TextButton({ controller.store.dismiss(batch.id) }) { Text("Dismiss") }
                    if (batch.entries.any { it.phase in listOf(ImportPhase.FAILED, ImportPhase.CANCELLED) })
                        FilledTonalButton({ controller.store.retry(batch.id) }) { Text("Retry remaining") }
                }
            }
        }
    }
}
