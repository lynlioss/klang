@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.data.Track
import kotlin.math.PI
import kotlin.math.sin

private val FastSpatial = spring<Float>(dampingRatio = 0.6f, stiffness = 800f)
private val SlowSpatial = spring<Float>(dampingRatio = 0.8f, stiffness = 200f)
private val Effects = spring<Float>(dampingRatio = 1f, stiffness = 800f)

@Composable
fun KlangApp(state: MusicState, model: MusicViewModel, onPermission: () -> Unit, onImport: () -> Unit) {
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); model.clearMessage() } }
    BackHandler(expanded || settings || destination != 0) {
        when { expanded -> expanded = false; settings -> settings = false; else -> destination = 0 }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val bottomSpace = if (wide) 24.dp else if (state.current != null) 184.dp else 112.dp
            // Screens extend under the dock. Only scroll content gets a trailing spacer.
            Row(Modifier.fillMaxSize().statusBarsPadding()) {
                if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Spacer(Modifier.height(24.dp))
                    destinations.forEachIndexed { i, (label, icon) ->
                        NavigationRailItem(destination == i, { destination = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Crossfade(destination, animationSpec = Effects, label = "Destination") { page ->
                        holder.SaveableStateProvider(page) {
                            when (page) {
                                0 -> MixScreen(state, model, { settings = true }, onPermission, onImport,
                                    { destination = 2 }, { expanded = true }, bottomSpace)
                                1 -> SearchScreen(state, model, onImport, bottomSpace)
                                else -> LibraryScreen(state, model, { settings = true }, onPermission, onImport, bottomSpace)
                            }
                        }
                    }
                }
                if (wide && state.current != null) Surface(Modifier.width(360.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    PlayerContent(state, model, {}, compact = true)
                }
            }
            if (!wide) Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .imePadding().widthIn(max = 520.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                AnimatedVisibility(state.current != null, enter = expandVertically(spring(0.8f, 380f)) + fadeIn(Effects),
                    exit = shrinkVertically(spring(0.8f, 380f)) + fadeOut(Effects)) {
                    Column { MiniPlayer(state, model) { expanded = true }; Spacer(Modifier.height(8.dp)) }
                }
                Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 6.dp) {
                    ShortNavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
                        destinations.forEachIndexed { index, (label, icon) ->
                            ShortNavigationBarItem(destination == index, { destination = index },
                                icon = { Icon(icon, null) }, label = { Text(label) })
                        }
                    }
                }
            }
            AnimatedVisibility(settings, enter = slideInHorizontally(spring(0.8f, 200f)) { it } + fadeIn(Effects),
                exit = slideOutHorizontally(spring(0.8f, 200f)) { it } + fadeOut(Effects)) {
                SettingsScreen(state, model, onPermission, onImport) { settings = false }
            }
            AnimatedVisibility(expanded && state.current != null,
                enter = slideInVertically(spring(0.8f, 200f)) { it } + fadeIn(Effects),
                exit = slideOutVertically(spring(0.8f, 200f)) { it } + fadeOut(Effects)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    PlayerContent(state, model, { expanded = false })
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (settings || expanded) 16.dp else bottomSpace))
        }
    }
}
private val destinations = listOf("Mix" to Icons.Rounded.GraphicEq, "Search" to Icons.Rounded.Search, "Library" to Icons.Rounded.LibraryMusic)

@Composable
private fun PageTitle(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        trailing()
    }
}

@Composable
private fun MixScreen(state: MusicState, model: MusicViewModel, settings: () -> Unit, permission: () -> Unit,
    import: () -> Unit, library: () -> Unit, player: () -> Unit, bottom: Dp) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottom)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text("Klang", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                FilledTonalIconButton(settings) { Icon(Icons.Rounded.Settings, "Settings") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your\nMix", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                    Text("${state.tracks.size} songs", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ExpressivePlay(state.playing, {
                    if (state.current != null) model.togglePlay() else model.play(state.tracks, shuffled = true)
                }, 104.dp, state.connected && state.tracks.isNotEmpty())
            }
            Spacer(Modifier.height(20.dp))
        }
        if (state.loading || state.busy) item { LoadingRow(state) }
        if (state.tracks.isNotEmpty()) item {
            CoverCollage(state.tracks) { track -> model.play(state.tracks, state.tracks.indexOf(track)); player() }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ model.play(state.tracks, shuffled = true) }, Modifier.weight(1f), enabled = state.connected) {
                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Shuffle")
                }
                FilledTonalButton(import, Modifier.weight(1f), enabled = !state.busy) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add music")
                }
            }
        }
        if (!state.permission) item { Spacer(Modifier.height(20.dp)); AccessCard(permission, import) }
        item { SectionHeading("Recently added", "See all", library) }
        items(state.tracks.take(6), key = { it.id }) { track ->
            TrackRow(track, state, model, { model.play(state.tracks, state.tracks.indexOf(track)) }, Modifier.animateItem())
        }
        if (state.tracks.isEmpty() && !state.loading && !state.busy) item { EmptyLibrary(import, model::installStarter) }
    }
}

@Composable
private fun CoverCollage(tracks: List<Track>, select: (Track) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(280.dp)) {
        val featured = tracks.firstOrNull { it.title.contains("KYOTO") } ?: tracks.first()
        val second = tracks.getOrElse(1) { featured }
        val third = tracks.getOrElse(2) { featured }
        Artwork(second, Modifier.align(Alignment.TopStart).size(88.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Play ${second.title}") { select(second) })
        Artwork(featured, Modifier.align(Alignment.Center).width(maxWidth * 0.64f).height(255.dp).rotate(22f)
            .clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = "Play ${featured.title}") { select(featured) })
        Artwork(third, Modifier.align(Alignment.BottomEnd).size(92.dp).clip(RoundedCornerShape(32.dp))
            .clickable(role = Role.Button, onClickLabel = "Play ${third.title}") { select(third) })
    }
}

@Composable
private fun AccessCard(permission: () -> Unit, import: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Text("Music access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Find audio on your phone, or choose files to add.", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(permission) { Text("Allow access") }; TextButton(import) { Text("Add files") }
            }
        }
    }
}

@Composable
private fun SearchScreen(state: MusicState, model: MusicViewModel, import: () -> Unit, bottom: Dp) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val found = remember(state.tracks, query, filter) {
        state.tracks.filter { (filter == 0 || it.id in state.favorites) &&
            (query.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(query.trim(), ignoreCase = true)) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        PageTitle("Search")
        TextField(query, { query = it }, Modifier.fillMaxWidth(), shape = CircleShape, singleLine = true,
            placeholder = { Text("Songs, artists, albums") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == 0, { filter = 0 }, label = { Text("All songs") })
            FilterChip(filter == 1, { filter = 1 }, label = { Text("Favorites") }, leadingIcon = { Icon(Icons.Rounded.Favorite, null, Modifier.size(18.dp)) })
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (query.isBlank()) "Songs" else "Results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${found.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottom)) {
            items(found, key = { it.id }) { track -> TrackRow(track, state, model,
                { model.play(found, found.indexOf(track)) }, Modifier.animateItem()) }
            if (found.isEmpty()) item {
                EmptyState(if (query.isBlank()) "No songs" else "No results",
                    if (query.isBlank()) "Add audio files to search your library." else "Try a different title, artist or album.")
                if (query.isBlank()) FilledTonalButton(import) { Text("Add music") }
            }
        }
    }
}

@Composable
private fun LibraryScreen(state: MusicState, model: MusicViewModel, settings: () -> Unit, permission: () -> Unit, import: () -> Unit, bottom: Dp) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    val tracks = remember(state.tracks, state.favorites, filter, sort, album) {
        val list = state.tracks.filter { (filter != 2 || it.id in state.favorites) && (album == null || it.album == album) }
        when (sort) { 1 -> list.sortedBy { it.title.lowercase() }; 2 -> list.sortedBy { it.artist.lowercase() }; else -> list.sortedByDescending { it.added } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottom)) {
        item {
            PageTitle("Library") { FilledTonalIconButton(settings) { Icon(Icons.Rounded.Settings, "Settings") } }
            ChoiceGroup(listOf("Songs", "Albums", "Favorites"), filter) { filter = it; album = null }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (filter == 1 && album == null) "${tracks.groupBy { it.album }.size} albums" else "${tracks.size} songs",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(import, enabled = !state.busy) { Icon(Icons.Rounded.Add, "Add music") }
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
            if (!state.permission) { AccessCard(permission, import); Spacer(Modifier.height(16.dp)) }
            if (album != null) AssistChip({ album = null }, label = { Text(album!!, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) })
            if (state.busy || state.loading) LoadingRow(state)
        }
        if (filter == 1 && album == null) {
            items(tracks.groupBy { it.album }.toList(), key = { it.first }) { (name, songs) ->
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).animateItem().clip(RoundedCornerShape(20.dp)).clickable { album = name },
                    shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(songs.first(), Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${songs.first().artist} · ${songs.size} songs", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else items(tracks, key = { it.id }) { track -> TrackRow(track, state, model,
            { model.play(tracks, tracks.indexOf(track)) }, Modifier.animateItem()) }
        if (tracks.isEmpty() && !state.loading) item {
            if (filter == 2) EmptyState("No favorites", "Tap the heart on a song to save it here.")
            else EmptyLibrary(import, model::installStarter)
        }
    }
}

@Composable
private fun ChoiceGroup(labels: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { i, label ->
            val shapes = when (i) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(checked = selected == i, onCheckedChange = { choose(i) }, modifier = Modifier.weight(1f), shapes = shapes) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, action: String? = null, click: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (action != null) TextButton(click) { Text(action) }
    }
}
@Composable
private fun LoadingRow(state: MusicState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(36.dp)); Spacer(Modifier.width(12.dp))
        Text(if (state.busy) "Adding music…" else "Loading library…", style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun EmptyLibrary(import: () -> Unit, starter: () -> Unit) {
    EmptyState("No music yet", "Add audio files or install the three-track NCS pack.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(import) { Text("Add music") }; TextButton(starter) { Text("Install tracks") }
    }
}

@Composable
private fun TrackRow(track: Track, state: MusicState, model: MusicViewModel, play: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val active = state.current?.id == track.id
    val fill by animateColorAsState(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        spring(1f, 3800f), label = "Track selection")
    val content = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = play), color = fill) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = content)
                Text(track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) content else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active && state.playing) Icon(Icons.Rounded.GraphicEq, "Playing", tint = content, modifier = Modifier.size(20.dp))
            else Text(time(track.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${track.title}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (track.id in state.favorites) "Remove favorite" else "Add favorite") },
                        onClick = { model.favorite(track); menu = false }, leadingIcon = { Icon(Icons.Rounded.FavoriteBorder, null) })
                    DropdownMenuItem(text = { Text("Play next") }, onClick = { model.playNext(track); menu = false },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) })
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: MusicState, model: MusicViewModel, expand: () -> Unit) {
    val track = state.current ?: return
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClickLabel = "Open now playing", onClick = expand),
        color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
                }
                IconButton(model::togglePlay) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (state.playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                IconButton(model::next, enabled = state.queue.size > 1) { Icon(Icons.Rounded.SkipNext, "Next", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            LinearProgressIndicator(progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
        }
    }
}

@Composable
private fun ExpressivePlay(playing: Boolean, click: () -> Unit, size: Dp = 88.dp, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateFloatAsState(if (pressed) 22f else if (playing) 32f else 50f, FastSpatial, label = "Play shape")
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, FastSpatial, label = "Play press")
    FilledIconButton(click, Modifier.size(size).scale(scale), enabled = enabled, interactionSource = source,
        shape = RoundedCornerShape(corner.coerceIn(0f, 50f).toInt()),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(40.dp))
    }
}

@Composable
private fun PlayerContent(state: MusicState, model: MusicViewModel, close: () -> Unit, compact: Boolean = false) {
    val track = state.current ?: return
    var queue by rememberSaveable { mutableStateOf(false) }
    BackHandler(queue) { queue = false }
    BoxWithConstraints(Modifier.fillMaxSize().then(if (compact) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
        val artworkSize = minOf(maxWidth - 72.dp, maxHeight * 0.31f, 320.dp).coerceAtLeast(120.dp)
        AnimatedContent(queue, transitionSpec = { fadeIn(Effects) togetherWith fadeOut(Effects) }, label = "Player queue") { showQueue ->
            if (showQueue) QueuePage(state, model) { queue = false }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(close) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") }
                        Text("Now playing", Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        FilledTonalIconButton({ queue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Open queue") }
                    }
                    AnimatedContent(track.id, transitionSpec = {
                        (fadeIn(Effects) + scaleIn(SlowSpatial, initialScale = 0.92f)) togetherWith fadeOut(Effects)
                    }, label = "Cover transition") { id ->
                        val shown = state.tracks.find { it.id == id } ?: track
                        Artwork(shown, Modifier.size(artworkSize).clip(RoundedCornerShape(32.dp)), "${shown.title} cover")
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(track.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(track.artist, Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(track.album, Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    ExpressiveSeekbar(state, model)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(model::previous, Modifier.weight(1f).height(80.dp), shape = RoundedCornerShape(40.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp))
                        }
                        ExpressivePlay(state.playing, model::togglePlay, 88.dp)
                        FilledTonalIconButton(model::next, Modifier.weight(1f).height(80.dp), enabled = state.queue.size > 1, shape = RoundedCornerShape(40.dp)) {
                            Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModeButton(state.shuffle, Icons.Rounded.Shuffle, "Shuffle", model::shuffle)
                            ModeButton(state.repeat != Player.REPEAT_MODE_OFF, if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                "Repeat ${when(state.repeat) { Player.REPEAT_MODE_ONE -> "one"; Player.REPEAT_MODE_ALL -> "all"; else -> "off" }}", model::repeat)
                            ModeButton(track.id in state.favorites, if (track.id in state.favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                if (track.id in state.favorites) "Remove favorite" else "Add favorite", { model.favorite(track) })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ModeButton(selected: Boolean, icon: ImageVector, label: String, click: () -> Unit) {
    val radius by animateFloatAsState(if (selected) 28f else 16f, FastSpatial, label = "Mode shape")
    FilledIconToggleButton(selected, { click() }, Modifier.width(72.dp).height(48.dp), shape = RoundedCornerShape(radius.dp),
        colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface, checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer)) { Icon(icon, label) }
}

@Composable
private fun ExpressiveSeekbar(state: MusicState, model: MusicViewModel) {
    var dragging by remember(state.current?.id) { mutableStateOf(false) }
    var scrub by remember(state.current?.id) { mutableFloatStateOf(0f) }
    val slider = remember(state.duration) { SliderState(trackRange = 0f..state.duration.coerceAtLeast(1).toFloat()) }
    slider.value = if (dragging) scrub else state.position.toFloat().coerceAtMost(state.duration.toFloat())
    val amplitude by animateFloatAsState(if (state.playing) 1f else 0f, FastSpatial, label = "Wave amplitude")
    val context = androidx.compose.ui.platform.LocalContext.current
    val motionEnabled = remember { android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    val phase = remember { Animatable(0f) }
    LaunchedEffect(state.playing, motionEnabled) {
        if (state.playing && motionEnabled) while (true) {
            phase.snapTo(0f); phase.animateTo((2 * PI).toFloat(), tween(1400, easing = LinearEasing))
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outlineVariant
    Slider(state = slider, onValueChange = { dragging = true; scrub = it },
        onValueChangeFinished = { model.seek(scrub.toLong()); dragging = false }, enabled = state.duration > 0,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
        thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(primary)) },
        track = { value ->
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val fraction = (value.value / state.duration.coerceAtLeast(1)).coerceIn(0f, 1f)
                val end = size.width * fraction
                val mid = size.height / 2
                if (end + 12.dp.toPx() < size.width) drawLine(muted, Offset(end + 12.dp.toPx(), mid), Offset(size.width, mid), 4.dp.toPx(), StrokeCap.Round)
                val path = Path()
                val wavelength = 32.dp.toPx()
                val waveHeight = 3.dp.toPx() * amplitude
                var x = 0f
                while (x <= end) {
                    val y = mid + sin(x / wavelength * (2 * PI).toFloat() + phase.value) * waveHeight
                    if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                    x += 1.dp.toPx()
                }
                drawPath(path, primary, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(time(if (dragging) scrub.toLong() else state.position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time(state.duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QueuePage(state: MusicState, model: MusicViewModel, back: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item { PageTitle("Queue") { FilledTonalIconButton(back) { Icon(Icons.Rounded.Close, "Close queue") } } }
        items(state.queue.size) { index ->
            val song = state.queue[index]
            Surface(Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(16.dp)).clickable { model.queuePlay(index) },
                color = if (index == state.queueIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ model.queueRemove(index) }) { Icon(Icons.Rounded.Close, "Remove ${song.title}") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: MusicState, model: MusicViewModel, permission: () -> Unit, import: () -> Unit, back: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text("Settings", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                SectionHeading("Appearance")
                ChoiceGroup(listOf("Dark", "Light", "System"), listOf("dark", "light", "system").indexOf(state.theme).coerceAtLeast(0)) {
                    model.theme(listOf("dark", "light", "system")[it])
                }
                Spacer(Modifier.height(16.dp))
                SettingSwitch("Album colors", "Build the Material palette from the current cover", state.coverColor, model::coverColor)
                SettingSwitch("Wallpaper colors", "Use your phone’s Material You palette", state.dynamicColor, model::dynamicColor, enabled = !state.coverColor)
                SectionHeading("Library")
                SettingsAction(Icons.Rounded.FolderOpen, "Add audio files", "Copy files into Music/Klang", import, !state.busy)
                SettingsAction(Icons.Rounded.LibraryMusic, "Music access", if (state.permission) "Allowed · manage in Android settings" else "Allow access to your phone’s music", permission)
                SettingsAction(Icons.Rounded.Download, "Starter tracks", "KYOTO, Recall and EYES ON US", model::installStarter, !state.busy)
                SettingsAction(Icons.Rounded.Refresh, "Refresh library", "Scan for added or removed files", model::refresh)
                SectionHeading("Playback")
                Text("Sleep timer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                ChoiceGroup(listOf("Off", "15 min", "30 min", "60 min"), listOf(0, 15, 30, 60).indexOf(state.sleepMinutes).coerceAtLeast(0)) {
                    model.sleep(listOf(0, 15, 30, 60)[it])
                }
                Text("Closing the app cancels the timer.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionHeading("About")
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Klang", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("0.2.0 · Created by Lynli", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        Text("Music by HESTER, gabriawll and 2frers. Released by NoCopyrightSounds.", Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, change, enabled = enabled)
        }
    }
}
@Composable
private fun SettingsAction(icon: ImageVector, title: String, subtitle: String, click: () -> Unit, enabled: Boolean = true) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = click), color = MaterialTheme.colorScheme.surfaceContainerLow) {
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
