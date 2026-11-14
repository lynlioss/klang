@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.lynli.klang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.data.Track
import app.lynli.klang.data.TrackDetails
import java.text.NumberFormat

/**
 * Cover-themed properties sheet: one editorial header, a grid of tonal stat cards and a compact
 * location/playlist footer. Technical metadata loads in place so the sheet never blocks.
 */
@Composable
internal fun TrackPropertiesSheet(track: Track, state: MusicState, model: MusicViewModel,
    hide: (() -> Unit)? = null, dismiss: () -> Unit) {
    var details by remember(track.id) { mutableStateOf<TrackDetails?>(null) }
    LaunchedEffect(track.id) { details = runCatching { model.details(track) }.getOrNull() }
    val hideTrack = hide ?: { model.hide(track); dismiss() }
    val playlistNames = remember(state.playlists, track.id) {
        state.playlists.filter { track.id in it.trackIds }.map { it.name }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = Color.Transparent,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.padding(top = 10.dp)) }) {
        CoverTheme(track) {
            val colors = MaterialTheme.colorScheme
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
                colors.primaryContainer.copy(alpha = 0.55f).compositeOver(colors.surfaceContainerLow),
                colors.surfaceContainerLow, colors.surfaceContainerLow)))) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                    Text("PROPERTIES", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp),
                        fontWeight = FontWeight.Bold, color = colors.primary)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(track, Modifier.size(88.dp).shadow(12.dp, RoundedCornerShape(24.dp))
                            .clip(RoundedCornerShape(24.dp)), "${track.title} cover")
                        Column(Modifier.weight(1f).padding(start = 18.dp)) {
                            Text(track.title, style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.album, style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    PropertyGrid(track, details)
                    Spacer(Modifier.height(12.dp))
                    LocationTile(details?.location)
                    Spacer(Modifier.height(22.dp))
                    Text("In playlists", style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant)
                    if (playlistNames.isEmpty()) {
                        Text("Not in a playlist", Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            playlistNames.forEach { PlaylistPill(it) }
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton({ hideTrack() }, Modifier.weight(1.35f).height(56.dp),
                            shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                            Icon(ImageVector.vectorResource(R.drawable.visibility_off), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Hide from app", maxLines = 1)
                        }
                        Button(dismiss, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(20.dp)) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyGrid(track: Track, details: TrackDetails?) {
    val timerIcon = ImageVector.vectorResource(R.drawable.timer)
    val entries = buildList<Triple<ImageVector, String, String>> {
        add(Triple(timerIcon, "Duration", time(details?.durationMs ?: track.durationMs)))
        add(Triple(Icons.Rounded.Star, "Quality", details?.quality ?: "—"))
        add(Triple(Icons.Rounded.MusicNote, "Format", details?.format ?: "—"))
        add(Triple(Icons.Rounded.GraphicEq, "Bitrate", details?.bitrateKbps
            ?.let { "${NumberFormat.getIntegerInstance().format(it)} kbps" } ?: "—"))
        details?.sampleRateHz?.let { add(Triple(Icons.Rounded.OfflineBolt, "Sample rate", sampleRateText(it))) }
        details?.sizeBytes?.let { add(Triple(Icons.Rounded.Download, "File size", formatSize(it))) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (icon, label, value) -> PropertyTile(icon, label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PropertyTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHigh.copy(alpha = 0.92f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(16.dp), tint = colors.primary)
                }
                Spacer(Modifier.width(9.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LocationTile(location: String?) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHigh.copy(alpha = 0.92f)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp), tint = colors.primary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("File location", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(location ?: "Reading file…", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
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
