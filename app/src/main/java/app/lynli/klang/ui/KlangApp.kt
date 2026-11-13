@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.data.Track
import kotlin.math.sin

@Composable
fun KlangApp(state: MusicState, model: MusicViewModel, onPermission: () -> Unit, onImport: () -> Unit) {
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); model.clearMessage() }
    }
    BackHandler(expanded || settings || destination != 0) {
        when { expanded -> expanded = false; settings -> settings = false; else -> destination = 0 }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            val wide = maxWidth >= 840.dp
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                    if (!wide) Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
                        state.current?.let { MiniPlayer(state, model, { expanded = true }) }
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            ShortNavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
                                listOf("Your Mix" to Icons.Rounded.GraphicEq, "Search" to Icons.Rounded.Search, "Library" to Icons.Rounded.LibraryMusic)
                                    .forEachIndexed { index, (label, icon) ->
                                        ShortNavigationBarItem(destination == index, { destination = index },
                                            icon = { Icon(icon, null) }, label = { Text(label) })
                                    }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Spacer(Modifier.height(32.dp))
                        listOf("Your Mix" to Icons.Rounded.GraphicEq, "Search" to Icons.Rounded.Search, "Library" to Icons.Rounded.LibraryMusic)
                            .forEachIndexed { i, (label, icon) -> NavigationRailItem(destination == i, { destination = i },
                                icon = { Icon(icon, null) }, label = { Text(label) }) }
                    }
                    Box(Modifier.weight(1f)) {
                        when (destination) {
                            0 -> MixScreen(state, model, { settings = true }, onPermission, onImport, { destination = 2 }, { expanded = true })
                            1 -> SearchScreen(state, model, onImport)
                            else -> LibraryScreen(state, model, { settings = true }, onPermission, onImport)
                        }
                    }
                    if (wide && state.current != null) Surface(Modifier.width(360.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        PlayerContent(state, model, {}, compact = true)
                    }
                }
            }
            if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
                Settings(state, model, onPermission, onImport)
            }
            if (expanded && state.current != null) ModalBottomSheet(onDismissRequest = { expanded = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = null,
                containerColor = MaterialTheme.colorScheme.surface) {
                PlayerContent(state, model, { expanded = false })
            }
        }
    }
}

@Composable
private fun MixScreen(state: MusicState, model: MusicViewModel, settings: () -> Unit, permission: () -> Unit,
    import: () -> Unit, library: () -> Unit, player: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("k", Modifier.padding(horizontal = 16.dp, vertical = 7.dp), fontFamily = Brand,
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("KLANG", style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
                    Text("Made for your music", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(settings) { Icon(Icons.Rounded.Settings, "Settings") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Product editorial treatment: a larger weight and tighter tracking than M3 display-large.
                    Text("Your\nMix", fontFamily = Brand, fontWeight = FontWeight.Bold,
                        fontSize = 64.sp, lineHeight = 60.sp, letterSpacing = (-3).sp)
                    Text(if (state.tracks.isEmpty()) "A little sound. A lot of you." else "${state.tracks.size} tracks. Endless moods.",
                        Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BigPlay(false, { model.play(state.tracks, shuffled = true) }, size = 104, enabled = state.tracks.isNotEmpty() && state.connected, label = "Shuffle your mix")
            }
            Spacer(Modifier.height(24.dp))
        }
        if (state.loading || state.busy) item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(40.dp)); Spacer(Modifier.width(12.dp))
                Text(if (state.busy) "Adding music to your phone…" else "Finding your music…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.tracks.isNotEmpty()) item {
            CoverCollage(state.tracks, { track -> model.play(state.tracks, state.tracks.indexOf(track)); player() })
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("OFFLINE. ALL YOURS.", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("✦  PRESS PLAY", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (!state.permission) item { AccessCard(permission, import) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("On repeat", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                TextButton(library) { Text("See all") }
            }
        }
        items(state.tracks.take(6), key = { it.id }) { track -> TrackRow(track, state, model, { model.play(state.tracks, state.tracks.indexOf(track)) }) }
        if (state.tracks.isEmpty() && !state.loading && !state.busy) item { EmptyLibrary(import, model::installStarter) }
        item {
            Surface(Modifier.fillMaxWidth().padding(top = 24.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.OfflineBolt, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Good music. No signal needed.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("Your collection stays on your phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverCollage(tracks: List<Track>, select: (Track) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(330.dp)) {
        val featured = tracks.firstOrNull { it.title.contains("KYOTO") } ?: tracks.first()
        val second = tracks.getOrElse(1) { featured }
        val third = tracks.getOrElse(2) { featured }
        Artwork(second, Modifier.align(Alignment.TopStart).padding(top = 8.dp).size(100.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Play ${second.title}") { select(second) }, "${second.title} cover")
        Artwork(featured, Modifier.align(Alignment.Center).width(maxWidth * 0.69f).height(305.dp).rotate(27f)
            .clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = "Play ${featured.title}") { select(featured) }, "${featured.title} cover")
        Artwork(third, Modifier.align(Alignment.BottomEnd).size(104.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Play ${third.title}") { select(third) }, "${third.title} cover")
    }
}

@Composable
private fun AccessCard(permission: () -> Unit, import: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp)) {
            Text("Make room for your favorites", style = MaterialTheme.typography.titleMedium)
            Text("Allow music access to find songs on your phone, or add files yourself.", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(permission) { Text("Find my music") }; TextButton(import) { Text("Add files") }
            }
        }
    }
}

@Composable
private fun SearchScreen(state: MusicState, model: MusicViewModel, import: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(state.tracks, query) { state.tracks.filter { query.isBlank() ||
        "${it.title} ${it.artist} ${it.album}".contains(query, ignoreCase = true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            Text("Find your\nfrequency.", style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(bottom = 24.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                singleLine = true, placeholder = { Text("Songs, artists, albums") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } })
            Text(if (query.isBlank()) "Your whole collection" else "${found.size} results", Modifier.padding(top = 28.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
        items(found, key = { it.id }) { track -> TrackRow(track, state, model, { model.play(found, found.indexOf(track)) }) }
        if (found.isEmpty()) item {
            Text(if (query.isBlank()) "Add a few tracks to start listening." else "No matches. Try another title or artist.",
                Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(import) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add music") }
        }
    }
}

@Composable
private fun LibraryScreen(state: MusicState, model: MusicViewModel, settings: () -> Unit, permission: () -> Unit, import: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    val base = if (filter == 2) state.tracks.filter { it.id in state.favorites } else state.tracks
    val tracks = remember(base, sort, album) {
        val list = base.filter { album == null || it.album == album }
        when (sort) { 1 -> list.sortedBy { it.title.lowercase() }; 2 -> list.sortedBy { it.artist.lowercase() }; else -> list.sortedByDescending { it.added } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your\nlibrary.", Modifier.weight(1f), style = MaterialTheme.typography.displayLarge)
                IconButton(settings) { Icon(Icons.Rounded.Settings, "Settings") }
            }
            Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Songs", "Albums", "Favorites").forEachIndexed { i, name ->
                    FilterChip(filter == i, { filter = i; album = null }, label = { Text(name) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(import, enabled = !state.busy) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add music") }
                Spacer(Modifier.weight(1f))
                IconButton(model::refresh) { Icon(Icons.Rounded.Refresh, "Refresh library") }
                Box {
                    IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort library") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("Recently added", "Title", "Artist").forEachIndexed { i, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { sort = i; sortMenu = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (!state.permission) { AccessCard(permission, import); Spacer(Modifier.height(16.dp)) }
            if (album != null) AssistChip({ album = null }, label = { Text(album!!, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) })
            if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
            Text(if (filter == 1 && album == null) "${tracks.groupBy { it.album }.size} albums" else "${tracks.size} songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
        }
        if (filter == 1 && album == null) {
            items(tracks.groupBy { it.album }.toList(), key = { it.first }) { (name, songs) ->
                Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { album = name }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(songs.first(), Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${songs.size} songs · ${songs.first().artist}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else items(tracks, key = { it.id }) { track -> TrackRow(track, state, model, { model.play(tracks, tracks.indexOf(track)) }) }
        if (tracks.isEmpty() && !state.loading) item {
            if (filter == 2) Text("Tap a heart on any song to keep it here.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else EmptyLibrary(import, model::installStarter)
        }
    }
}

@Composable
private fun EmptyLibrary(import: () -> Unit, starter: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text("Your next favorite is waiting.", style = MaterialTheme.typography.headlineMedium)
        Text("Add audio files or install the three-track NCS starter pack.", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(import) { Text("Add music") }
        TextButton(starter) { Text("Install starter tracks") }
    }
}

@Composable
private fun TrackRow(track: Track, state: MusicState, model: MusicViewModel, play: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val active = state.current?.id == track.id
    val fill by animateColorAsState(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = spring(dampingRatio = 1f, stiffness = 3800f), label = "Track selection")
    Surface(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = play), color = fill) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                Text(track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active && state.playing) Icon(Icons.Rounded.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            else Text(time(track.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${track.title}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (track.id in state.favorites) "Remove favorite" else "Add to favorites") },
                        onClick = { model.favorite(track); menu = false }, leadingIcon = { Icon(Icons.Rounded.FavoriteBorder, null) })
                    DropdownMenuItem(text = { Text("Play next") }, onClick = { model.playNext(track); menu = false }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) })
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: MusicState, model: MusicViewModel, expand: () -> Unit) {
    val track = state.current ?: return
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Open now playing", onClick = expand),
        color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
                }
                IconButton(model::togglePlay) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (state.playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                IconButton(model::next, enabled = state.queue.size > 1) { Icon(Icons.Rounded.SkipNext, "Next", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            LinearProgressIndicator(progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
        }
    }
}

@Composable
private fun BigPlay(playing: Boolean, click: () -> Unit, size: Int = 96, enabled: Boolean = true, label: String? = null) {
    val corner by animateFloatAsState(if (playing) 32f else 50f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f), label = "Play shape")
    FilledIconButton(click, Modifier.size(size.dp), enabled = enabled,
        shape = RoundedCornerShape(corner.coerceIn(0f,50f).toInt()),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary)) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, label ?: if (playing) "Pause" else "Play", Modifier.size(44.dp))
    }
}

@Composable
private fun PlayerContent(state: MusicState, model: MusicViewModel, close: () -> Unit, compact: Boolean = false) {
    val track = state.current ?: return
    var queue by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!compact) IconButton(close) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                    Text("Your music, uninterrupted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ queue = !queue }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, if (queue) "Hide queue" else "Show queue") }
            }
            Spacer(Modifier.height(28.dp))
            val scale by animateFloatAsState(if (state.playing) 1f else 0.96f,
                spring(dampingRatio = 0.8f, stiffness = 380f), label = "Artwork scale")
            Artwork(track, Modifier.fillMaxWidth().aspectRatio(1f).scale(scale).clip(RoundedCornerShape(48.dp)), "${track.title} cover")
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(track.album, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconToggleButton(track.id in state.favorites, { model.favorite(track) }) {
                    Icon(if (track.id in state.favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (track.id in state.favorites) "Remove favorite" else "Add favorite", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Waveform(state.position, state.duration)
            Slider(value = if (dragging) scrub else state.position.toFloat().coerceAtMost(state.duration.toFloat()),
                onValueChange = { dragging = true; scrub = it }, onValueChangeFinished = { model.seek(scrub.toLong()); dragging = false },
                valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), enabled = state.duration > 0,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time(if (dragging) scrub.toLong() else state.position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(time(state.duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconToggleButton(state.shuffle, { model.shuffle() }) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(model::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp)) }
                BigPlay(state.playing, model::togglePlay, size = if (compact) 80 else 96)
                IconButton(model::next, enabled = state.queue.size > 1) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp)) }
                IconButton(model::repeat) { Icon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat: ${when(state.repeat) { Player.REPEAT_MODE_ALL -> "all"; Player.REPEAT_MODE_ONE -> "one"; else -> "off" }}",
                    tint = if (state.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (queue) {
            item { Text("Up next · ${state.queue.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)) }
            items(state.queue.size) { index ->
                val song = state.queue[index]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { model.queuePlay(index) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ model.queueRemove(index) }) { Icon(Icons.Rounded.Close, "Remove ${song.title} from queue") }
                }
            }
        }
    }
}

@Composable
private fun Waveform(position: Long, duration: Long) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "Decorative music waveform" }) {
        val progress = if (duration > 0) position.toFloat() / duration else 0f
        val bars = 64
        repeat(bars) { i ->
            val height = (0.25f + 0.75f * kotlin.math.abs(sin(i * 1.71f) * sin(i * 0.31f))) * size.height
            val x = i * size.width / bars
            drawLine(if (i.toFloat() / bars < progress) active else inactive,
                Offset(x, (size.height-height)/2), Offset(x, (size.height+height)/2), strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun Settings(state: MusicState, model: MusicViewModel, permission: () -> Unit, import: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        item {
            Text("Make it yours.", style = MaterialTheme.typography.headlineLarge)
            Text("Appearance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dark" to "Dark", "light" to "Light", "system" to "System").forEach { (value, label) -> FilterChip(state.theme == value, { model.theme(value) }, label = { Text(label) }) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Material You colors", style = MaterialTheme.typography.titleMedium); Text("Use your wallpaper palette", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(state.dynamicColor, model::dynamicColor)
            }
            Text("Your collection", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            SettingsAction(Icons.Rounded.FolderOpen, "Add audio files", "Copy music into Music/Klang", import, !state.busy)
            SettingsAction(Icons.Rounded.LibraryMusic, "Music access", if (state.permission) "Access enabled · tap to manage permission" else "Discover songs already on your phone", permission)
            SettingsAction(Icons.Rounded.Download, "NCS starter pack", "KYOTO · Recall · EYES ON US", model::installStarter, !state.busy)
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(0,15,30,60)) { minutes -> FilterChip(state.sleepMinutes == minutes, { model.sleep(minutes) }, label = { Text(if (minutes == 0) "Off" else "$minutes min") }) }
            }
            Text("Timer runs while Klang is open or in the background. Closing the app cancels it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 24.dp))
            Text("Klang", style = MaterialTheme.typography.headlineMedium)
            Text("Version 0.1.0 · Created by Lynli", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
            Text("Starter music by Hester, gabriawll & 2frers. Released by NoCopyrightSounds. Artwork from the NCS release pages.",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun SettingsAction(icon: ImageVector, title: String, subtitle: String, click: () -> Unit, enabled: Boolean = true) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = click), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
internal fun time(milliseconds: Long): String = "${milliseconds.coerceAtLeast(0) / 60000}:${((milliseconds.coerceAtLeast(0) / 1000) % 60).toString().padStart(2, '0')}"
