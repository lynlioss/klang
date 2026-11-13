@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.data.Track

internal data class MusicCollection(val id: String, val title: String, val subtitle: String,
    val tracks: List<Track>, val favorites: Boolean = false, val mix: Boolean = false,
    val album: Boolean = false, val editableId: String? = null)

// Use artist as well as album title: different artists can release identically named albums.
internal fun albumCollections(tracks: List<Track>): List<MusicCollection> = tracks
    .filter { it.album != "Unknown album" }
    .groupBy { it.album to it.artist }
    .map { (key, songs) -> MusicCollection("album:${key.first.length}:${key.first}${key.second}",
        key.first, key.second, songs, album = true) }

internal fun musicCollections(state: MusicState): List<MusicCollection> {
    val tracks = state.tracks.associateBy { it.id }
    return listOf(MusicCollection("mix", "My Mix", "", state.tracks, mix = true),
        MusicCollection("favorites", "Favorites", "Saved songs", state.tracks.filter { it.id in state.favorites }, favorites = true)) +
        state.playlists.map { playlist -> MusicCollection("playlist:${playlist.id}", playlist.name, "",
            playlist.trackIds.mapNotNull { tracks[it] }, editableId = playlist.id) } + albumCollections(state.tracks)
}

@Composable
internal fun HomeScreen(state: MusicState, model: MusicViewModel, collections: List<MusicCollection>,
    settings: () -> Unit, permission: () -> Unit, import: () -> Unit,
    library: () -> Unit, openCollection: (MusicCollection) -> Unit, bottom: Dp) {
    val albums = collections.filter { it.album }
    val mix = collections.first { it.mix }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = bottom)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    UtilityIconButton(settings) { Icon(Icons.Rounded.Settings, "Settings") }
                }
                Spacer(Modifier.height(4.dp))
                MixHero(mix, state, model, compact = true) { openCollection(mix) }
                if (state.loading || state.busy) LoadingRow(state)
                if (!state.permission) { Spacer(Modifier.height(16.dp)); AccessCard(permission, import) }
            }
        }
        if (albums.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = 24.dp)) { SectionHeading("Albums & releases") } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(albums, key = { it.id }) { album -> AlbumCard(album) { openCollection(album) } }
                }
            }
        }
        item { Box(Modifier.padding(horizontal = 24.dp)) { SectionHeading("Recently added", "See all", library) } }
        items(state.tracks.take(8), key = { it.id }) { track ->
            TrackRow(track, state, model, { model.play(state.tracks, state.tracks.indexOf(track)) },
                Modifier.padding(horizontal = 24.dp).animateItem())
        }
        if (state.tracks.isEmpty() && !state.loading && !state.busy) item {
            Column(Modifier.padding(horizontal = 24.dp)) { EmptyLibrary(import, model::installStarter) }
        }
    }
}

@Composable
private fun MixHero(collection: MusicCollection, state: MusicState, model: MusicViewModel,
    compact: Boolean, open: (() -> Unit)? = null) {
    val modifier = Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(role = Role.Button,
        onClickLabel = "Open My Mix", onClick = open) else Modifier)
    BoxWithConstraints(modifier) {
        val availableWidth = maxWidth
        val narrow = availableWidth < 360.dp
        val display = if (compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge
        val height = if (compact) 216.dp else 404.dp
        Box(Modifier.fillMaxWidth().height(height)) {
            Column(Modifier.align(Alignment.TopStart).padding(top = 8.dp)) {
                Text("My\nMix", style = display.copy(textGeometricTransform = TextGeometricTransform(scaleX = 1.12f)),
                    fontWeight = FontWeight.Black)
                if (!compact) Text(collection.tracks.map { it.artist }.distinct().take(3).joinToString(", "),
                    Modifier.width(availableWidth * 0.65f).padding(top = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (collection.tracks.isNotEmpty()) {
                val first = collection.tracks.first()
                val second = collection.tracks.getOrElse(1) { first }
                val third = collection.tracks.getOrElse(2) { first }
                val artWidth = if (compact) availableWidth * (if (narrow) 0.41f else 0.46f) else availableWidth * 0.63f
                Artwork(first, Modifier.align(if (compact) Alignment.CenterEnd else Alignment.BottomCenter)
                    .padding(end = if (compact) 8.dp else 0.dp, bottom = if (compact) 0.dp else 8.dp)
                    .width(artWidth).height(if (compact) 148.dp else 248.dp).rotate(20f).clip(RoundedCornerShape(50)), large = true)
                Artwork(second, Modifier.align(Alignment.BottomStart).padding(start = if (compact) 16.dp else 0.dp,
                    bottom = if (compact) 4.dp else 124.dp).size(if (compact) 52.dp else 84.dp).clip(CircleShape))
                Artwork(third, Modifier.align(Alignment.BottomEnd).padding(end = if (compact) 12.dp else 0.dp,
                    bottom = 2.dp).size(if (compact) 52.dp else 88.dp).clip(RoundedCornerShape(28.dp)))
            }
            Box(Modifier.align(if (compact) Alignment.BottomStart else Alignment.TopEnd)
                .padding(start = if (compact) 88.dp else 0.dp, top = if (compact) 0.dp else 36.dp, bottom = if (compact) 4.dp else 0.dp)) {
                ExpressivePlay(state.playing && state.isPlayingFrom(collection.id), { model.playOrToggle(collection.tracks, collection.id) }, if (compact) 60.dp else 96.dp,
                    state.connected && collection.tracks.isNotEmpty())
            }
        }
    }
}

@Composable
private fun AlbumCard(album: MusicCollection, click: () -> Unit) {
    Column(Modifier.width(172.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button,
        onClickLabel = "Open ${album.title}", onClick = click)) {
        Artwork(album.tracks.first(), Modifier.size(172.dp).clip(RoundedCornerShape(28.dp)), large = true)
        Text(album.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(album.subtitle, Modifier.padding(top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CollectionScreen(collection: MusicCollection, state: MusicState, model: MusicViewModel,
    player: () -> Unit, back: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by rememberSaveable(collection.id) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val currentId = state.current?.id
    val activeRowVisible by remember(currentId, listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            currentId != null && layout.visibleItemsInfo.any { item ->
                item.key == currentId && controlsAreVisible(item.offset, item.size,
                    layout.viewportStartOffset, layout.viewportEndOffset)
            }
        }
    }
    BackHandler(adding) { adding = false }
    ContentTheme(state.coverColor && collection.album, collection.tracks.firstOrNull()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp,
                    bottom = if (state.current != null) 112.dp else 24.dp)) {
                    item(key = "collection-header") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                            Spacer(Modifier.weight(1f))
                            if (collection.editableId != null) Box {
                                UtilityIconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("Add songs") }, onClick = { adding = true; menu = false })
                                    DropdownMenuItem({ Text("Rename") }, onClick = { rename = true; menu = false })
                                    DropdownMenuItem({ Text("Delete playlist") }, onClick = { deleting = true; menu = false })
                                }
                            }
                        }
                        if (collection.mix) MixHero(collection, state, model, compact = false)
                        else {
                            CollectionArtwork(collection)
                            Spacer(Modifier.height(24.dp))
                            Text(collection.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                            if (collection.subtitle.isNotBlank()) Text(collection.subtitle, Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${collection.tracks.size} songs", style = MaterialTheme.typography.titleMedium)
                                Text(time(collection.tracks.sumOf { it.durationMs }), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (collection.editableId != null) TextButton({ adding = true }) { Text("Add songs") }
                            FilledTonalIconButton({ model.play(collection.tracks, shuffled = true, sourceId = collection.id) },
                                enabled = state.connected && collection.tracks.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, "Shuffle collection") }
                            if (!collection.mix) {
                                Spacer(Modifier.width(12.dp))
                                ExpressivePlay(state.playing && state.isPlayingFrom(collection.id), { model.playOrToggle(collection.tracks, collection.id) }, 72.dp, state.connected && collection.tracks.isNotEmpty())
                            }
                        }
                    }
                    items(collection.tracks, key = { it.id }) { track ->
                        if (collection.editableId != null) PlaylistTrackRow(track, collection.tracks.indexOf(track), collection,
                            state, model, Modifier.animateItem(), player)
                        else TrackRow(track, state, model, {
                            if (state.current?.id == track.id) player()
                            else model.play(collection.tracks, collection.tracks.indexOf(track), sourceId = collection.id)
                        }, Modifier.animateItem(), playbackControls = true)
                    }
                    if (collection.tracks.isEmpty()) item { EmptyState("No songs yet",
                        if (collection.favorites) "Save songs with the heart to build this playlist." else if (collection.mix) "Your music will appear here." else "Add songs to your playlist.") }
                }
                AnimatedVisibility(state.current != null && !activeRowVisible,
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp),
                    enter = slideInVertically(spring(0.8f, 200f)) { it } + fadeIn(spring(1f, 800f)),
                    exit = slideOutVertically(spring(0.8f, 200f)) { it } + fadeOut(spring(1f, 800f))) {
                    Box(Modifier.padding(16.dp)) { MiniPlayer(state, model, player) }
                }
            }
        }
    }
    if (rename) PlaylistNameDialog("Rename playlist", collection.title, { rename = false }) {
        model.renamePlaylist(collection.editableId!!, it); rename = false
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete playlist?") },
        text = { Text("Delete ${collection.title}? Your audio files will stay on your phone.") },
        confirmButton = { TextButton({ model.deletePlaylist(collection.editableId!!); deleting = false; back() }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
    if (adding) PlaylistTrackPicker(collection, state, { songs ->
        model.addToPlaylist(collection.editableId!!, songs); adding = false
    }) { adding = false }
}

@Composable
private fun CollectionArtwork(collection: MusicCollection) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(252.dp), contentAlignment = Alignment.Center) {
        if (collection.favorites && collection.tracks.size > 1) {
            Artwork(collection.tracks.last(), Modifier.align(Alignment.TopStart).size(88.dp).clip(CircleShape))
            Artwork(collection.tracks.first(), Modifier.width(maxWidth * 0.62f).height(224.dp).rotate(14f).clip(RoundedCornerShape(50)), large = true)
            Artwork(collection.tracks[1], Modifier.align(Alignment.BottomEnd).size(92.dp).clip(RoundedCornerShape(28.dp)))
        } else if (collection.tracks.isNotEmpty()) {
            Artwork(collection.tracks.first(), Modifier.size(244.dp).clip(RoundedCornerShape(48.dp)), large = true)
        } else {
            Box(Modifier.size(224.dp).clip(RoundedCornerShape(48.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Favorite, null, Modifier.size(88.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
internal fun PluginsScreen(back: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(24.dp)) {
            item {
                UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Plugins", Modifier.padding(top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.displayMedium)
                Text("Music integrations", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                listOf("Spotify" to R.drawable.spotify, "YouTube Music" to R.drawable.youtube_music).forEach { (name, logo) ->
                    Surface(Modifier.padding(bottom = 12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(logo), "$name logo", Modifier.size(48.dp), tint = Color.Unspecified)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Unavailable", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = false, onCheckedChange = null, enabled = false)
                        }
                    }
                }
            }
        }
    }
}
