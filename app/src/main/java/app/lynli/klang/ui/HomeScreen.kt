@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.data.Track

internal data class MusicCollection(val id: String, val title: String, val subtitle: String,
    val tracks: List<Track>, val favorites: Boolean = false)

// Use artist as well as album title: different artists can release identically named albums.
internal fun albumCollections(tracks: List<Track>): List<MusicCollection> = tracks
    .filter { it.album != "Unknown album" }
    .groupBy { it.album to it.artist }
    .map { (key, songs) -> MusicCollection("album:${key.first.length}:${key.first}${key.second}",
        key.first, key.second, songs) }

internal fun musicCollections(tracks: List<Track>, favorites: Set<String>): List<MusicCollection> =
    listOf(MusicCollection("favorites", "Favorites", "Saved songs", tracks.filter { it.id in favorites }, true)) + albumCollections(tracks)

@Composable
internal fun HomeScreen(state: MusicState, model: MusicViewModel, collections: List<MusicCollection>,
    settings: () -> Unit, plugins: () -> Unit, permission: () -> Unit, import: () -> Unit,
    library: () -> Unit, openCollection: (MusicCollection) -> Unit, bottom: Dp) {
    val albums = collections.filterNot { it.favorites }
    val favorites = collections.first { it.favorites }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = bottom)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Home", Modifier.weight(1f), style = MaterialTheme.typography.displayLarge)
                    FilledTonalIconButton(settings) { Icon(Icons.Rounded.Settings, "Settings") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.tracks.size} songs on this device", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(plugins) { Text("Plugins") }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickCollection("Favorites", "${favorites.tracks.size} saved", Icons.Rounded.Favorite,
                        Modifier.weight(1f), true) { openCollection(favorites) }
                    QuickCollection("Songs", "${state.tracks.size} tracks", Icons.Rounded.LibraryMusic,
                        Modifier.weight(1f), false, library)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ model.play(state.tracks, shuffled = true) }, Modifier.weight(1f),
                        enabled = state.connected && state.tracks.isNotEmpty()) {
                        Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Shuffle all")
                    }
                    FilledTonalButton(import, Modifier.weight(1f), enabled = !state.busy) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add music")
                    }
                }
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
private fun QuickCollection(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier, primary: Boolean, click: () -> Unit) {
    val fill = if (primary) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val ink = if (primary) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(onClick = click, modifier = modifier, shape = RoundedCornerShape(20.dp), color = fill, contentColor = ink) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, Modifier.size(28.dp))
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AlbumCard(album: MusicCollection, click: () -> Unit) {
    Column(Modifier.width(172.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button,
        onClickLabel = "Open ${album.title}", onClick = click)) {
        Artwork(album.tracks.first(), Modifier.size(172.dp).clip(RoundedCornerShape(28.dp)))
        Text(album.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(album.subtitle, Modifier.padding(top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CollectionScreen(collection: MusicCollection, state: MusicState, model: MusicViewModel,
    player: () -> Unit, back: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp,
                bottom = if (state.current != null) 112.dp else 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Text(if (collection.favorites) "Playlist" else "Album / release", Modifier.padding(start = 16.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CollectionArtwork(collection)
                    Spacer(Modifier.height(24.dp))
                    Text(collection.title, style = MaterialTheme.typography.displaySmall)
                    Text(collection.subtitle, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${collection.tracks.size} songs", style = MaterialTheme.typography.titleMedium)
                            Text(time(collection.tracks.sumOf { it.durationMs }), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalIconButton({ model.play(collection.tracks, shuffled = true) },
                            enabled = state.connected && collection.tracks.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, "Shuffle collection") }
                        Spacer(Modifier.width(12.dp))
                        ExpressivePlay(false, { model.play(collection.tracks) }, 72.dp, state.connected && collection.tracks.isNotEmpty())
                    }
                }
                items(collection.tracks, key = { it.id }) { track -> TrackRow(track, state, model,
                    { model.play(collection.tracks, collection.tracks.indexOf(track)) }, Modifier.animateItem()) }
                if (collection.tracks.isEmpty()) item { EmptyState("No favorites yet", "Save songs with the heart to build this playlist.") }
            }
            if (state.current != null) Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp).padding(16.dp)) {
                MiniPlayer(state, model, player)
            }
        }
    }
}

@Composable
private fun CollectionArtwork(collection: MusicCollection) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(252.dp), contentAlignment = Alignment.Center) {
        if (collection.favorites && collection.tracks.size > 1) {
            Artwork(collection.tracks.last(), Modifier.align(Alignment.TopStart).size(88.dp).clip(CircleShape))
            Artwork(collection.tracks.first(), Modifier.width(maxWidth * 0.62f).height(224.dp).rotate(14f).clip(RoundedCornerShape(50)))
            Artwork(collection.tracks[1], Modifier.align(Alignment.BottomEnd).size(92.dp).clip(RoundedCornerShape(28.dp)))
        } else if (collection.tracks.isNotEmpty()) {
            Artwork(collection.tracks.first(), Modifier.size(244.dp).clip(RoundedCornerShape(48.dp)))
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
                FilledTonalIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Plugins", Modifier.padding(top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.displayMedium)
                Text("Music integrations", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.spotify), "Spotify logo", Modifier.size(48.dp), tint = Color.Unspecified)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text("Spotify", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Disabled", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = false, onCheckedChange = null, enabled = false)
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Not implemented yet", style = MaterialTheme.typography.titleMedium)
                        Text("Spotify support is planned. This plugin will stay unavailable while Klang’s plugin system is being developed.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
