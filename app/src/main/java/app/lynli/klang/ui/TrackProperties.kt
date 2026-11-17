@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.data.Track
import app.lynli.klang.data.TrackDetails
import app.lynli.klang.plugins.SourceTrackStatus
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One Material surface that follows the cover color setting, including the native drag handle. Metadata scrolls independently
 * of the song header and dismissal actions when the window or font size leaves less room.
 */
@Composable
internal fun TrackPropertiesSheet(track: Track, state: MusicState, model: MusicViewModel,
    hide: (() -> Unit)? = null, dismiss: () -> Unit) {
    var details by remember(track.id) { mutableStateOf<TrackDetails?>(null) }
    var readingDetails by remember(track.id) { mutableStateOf(true) }
    LaunchedEffect(track.id) {
        details = try { model.details(track) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        readingDetails = false
    }
    var sourceStatus by remember(track.id) { mutableStateOf<SourceTrackStatus?>(null) }
    var cachedBytes by remember(track.id) { mutableStateOf(0L) }
    var readingSource by remember(track.id) { mutableStateOf(track.providerId != null && !track.offline) }
    LaunchedEffect(track.id) {
        if (track.providerId == null) return@LaunchedEffect
        sourceStatus = try { model.musicSources.status(track) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        cachedBytes = try { model.musicSources.cachedBytes(track) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { 0L }
        readingSource = false
    }
    val playlistNames = remember(state.playlists, track.id) {
        state.playlists.filter { playlist -> track.identities.any { it in playlist.trackIds } }.map { it.name }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember(track.id) { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(dismiss)
    val latestHide by rememberUpdatedState(hide)
    fun close(hideSong: Boolean = false, deleteSong: Boolean = false) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                sheet.hide()
                if (!sheet.isVisible) {
                    if (hideSong) {
                        val action = latestHide
                        if (action != null) action() else { model.hide(track); latestDismiss() }
                    } else {
                        latestDismiss()
                        if (deleteSong) model.requestAudioDeletion(track)
                    }
                }
            } finally { closing = false }
        }
    }
    ContentTheme(state.coverColor, track) {
        val colors = MaterialTheme.colorScheme
        // Klang design choice: match the sleep timer's corners and handle spacing. Keep the theme
        // outside the modal so its container, handle, system insets and content share one surface.
        ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet,
            // Let Material measure against the full window to keep its bottom and swipe
            // anchors correct. Default insets protect the handle and navigation area.
            modifier = Modifier.semantics { paneTitle = "Song details" },
            sheetGesturesEnabled = !closing,
            containerColor = colors.surfaceContainerLow,
            contentColor = colors.onSurface,
            shape = KlangSheetShape,
            dragHandle = { BottomSheetDefaults.DragHandle(Modifier.padding(top = 10.dp)) }) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Cap only the content, after Material has accounted for its handle and insets.
                // This leaves room above a long sheet without changing the window's anchors.
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.9f)) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Artwork(track, Modifier.size(64.dp).clip(MaterialTheme.shapes.large), "${track.title} cover")
                        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                            Text(track.title, Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.album, style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton({ close() }, Modifier.align(Alignment.Top), enabled = !closing) {
                            Icon(Icons.Rounded.Close, "Close song details")
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                        PropertyList(track, details)
                        Spacer(Modifier.height(16.dp))
                        if (track.providerId != null && !track.offline) {
                            SourceProperties(track, sourceStatus, cachedBytes, readingSource)
                        } else {
                            LocationTile(details?.location, readingDetails)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("In playlists", style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant)
                        if (playlistNames.isEmpty()) {
                            Text("Not in a playlist", Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        } else {
                            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                playlistNames.forEach { PlaylistPill(it) }
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val buttonHeight = ButtonDefaults.MediumContainerHeight
                        TextButton(onClick = { close(deleteSong = true) }, enabled = !closing && (track.providerId == null || track.localUri != null),
                            modifier = Modifier.heightIn(min = buttonHeight),
                            shapes = ButtonDefaults.shapesFor(buttonHeight),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
                            Icon(Icons.Rounded.Delete, null, Modifier.size(ButtonDefaults.SmallIconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Delete audio file")
                        }
                        TextButton(onClick = { close(hideSong = true) }, enabled = !closing,
                            modifier = Modifier.heightIn(min = buttonHeight),
                            shapes = ButtonDefaults.shapesFor(buttonHeight)) {
                            Icon(ImageVector.vectorResource(R.drawable.visibility_off), null, Modifier.size(ButtonDefaults.SmallIconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Hide from app")
                        }
                        Button(onClick = { close() }, enabled = !closing, modifier = Modifier.heightIn(min = buttonHeight),
                            shapes = ButtonDefaults.shapesFor(buttonHeight),
                            contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight)) {
                            Text("Done", style = ButtonDefaults.textStyleFor(buttonHeight))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyList(track: Track, details: TrackDetails?) {
    val timerIcon = ImageVector.vectorResource(R.drawable.timer)
    val entries = buildList<Triple<ImageVector, String, String>> {
        add(Triple(timerIcon, "Duration", time(details?.durationMs ?: track.durationMs)))
        add(Triple(Icons.Rounded.Star, "Encoding", details?.quality ?: "—"))
        add(Triple(Icons.Rounded.MusicNote, "Format", details?.format ?: "—"))
        add(Triple(Icons.Rounded.GraphicEq, "Bitrate", details?.bitrateKbps
            ?.let { "${NumberFormat.getIntegerInstance().format(it)} kbps" } ?: "—"))
        // Reserve these rows while file details load so the sheet's height stays stable.
        add(Triple(Icons.Rounded.OfflineBolt, "Sample rate", details?.sampleRateHz?.let(::sampleRateText) ?: "—"))
        add(Triple(Icons.Rounded.Download, "File size", details?.sizeBytes?.let(::formatSize) ?: "—"))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Klang design choice: stack values when scaled text would crowd the two text columns.
        val stackValues = maxWidth / LocalDensity.current.fontScale < 300.dp
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            entries.forEachIndexed { index, (icon, label, value) ->
                SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(index, entries.size),
                    modifier = Modifier.fillMaxWidth(),
                    leadingContent = { Icon(icon, null, Modifier.size(20.dp)) },
                    trailingContent = if (stackValues) null else {
                        { Text(value, style = MaterialTheme.typography.bodyLarge) }
                    },
                    supportingContent = if (stackValues) {
                        { Text(value, style = MaterialTheme.typography.bodyLarge) }
                    } else null,
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        trailingContentColor = MaterialTheme.colorScheme.onSurface,
                        supportingContentColor = MaterialTheme.colorScheme.onSurface)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Streaming songs show where their playback comes from: cached bytes, the source link and the
 * matched YouTube Music recording. Downloading a song replaces this with its file location.
 */
@Composable
private fun SourceProperties(track: Track, status: SourceTrackStatus?, cachedBytes: Long, reading: Boolean) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val sourceUrl = status?.sourceUrl ?: track.shareUrl
    val recordingUrl = status?.recordingUrl
    val recordingLabel = status?.recording?.let { recording ->
        listOf(recording.title, recording.artist).filter { it.isNotBlank() }.joinToString(" · ")
    }?.takeIf { it.isNotBlank() }
    val rows = listOf(
        SourcePropertyRow(Icons.Rounded.OfflineBolt, "Playback cache", cacheValue(cachedBytes),
            if (reading) "Reading playback details…" else statusText(status)),
        SourcePropertyRow(Icons.Rounded.OpenInNew, "${track.sourceLabel} link",
            if (sourceUrl != null) "Open" else "Unavailable"),
        SourcePropertyRow(Icons.Rounded.SmartDisplay, "YouTube Music link",
            if (recordingUrl != null) "Open" else linkValue(status, reading),
            if (recordingUrl != null) recordingLabel else null),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Klang design choice: stack values when scaled text would crowd the two text columns.
        val stackValues = maxWidth / LocalDensity.current.fontScale < 300.dp
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            rows.forEachIndexed { index, row ->
                val url = when (index) { 1 -> sourceUrl; 2 -> recordingUrl; else -> null }
                val detail = if (stackValues) listOfNotNull(row.value, row.support).joinToString(" · ")
                    else row.support
                SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(index, rows.size),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = url != null) {
                        if (url != null) runCatching { uriHandler.openUri(url) }
                    },
                    leadingContent = { Icon(row.icon, null, Modifier.size(20.dp)) },
                    trailingContent = if (stackValues) null else {
                        { Text(row.value, style = MaterialTheme.typography.bodyLarge) }
                    },
                    supportingContent = if (detail == null) null else {
                        { Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                            overflow = TextOverflow.Ellipsis) }
                    },
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = colors.onSurfaceVariant,
                        trailingContentColor = colors.onSurface,
                        supportingContentColor = colors.onSurfaceVariant)) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private data class SourcePropertyRow(val icon: ImageVector, val label: String, val value: String,
    val support: String? = null)

private fun cacheValue(bytes: Long): String = if (bytes > 0) "${formatSize(bytes)} cached" else "Not cached yet"

private fun linkValue(status: SourceTrackStatus?, reading: Boolean): String = when {
    reading -> "Checking…"
    status == null -> "Unavailable"
    status.state in setOf("queued", "matching", "waiting") -> "Pending"
    else -> "Not linked"
}

private fun statusText(status: SourceTrackStatus?): String? = when {
    status == null -> null
    status.state == "linked" && status.verified -> "Verified YouTube Music match"
    status.state == "linked" -> "Linked for playback"
    status.state == "matching" -> "Matching with YouTube Music now"
    status.state == "waiting" -> "Waiting to retry the match"
    status.state == "expired" -> "Saved link expired; it will be matched again"
    status.state == "queued" -> "Queued for background matching"
    else -> "Matched on first play"
}

@Composable
private fun LocationTile(location: String?, reading: Boolean) {
    val colors = MaterialTheme.colorScheme
    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(0, 1), modifier = Modifier.fillMaxWidth(),
        leadingContent = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(20.dp)) },
        supportingContent = {
            Text(location ?: if (reading) "Reading file…" else "Unavailable",
                style = MaterialTheme.typography.bodySmall, minLines = 2, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        },
        colors = ListItemDefaults.segmentedColors(containerColor = colors.surfaceContainerHigh)) {
        Text("File location", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlaylistPill(name: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun sampleRateText(hz: Int): String {
    val khz = hz / 1000f
    return if (khz % 1f == 0f) "${khz.toInt()} kHz" else "%.1f kHz".format(khz)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}

/** Review and restore files that were hidden from the library. */
@Composable
internal fun HiddenAudioScreen(state: MusicState, model: MusicViewModel, back: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("Hidden audio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${state.hiddenTracks.size} files · still on your phone",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.hiddenTracks.isNotEmpty()) TextButton(model::restoreAllHidden) { Text("Restore all") }
            }
            Text("Hidden files do not appear in Home, Search, Library, playlists or My Mix.",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
                items(state.hiddenTracks, key = { it.id }) { track ->
                    Surface(Modifier.fillMaxWidth().padding(bottom = 4.dp).animateItem(), shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(track, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(track.artist, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            TextButton({ model.restore(track) }) { Text("Restore") }
                        }
                    }
                }
                if (state.hiddenTracks.isEmpty()) item {
                    EmptyState("Nothing hidden", "Long-press a song, then open its properties to hide it from the app.")
                }
            }
        }
    }
}
