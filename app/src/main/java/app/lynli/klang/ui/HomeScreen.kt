@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.data.Track
import app.lynli.klang.data.albumGroupingArtist
import app.lynli.klang.data.mixArtwork
import app.lynli.klang.plugins.SourceCollection
import kotlinx.coroutines.launch

internal data class MusicCollection(val id: String, val title: String, val subtitle: String,
    val tracks: List<Track>, val favorites: Boolean = false, val mix: Boolean = false,
    val album: Boolean = false, val artist: Boolean = false, val editableId: String? = null,
    val coverFile: String? = null, val source: SourceCollection? = null) {
    val songCount: Int get() = source?.total ?: tracks.size
}

internal fun SourceCollection.collection() = MusicCollection(identity, title, subtitle, emptyList(),
    album = kind == "album", artist = kind == "artist", source = this)

@Composable
internal fun CollectionCover(collection: MusicCollection, modifier: Modifier, large: Boolean = false) {
    if (collection.source != null) Artwork(Track(android.net.Uri.EMPTY, collection.title, collection.subtitle, "", 0,
        artworkUrl = collection.source.artwork), modifier, large = large)
    else PlaylistArtwork(collection.coverFile, collection.tracks.firstOrNull(), modifier, large = large)
}

// Album credits stay constant when individual tracks include guest artists.
internal fun albumCollections(tracks: List<Track>): List<MusicCollection> = tracks
    .filter { it.album != "Unknown album" }
    .groupBy { it.album to albumGroupingArtist(it.albumArtist, it.artist) }
    .map { (key, songs) -> MusicCollection("album:${key.first.length}:${key.first}${key.second}",
        key.first, key.second, songs, album = true) }

internal fun artistId(artist: String) = "artist:${artist.length}:$artist"

internal fun artistCollection(artist: String, tracks: List<Track>): MusicCollection? {
    if (artist.isBlank() || artist == "Unknown artist") return null
    val songs = tracks.filter { it.artist == artist }
    if (songs.isEmpty()) return null
    return MusicCollection(artistId(artist), artist, "${songs.size} songs", songs, artist = true)
}

internal fun artistCollections(tracks: List<Track>): List<MusicCollection> = tracks.map { it.artist }.distinct()
    .mapNotNull { artistCollection(it, tracks) }

internal fun albumCollection(track: Track, tracks: List<Track>): MusicCollection? = albumCollections(tracks)
    .find { it.title == track.album && it.tracks.any { song -> song.id == track.id } }

internal fun musicCollections(state: MusicState): List<MusicCollection> {
    val tracks = state.tracks.associateBy { it.id }
    return listOf(MusicCollection("mix", "My Mix", "collection of all your songs", state.tracks, mix = true),
        MusicCollection("favorites", "Favorites", "Saved songs", state.tracks.filter { it.id in state.favorites }, favorites = true)) +
        state.playlists.map { playlist -> MusicCollection("playlist:${playlist.id}", playlist.name, "",
            playlist.trackIds.mapNotNull { tracks[it] }, editableId = playlist.id, coverFile = playlist.coverFile) } +
        albumCollections(state.tracks) + artistCollections(state.tracks) + state.sourceCollections.map { it.collection() }
}

@Composable
internal fun HomeScreen(state: MusicState, model: MusicViewModel, collections: List<MusicCollection>,
    settings: () -> Unit, permission: () -> Unit, import: () -> Unit,
    library: () -> Unit, openCollection: (MusicCollection) -> Unit, bottom: Dp, downloads: () -> Unit) {
    val albums = collections.filter { it.album }
    val mix = collections.first { it.mix }
    val recentPlaylists = state.playlists.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }
        .take(8).mapNotNull { playlist -> collections.find { it.editableId == playlist.id } }
    LaunchedEffect(state.tracks.take(2).map { it.id }) { model.musicSources.prepare(state.tracks.take(2)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                PageTitle("Klang", brand = true) {
                    DownloadStatusButton(model.musicImports, downloads)
                    UtilityIconButton(settings) { Icon(Icons.Rounded.Settings, "Settings") }
                }
                MixHero(mix, state, model, compact = true) { openCollection(mix) }
                SourceConnectionCards(state, model)
                if (state.loading || state.busy) LoadingRow(state)
                if (!state.permission) { Spacer(Modifier.height(16.dp)); AccessCard(permission, import) }
            }
        }
        state.sourceHome.groupBy { it.section.ifBlank { "Made for you" } }.forEach { (section, entries) ->
            item { Box(Modifier.padding(horizontal = 24.dp)) { SectionHeading(section) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(entries.distinctBy { it.identity }, key = { it.identity }) { entry ->
                        val collection = entry.collection()
                        AlbumCard(collection) { openCollection(collection) }
                    }
                }
            }
        }
        if (recentPlaylists.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = 24.dp)) { SectionHeading("Recently played playlists") } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(recentPlaylists, key = { it.id }) { playlist -> RecentPlaylistCard(playlist) { openCollection(playlist) } }
                }
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
            Column(Modifier.padding(horizontal = 24.dp)) { EmptyLibrary(import) }
        }
    }
}

@Composable
private fun RecentPlaylistCard(playlist: MusicCollection, open: () -> Unit) {
    Surface(onClick = open, modifier = Modifier.width(172.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp)) {
            CollectionCover(playlist, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)), large = true)
            Text(playlist.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${playlist.songCount} songs", Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MixHero(collection: MusicCollection, state: MusicState, model: MusicViewModel,
    compact: Boolean, open: (() -> Unit)? = null) {
    // Resample when opened or the library changes; playback recompositions keep the artwork steady.
    val artwork = remember(collection.tracks, compact) {
        mixArtwork(collection.tracks) { it.album to albumGroupingArtist(it.albumArtist, it.artist) }
    }
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
                if (!compact) Text(collection.subtitle,
                    Modifier.width(availableWidth * 0.65f).padding(top = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (collection.tracks.isNotEmpty()) {
                val first = artwork.first()
                val artWidth = if (compact) availableWidth * (if (narrow) 0.41f else 0.46f) else availableWidth * 0.63f
                Artwork(first, Modifier.align(if (compact) Alignment.CenterEnd else Alignment.BottomCenter)
                    .padding(end = if (compact) 8.dp else 0.dp, bottom = if (compact) 0.dp else 8.dp)
                    .width(artWidth).height(if (compact) 148.dp else 248.dp).rotate(20f).clip(RoundedCornerShape(50)), large = true)
                artwork.getOrNull(1)?.let { second ->
                    Artwork(second, Modifier.align(Alignment.BottomStart).padding(start = if (compact) 16.dp else 0.dp,
                        bottom = if (compact) 4.dp else 124.dp).size(if (compact) 52.dp else 84.dp).clip(CircleShape))
                }
                artwork.getOrNull(2)?.let { third ->
                    Artwork(third, Modifier.align(Alignment.BottomEnd).padding(end = if (compact) 12.dp else 0.dp,
                        bottom = 2.dp).size(if (compact) 52.dp else 88.dp).clip(RoundedCornerShape(28.dp)))
                }
            }
            Box(Modifier.align(if (compact) Alignment.BottomStart else Alignment.TopEnd)
                .padding(start = if (compact) 88.dp else 0.dp, top = if (compact) 0.dp else 36.dp, bottom = if (compact) 4.dp else 0.dp)) {
                ExpressivePlay(state.playWhenReady && state.isPlayingFrom(collection.id), { model.playOrToggle(collection.tracks, collection.id) }, if (compact) 60.dp else 96.dp,
                    state.connected && collection.tracks.isNotEmpty(), state.buffering && state.isPlayingFrom(collection.id))
            }
        }
    }
}

@Composable
private fun AlbumCard(album: MusicCollection, click: () -> Unit) {
    Column(Modifier.width(172.dp).clickable(role = Role.Button,
        onClickLabel = "Open ${album.title}", onClick = click)) {
        CollectionCover(album, Modifier.size(172.dp).clip(RoundedCornerShape(28.dp)), large = true)
        Text(album.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(album.subtitle, Modifier.padding(top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CollectionScreen(collection: MusicCollection, state: MusicState, model: MusicViewModel,
    player: () -> Unit, back: () -> Unit) {
    if (collection.source != null) {
        Box(Modifier.fillMaxSize()) {
            MusicSourceScreen(app.lynli.klang.plugins.SourceDestination(collection.source.providerId, collection.source.id), state, model, back)
            if (state.current != null) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .widthIn(max = 520.dp).padding(16.dp)) { MiniPlayer(state, model, player) }
        }
        return
    }
    LaunchedEffect(collection.id) { model.musicSources.prepare(collection.tracks.take(2)) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by rememberSaveable(collection.id) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorder = remember(collection.id, listState) { PlaylistReorderState(listState, collection.tracks) }
    SideEffect { reorder.sync(collection.tracks) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestCollection by rememberUpdatedState(collection)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) collection.editableId?.let { model.setPlaylistCover(it, uri) }
    }
    val chooseCover = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val orderedCollection = collection.copy(tracks = reorder.tracks)
    if (collection.editableId != null) {
        val density = LocalDensity.current
        PlaylistDragAutoScroll(reorder, with(density) { 72.dp.toPx() }, with(density) { 640.dp.toPx() }, haptics)
    }
    BackHandler(adding) { adding = false }
    PlaylistContentTheme(state.coverColor && (collection.album || collection.editableId != null),
        collection.coverFile, collection.tracks.firstOrNull()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val dragModifier = if (collection.editableId != null) Modifier.playlistDrag(reorder, haptics,
                    finish = { scope.launch { reorder.finish({ ids -> model.reorderPlaylist(collection.editableId, ids) }, haptics) } },
                    cancel = { reorder.cancel(latestCollection.tracks) }) else Modifier
                LazyColumn(Modifier.fillMaxSize().then(dragModifier), state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp,
                    bottom = if (state.current != null) 112.dp else 24.dp)) {
                    item(key = "collection-header") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                            Spacer(Modifier.weight(1f))
                            if (collection.editableId != null) Box {
                                UtilityIconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options") }
                                DropdownMenu(menu, { menu = false }, shape = KlangMenuShape,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                    DropdownMenuItem({ Text("Add songs") }, onClick = { adding = true; menu = false },
                                        leadingIcon = { Icon(painterResource(R.drawable.playlist_add), null) })
                                    DropdownMenuItem({ Text(if (collection.coverFile == null) "Choose cover" else "Change cover") },
                                        onClick = { menu = false; chooseCover() }, leadingIcon = { Icon(painterResource(R.drawable.photo), null) })
                                    if (collection.coverFile != null) DropdownMenuItem({ Text("Remove custom cover") },
                                        onClick = { model.removePlaylistCover(collection.editableId); menu = false },
                                        leadingIcon = { Icon(Icons.Rounded.Close, null) })
                                    DropdownMenuItem({ Text("Rename") }, onClick = { rename = true; menu = false },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                    DropdownMenuItem({ Text("Delete playlist") }, onClick = { deleting = true; menu = false },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null) }, colors = MenuDefaults.itemColors(
                                            textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error))
                                }
                            }
                        }
                        if (collection.mix) MixHero(collection, state, model, compact = false)
                        else {
                            CollectionArtwork(collection, if (collection.editableId != null) chooseCover else null)
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
                            FilledTonalIconButton({ model.play(collection.tracks, shuffled = true, sourceId = collection.id) },
                                enabled = state.connected && collection.tracks.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, "Shuffle collection") }
                            if (!collection.mix) {
                                Spacer(Modifier.width(12.dp))
                                ExpressivePlay(state.playWhenReady && state.isPlayingFrom(collection.id), { model.playOrToggle(collection.tracks, collection.id) },
                                    72.dp, state.connected && collection.tracks.isNotEmpty(), state.buffering && state.isPlayingFrom(collection.id))
                            }
                        }
                        if (collection.editableId != null) {
                            FilledTonalButton({ adding = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                shape = RoundedCornerShape(16.dp)) {
                                Icon(painterResource(R.drawable.playlist_add), null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp)); Text("Add songs", fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    items(orderedCollection.tracks, key = { it.id }) { track ->
                        if (collection.editableId != null) {
                            val lifted = reorder.draggingId == track.id || reorder.settlingId == track.id
                            PlaylistTrackRow(track, orderedCollection.tracks.indexOf(track), orderedCollection, state, model,
                                Modifier.animateItem(placementSpec = if (lifted) null else spring(0.8f, 380f))
                                    .zIndex(if (lifted) 1f else 0f).graphicsLayer { translationY = reorder.offset(track.id) },
                                player, lifted)
                        }
                        else TrackRow(track, state, model, {
                            if (state.current?.id == track.id && state.isPlayingFrom(collection.id)) player()
                            else model.play(collection.tracks, collection.tracks.indexOf(track), sourceId = collection.id)
                        }, Modifier.animateItem())
                    }
                    if (collection.tracks.isEmpty()) item { EmptyState("No songs yet",
                        if (collection.favorites) "Save songs with the heart to build this playlist." else if (collection.mix) "Your music will appear here." else "Add songs to your playlist.") }
                }
                AnimatedVisibility(state.current != null,
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp),
                    enter = slideInVertically(spring(0.8f, 200f)) { it } + fadeIn(spring(1f, 800f)),
                    exit = slideOutVertically(spring(0.8f, 200f)) { it } + fadeOut(spring(1f, 800f))) {
                    Box(Modifier.padding(16.dp)) { MiniPlayer(state, model, player) }
                }
            }
        }
        if (rename) PlaylistNameDialog("Rename playlist", collection.title, { rename = false }) {
            model.renamePlaylist(collection.editableId!!, it); rename = false
        }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, shape = MaterialTheme.shapes.extraLarge,
            icon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete playlist?", fontWeight = FontWeight.Bold) },
            text = { Text("Delete ${collection.title}? Your audio files will stay on your phone.") },
            confirmButton = { Button({ model.deletePlaylist(collection.editableId!!); deleting = false; back() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
        if (adding) PlaylistTrackPicker(collection, state, { songs ->
            model.addToPlaylist(collection.editableId!!, songs); adding = false
        }) { adding = false }
    }
}

@Composable
private fun CollectionArtwork(collection: MusicCollection, chooseCover: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(252.dp), contentAlignment = Alignment.Center) {
        if (collection.favorites && collection.tracks.size > 1) {
            Artwork(collection.tracks.last(), Modifier.align(Alignment.TopStart).size(88.dp).clip(CircleShape))
            Artwork(collection.tracks.first(), Modifier.width(maxWidth * 0.62f).height(224.dp).rotate(14f).clip(RoundedCornerShape(50)), large = true)
            Artwork(collection.tracks[1], Modifier.align(Alignment.BottomEnd).size(92.dp).clip(RoundedCornerShape(28.dp)))
        } else if (collection.tracks.isNotEmpty() || collection.coverFile != null || chooseCover != null) {
            Box(Modifier.size(244.dp)) {
                PlaylistArtwork(collection.coverFile, collection.tracks.firstOrNull(), Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(48.dp)).then(if (chooseCover != null) Modifier.clickable(role = Role.Button,
                        onClickLabel = "Change playlist cover", onClick = chooseCover) else Modifier), large = true)
                if (chooseCover != null) FilledTonalIconButton(chooseCover, Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    Icon(painterResource(R.drawable.photo), "Change playlist cover")
                }
            }
        } else {
            Box(Modifier.size(224.dp).clip(RoundedCornerShape(48.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Favorite, null, Modifier.size(88.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}
