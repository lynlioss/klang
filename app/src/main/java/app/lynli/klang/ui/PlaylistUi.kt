@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.data.Track

@Composable
internal fun PlaylistNameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    PlaylistEditorDialog(title, initial, false, dismiss) { name, _ -> save(name) }
}

@Composable
internal fun PlaylistCreateDialog(dismiss: () -> Unit, save: (String, Uri?) -> Unit) {
    PlaylistEditorDialog("Create playlist", "", true, dismiss, save)
}

@Composable
private fun PlaylistEditorDialog(title: String, initial: String, creating: Boolean,
    dismiss: () -> Unit, save: (String, Uri?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var coverUri by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) coverUri = uri.toString()
    }
    val submit = { if (name.trim().isNotEmpty()) save(name.trim(), coverUri?.let(Uri::parse)) }
    AlertDialog(onDismissRequest = dismiss, shape = MaterialTheme.shapes.extraLarge,
        icon = { Icon(painterResource(R.drawable.playlist_add), null, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text(title, fontWeight = FontWeight.Bold) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (creating) "A home for your favorite sounds." else "Give your mix a new name.",
                    style = MaterialTheme.typography.bodyMedium)
                if (creating) Surface(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (coverUri != null) PickedCover(Uri.parse(coverUri), Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
                        else Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.photo), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(if (coverUri == null) "Choose a cover" else "Change cover", fontWeight = FontWeight.Bold)
                            Text("Make it yours", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.Add, null)
                    }
                }
                TextField(name, { name = it.take(100) }, Modifier.fillMaxWidth(),
                    label = { Text("Playlist name") }, placeholder = { Text("Your next great mix") },
                    singleLine = true, shape = KlangFieldShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
            }
        }, confirmButton = {
            Button(submit, enabled = name.trim().isNotEmpty()) {
                Icon(if (creating) Icons.Rounded.Add else Icons.Rounded.Check, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text(if (creating) "Create" else "Save")
            }
        }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
internal fun PlaylistListItem(collection: MusicCollection, modifier: Modifier = Modifier, open: () -> Unit) {
    Surface(onClick = open, modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArtwork(collection.coverFile, collection.tracks.firstOrNull(), Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(collection.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${collection.tracks.size} songs", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AddToPlaylistDialog(track: Track, state: MusicState, model: MusicViewModel, dismiss: () -> Unit) {
    var create by rememberSaveable { mutableStateOf(false) }
    val songs = remember(state.tracks) { state.tracks.associateBy { it.id } }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, shape = KlangSheetShape) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Add to playlist", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton({ create = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(painterResource(R.drawable.playlist_add), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text("New playlist", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.playlists, key = { it.id }) { playlist ->
                    val added = track.id in playlist.trackIds
                    Surface(onClick = { model.addToPlaylist(playlist.id, listOf(track)); dismiss() }, enabled = !added,
                        shape = MaterialTheme.shapes.medium, color = if (added) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlaylistArtwork(playlist.coverFile, playlist.trackIds.firstNotNullOfOrNull { songs[it] },
                                Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (added) "Already added" else "${playlist.trackIds.size} songs", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(if (added) Icons.Rounded.Check else Icons.Rounded.Add, null)
                        }
                    }
                }
                if (state.playlists.isEmpty()) item { EmptyState("Start a new mix", "Create a playlist to keep this song close.") }
            }
        }
    }
    if (create) PlaylistCreateDialog({ create = false }) { name, cover ->
        model.createPlaylist(name)?.let { id ->
            model.addToPlaylist(id, listOf(track))
            cover?.let { model.setPlaylistCover(id, it) }
            create = false; dismiss()
        }
    }
}

@Composable
internal fun PlaylistTrackPicker(collection: MusicCollection, state: MusicState, save: (List<Track>) -> Unit, back: () -> Unit) {
    var selected by rememberSaveable(collection.id) { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable(collection.id) { mutableStateOf("") }
    val existing = collection.tracks.map { it.id }.toSet()
    val available = state.tracks.filter { it.id !in existing }
    val tracks = available.filter { "${it.title} ${it.artist} ${it.album}".contains(query, true) }
    val selectedTracks = available.filter { it.id in selected }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp)) {
            Row(Modifier.padding(top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("Add songs", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(collection.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search your music") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = {
                    if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                }, singleLine = true, shape = RoundedCornerShape(28.dp), colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${tracks.size} available", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val allSelected = tracks.isNotEmpty() && tracks.all { it.id in selected }
                TextButton({ selected = if (allSelected) selected - tracks.map { it.id }.toSet()
                    else (selected + tracks.map { it.id }).distinct() }, enabled = tracks.isNotEmpty()) {
                    Text(if (allSelected) "Clear selection" else "Select all")
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(tracks, key = { it.id }) { track ->
                    val checked = track.id in selected
                    val color by animateColorAsState(if (checked) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow, spring(1f, 1600f), label = "Song selection")
                    val corner by animateDpAsState(if (checked) 28.dp else 20.dp, spring(0.6f, 800f), label = "Selection shape")
                    Surface(shape = RoundedCornerShape(corner), color = color, modifier = Modifier.fillMaxWidth().animateItem()
                        .toggleable(checked, role = Role.Checkbox) { value -> selected = if (value) selected + track.id else selected - track.id }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = if (checked) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked, null)
                        }
                    }
                }
                if (tracks.isEmpty()) item { EmptyState(if (query.isBlank()) "You're all caught up" else "No matching songs",
                    if (query.isBlank()) "Every song in your library is already in this playlist." else "Try another title, artist or album.") }
            }
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (selectedTracks.isEmpty()) "Pick the songs for your mix" else "${selectedTracks.size} songs · ${time(selectedTracks.sumOf { it.durationMs })}",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Button({ save(selectedTracks) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = selectedTracks.isNotEmpty(),
                        shape = RoundedCornerShape(16.dp)) {
                        Icon(painterResource(R.drawable.playlist_add), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text(if (selectedTracks.isEmpty()) "Add songs" else "Add ${selectedTracks.size} songs")
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaylistTrackRow(track: Track, index: Int, collection: MusicCollection, state: MusicState,
    model: MusicViewModel, modifier: Modifier = Modifier, openPlayer: () -> Unit = {}, lifted: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (lifted) 1.025f else 1f, spring(0.6f, 800f), label = "Lift song")
    val corner by animateDpAsState(if (lifted) 28.dp else 20.dp, spring(0.6f, 800f), label = "Lift shape")
    val elevation by animateDpAsState(if (lifted) 6.dp else 0.dp, spring(0.6f, 800f), label = "Lift elevation")
    val color by animateColorAsState(when {
        lifted -> MaterialTheme.colorScheme.secondaryContainer
        state.current?.id == track.id -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }, spring(1f, 1600f), label = "Lift color")
    Surface(modifier.fillMaxWidth().padding(bottom = 4.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics {
            customActions = buildList {
                if (index > 0) add(CustomAccessibilityAction("Move earlier") { model.moveInPlaylist(collection.editableId!!, track.id, -1); true })
                if (index < collection.tracks.lastIndex) add(CustomAccessibilityAction("Move later") { model.moveInPlaylist(collection.editableId!!, track.id, 1); true })
            }
        }, shape = RoundedCornerShape(corner), shadowElevation = elevation.coerceAtLeast(0.dp), color = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable {
                if (state.current?.id == track.id) openPlayer() else model.play(collection.tracks, index, sourceId = collection.id)
            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (lifted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (lifted) Icon(painterResource(R.drawable.drag_handle), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            else if (state.current?.id == track.id && state.playing) PlayingIndicator(MaterialTheme.colorScheme.onSurface)
            else Text(time(track.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${track.title}") }
                DropdownMenu(menu, { menu = false }, shape = KlangMenuShape,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    DropdownMenuItem({ Text("Remove from playlist") }, onClick = {
                        model.removeFromPlaylist(collection.editableId!!, track.id); menu = false
                    }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                    DropdownMenuItem({ Text("Properties") }, onClick = { properties = true; menu = false },
                        leadingIcon = { Icon(Icons.Rounded.Info, null) })
                }
            }
        }
    }
    if (properties) TrackPropertiesSheet(track, state, model) { properties = false }
}
