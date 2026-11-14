@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.lynli.klang.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.data.Track

@Composable
internal fun PlaylistNameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("Playlist name") }, singleLine = true)
    }, confirmButton = { TextButton({ save(name.trim()) }, enabled = name.trim().isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
internal fun PlaylistListItem(collection: MusicCollection, modifier: Modifier = Modifier, open: () -> Unit) {
    Surface(onClick = open, modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(collection.tracks.firstOrNull(), Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(collection.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${collection.tracks.size} songs", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun AddToPlaylistDialog(track: Track, state: MusicState, model: MusicViewModel, dismiss: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add to playlist") }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(state.playlists, key = { it.id }) { playlist ->
                ListItem(headlineContent = { Text(playlist.name) }, modifier = Modifier.clickable {
                    model.addToPlaylist(playlist.id, listOf(track)); dismiss()
                }, supportingContent = { Text(if (track.id in playlist.trackIds) "Already added" else "${playlist.trackIds.size} songs") })
            }
            item { TextButton({ create = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("New playlist") } }
        }
    }, confirmButton = { TextButton(dismiss) { Text("Cancel") } })
    if (create) PlaylistNameDialog("Create playlist", "", { create = false }) { name ->
        model.createPlaylist(name)?.let { model.addToPlaylist(it, listOf(track)); dismiss() }
    }
}

@Composable
internal fun PlaylistTrackPicker(collection: MusicCollection, state: MusicState, save: (List<Track>) -> Unit, back: () -> Unit) {
    var selected by rememberSaveable(collection.id) { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    val existing = collection.tracks.map { it.id }.toSet()
    val tracks = state.tracks.filter { it.id !in existing && "${it.title} ${it.artist}".contains(query, true) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Add songs", Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.headlineSmall)
                TextButton({ save(state.tracks.filter { it.id in selected }) }, enabled = selected.isNotEmpty()) { Text("Add (${selected.size})") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 16.dp),
                label = { Text("Search songs") }, singleLine = true)
            LazyColumn(Modifier.weight(1f)) {
                items(tracks, key = { it.id }) { track ->
                    val checked = track.id in selected
                    ListItem(headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Artwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) },
                        trailingContent = { Checkbox(checked, null) }, modifier = Modifier.clickable {
                            selected = if (checked) selected - track.id else selected + track.id
                        })
                }
                if (tracks.isEmpty()) item { EmptyState("No songs to add", "All matching songs are already in this playlist.") }
            }
        }
    }
}

@Composable
internal fun PlaylistTrackRow(track: Track, index: Int, collection: MusicCollection, state: MusicState,
    model: MusicViewModel, modifier: Modifier = Modifier, openPlayer: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().padding(bottom = 4.dp), shape = RoundedCornerShape(16.dp),
        color = if (state.current?.id == track.id) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).combinedClickable(
                onClick = { if (state.current?.id == track.id) openPlayer() else model.play(collection.tracks, index, sourceId = collection.id) },
                onLongClick = { menu = true },
            ).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.current?.id == track.id && state.playing) PlayingIndicator(MaterialTheme.colorScheme.onSurface)
            else Text(time(track.durationMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${track.title}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Move up") }, enabled = index > 0, onClick = {
                        model.moveInPlaylist(collection.editableId!!, track.id, -1); menu = false
                    })
                    DropdownMenuItem({ Text("Move down") }, enabled = index < collection.tracks.lastIndex, onClick = {
                        model.moveInPlaylist(collection.editableId!!, track.id, 1); menu = false
                    })
                    DropdownMenuItem({ Text("Remove from playlist") }, onClick = {
                        model.removeFromPlaylist(collection.editableId!!, track.id); menu = false
                    })
                    DropdownMenuItem({ Text("Properties") }, onClick = { properties = true; menu = false },
                        leadingIcon = { Icon(Icons.Rounded.Info, null) })
                }
            }
        }
    }
    if (properties) TrackPropertiesSheet(track, state, model) { properties = false }
}
