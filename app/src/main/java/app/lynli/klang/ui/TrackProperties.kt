package app.lynli.klang.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.data.Track
import app.lynli.klang.data.TrackDetails
import java.text.NumberFormat

/** Small, quiet sheet with everything the app knows about a local file. */
@Composable
internal fun TrackPropertiesDialog(track: Track, state: MusicState, model: MusicViewModel,
    hide: (() -> Unit)? = null, dismiss: () -> Unit) {
    var details by remember(track.id) { mutableStateOf<TrackDetails?>(null) }
    LaunchedEffect(track.id) { details = runCatching { model.details(track) }.getOrNull() }
    val hideTrack = hide ?: { model.hide(track); dismiss() }
    val playlistNames = remember(state.playlists, track.id) {
        state.playlists.filter { track.id in it.trackIds }.map { it.name }
    }
    Dialog(dismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth().widthIn(max = 400.dp),
                shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp, shadowElevation = 12.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Artwork(track, Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)), "${track.title} cover")
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.album, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(dismiss) { Icon(Icons.Rounded.Close, "Close properties") }
                    }
                    Spacer(Modifier.height(20.dp))
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row {
                                PropertyCell("Duration", time(details?.durationMs ?: track.durationMs), Modifier.weight(1f))
                                PropertyCell("Quality", details?.quality ?: "…", Modifier.weight(1f))
                            }
                            Row {
                                PropertyCell("Format", formatLine(details), Modifier.weight(1f))
                                PropertyCell("Bitrate", details?.bitrateKbps?.let { "${NumberFormat.getIntegerInstance().format(it)} kbps" } ?: "…",
                                    Modifier.weight(1f))
                            }
                            val fileSize = details?.sizeBytes
                            if (fileSize != null) {
                                PropertyCell("File size", formatSize(fileSize), Modifier.fillMaxWidth())
                            }
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text("Location", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(details?.location ?: "Reading file…", Modifier.padding(top = 3.dp),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("In playlists", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (playlistNames.isEmpty()) {
                        Text("Not in a playlist", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            playlistNames.forEach { name ->
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                    Text(name, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(dismiss) { Text("Close") }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton({ hideTrack() }) {
                            Icon(ImageVector.vectorResource(R.drawable.visibility_off), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Hide from app")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 10.dp, horizontal = 0.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatLine(details: TrackDetails?): String {
    val format = details?.format ?: "…"
    val rate = details?.sampleRateHz?.let { hz ->
        val khz = hz / 1000f
        if (khz % 1f == 0f) "${khz.toInt()} kHz" else "%.1f kHz".format(khz)
    } ?: return format
    return "$format · $rate"
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
                    Surface(Modifier.fillMaxWidth().padding(bottom = 4.dp).animateItem(), shape = RoundedCornerShape(16.dp),
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
