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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.lynli.klang.R
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
    var plugins by rememberSaveable { mutableStateOf(false) }
    var settingsEq by rememberSaveable { mutableStateOf(false) }
    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
    val collections = remember(state.tracks, state.favorites, state.playlists) { musicCollections(state) }
    val selectedCollection = collections.find { it.id == collectionId }
    var lastCollection by remember { mutableStateOf<MusicCollection?>(null) }
    SideEffect { if (selectedCollection != null) lastCollection = selectedCollection }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); model.clearMessage() } }
    BackHandler(expanded || settingsEq || plugins || settings || collectionId != null || destination != 0) {
        when { expanded -> expanded = false; settingsEq -> settingsEq = false; plugins -> plugins = false; settings -> settings = false;
            collectionId != null -> collectionId = null; else -> destination = 0 }
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
                                0 -> HomeScreen(state, model, collections, { settings = true }, onPermission, onImport,
                                    { destination = 2 }, { collectionId = it.id }, bottomSpace)
                                1 -> SearchScreen(state, model, onImport, bottomSpace)
                                else -> LibraryScreen(state, model, { settings = true }, onPermission, onImport,
                                    { collectionId = it.id }, bottomSpace)
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
                SettingsScreen(state, model, onPermission, onImport, { plugins = true }, { settingsEq = true }) { settings = false }
            }
            AnimatedVisibility(collectionId != null,
                enter = slideInHorizontally(spring(0.8f, 200f)) { it } + fadeIn(Effects),
                exit = slideOutHorizontally(spring(0.8f, 200f)) { it } + fadeOut(Effects)) {
                (selectedCollection ?: lastCollection)?.let { collection ->
                    CollectionScreen(collection, state, model, { expanded = true }) { collectionId = null }
                }
            }
            AnimatedVisibility(plugins, enter = slideInHorizontally(spring(0.8f, 200f)) { it } + fadeIn(Effects),
                exit = slideOutHorizontally(spring(0.8f, 200f)) { it } + fadeOut(Effects)) {
                PluginsScreen { plugins = false }
            }
            AnimatedVisibility(settingsEq, enter = slideInHorizontally(spring(0.8f, 200f)) { it } + fadeIn(Effects),
                exit = slideOutHorizontally(spring(0.8f, 200f)) { it } + fadeOut(Effects)) {
                EqualizerScreen({ settingsEq = false }, null, songContext = false)
            }
            AnimatedVisibility(expanded && state.current != null,
                enter = slideInVertically(spring(0.8f, 200f)) { it } + fadeIn(Effects),
                exit = slideOutVertically(spring(0.8f, 200f)) { it } + fadeOut(Effects)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    PlayerContent(state, model, { expanded = false })
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (settings || expanded || plugins || collectionId != null) 16.dp else bottomSpace))
        }
    }
}
private val destinations = listOf("Home" to Icons.Rounded.Home, "Search" to Icons.Rounded.Search, "Library" to Icons.Rounded.LibraryMusic)

@Composable
private fun PageTitle(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        trailing()
    }
}

@Composable
internal fun AccessCard(permission: () -> Unit, import: () -> Unit) {
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
private fun LibraryScreen(state: MusicState, model: MusicViewModel, settings: () -> Unit, permission: () -> Unit, import: () -> Unit, openCollection: (MusicCollection) -> Unit, bottom: Dp) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var creatingPlaylist by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    val tracks = remember(state.tracks, state.favorites, filter, sort) {
        val list = state.tracks
        when (sort) { 1 -> list.sortedBy { it.title.lowercase() }; 2 -> list.sortedBy { it.artist.lowercase() }; else -> list.sortedByDescending { it.added } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottom)) {
        item {
            PageTitle("Library") { UtilityIconButton(settings) { Icon(Icons.Rounded.Settings, "Settings") } }
            ChoiceGroup(listOf("Songs", "Albums", "Playlists"), filter) { filter = it }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (filter == 1) "${albumCollections(tracks).size} albums" else if (filter == 2) "${state.playlists.size + 2} playlists" else "${tracks.size} songs",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (filter == 2) IconButton({ creatingPlaylist = true }) { Icon(Icons.Rounded.Add, "Create playlist") }
                else IconButton(import, enabled = !state.busy) { Icon(Icons.Rounded.Add, "Add music") }
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
            if (state.busy || state.loading) LoadingRow(state)
        }
        if (filter == 1) {
            items(albumCollections(tracks), key = { it.id }) { collection ->
                val name = collection.title
                val songs = collection.tracks
                Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).animateItem().clip(RoundedCornerShape(20.dp)).clickable { openCollection(collection) },
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
        } else if (filter == 2) {
            items(musicCollections(state).filter { !it.album }, key = { it.id }) { collection ->
                PlaylistListItem(collection, Modifier.animateItem()) { openCollection(collection) }
            }
        } else items(tracks, key = { it.id }) { track -> TrackRow(track, state, model,
            { model.play(tracks, tracks.indexOf(track)) }, Modifier.animateItem()) }
        if (tracks.isEmpty() && !state.loading && filter != 2) item { EmptyLibrary(import, model::installStarter) }
        if (filter == 1 && tracks.isNotEmpty() && albumCollections(tracks).isEmpty()) item {
            EmptyState("No albums", "Files with album metadata appear here.")
        }
    }
    if (creatingPlaylist) PlaylistNameDialog("Create playlist", "", { creatingPlaylist = false }) { name ->
        model.createPlaylist(name)?.let { id ->
            creatingPlaylist = false
            openCollection(MusicCollection("playlist:$id", name.trim(), "", emptyList(), editableId = id))
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
internal fun SectionHeading(title: String, action: String? = null, click: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (action != null) TextButton(click) { Text(action) }
    }
}
@Composable
internal fun LoadingRow(state: MusicState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(36.dp)); Spacer(Modifier.width(12.dp))
        Text(if (state.busy) "Adding music…" else "Loading library…", style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable
internal fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
internal fun EmptyLibrary(import: () -> Unit, starter: () -> Unit) {
    EmptyState("No music yet", "Add audio files or install the three-track NCS pack.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(import) { Text("Add music") }; TextButton(starter) { Text("Install tracks") }
    }
}

@Composable
internal fun TrackRow(track: Track, state: MusicState, model: MusicViewModel, play: () -> Unit, modifier: Modifier = Modifier, playbackControls: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf(false) }
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
            if (active && playbackControls) ActiveTrackControls(track, state, model, content)
            else if (active && state.playing) PlayingIndicator(content)
            else Text(time(track.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${track.title}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (track.id in state.favorites) "Remove favorite" else "Add favorite") },
                        onClick = { model.favorite(track); menu = false }, leadingIcon = { Icon(Icons.Rounded.FavoriteBorder, null) })
                    DropdownMenuItem(text = { Text("Add to playlist") },
                        onClick = { menu = false; addToPlaylist = true },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) })
                    DropdownMenuItem(text = { Text("Play next") }, onClick = { model.playNext(track); menu = false },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) })
                }
            }
        }
    }
    if (addToPlaylist) AddToPlaylistDialog(track, state, model) { addToPlaylist = false }

}

@Composable
internal fun MiniPlayer(state: MusicState, model: MusicViewModel, expand: () -> Unit) {
    val track = state.current ?: return
    CoverTheme(track) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClickLabel = "Open now playing", onClick = expand),
        color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
                }
                FavoriteButton(track, state, model, MaterialTheme.colorScheme.onPrimaryContainer)
                IconButton(model::togglePlay) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (state.playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                IconButton(model::next, enabled = state.queue.size > 1) { Icon(Icons.Rounded.SkipNext, "Next", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            LinearProgressIndicator(progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
        }
    }
    }
}

@Composable
internal fun ExpressivePlay(playing: Boolean, click: () -> Unit, size: Dp = 88.dp, enabled: Boolean = true) {
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
    val appScheme = MaterialTheme.colorScheme
    val base = appScheme.surface
    ContentTheme(state.coverColor, state.current) {
        val track = state.current ?: return@ContentTheme
        var queue by rememberSaveable { mutableStateOf(false) }
        var equalizer by rememberSaveable { mutableStateOf(false) }
        var addPlaylist by remember { mutableStateOf(false) }
        var devices by remember { mutableStateOf(false) }
        val context = androidx.compose.ui.platform.LocalContext.current
        if (addPlaylist) AddToPlaylistDialog(track, state, model) { addPlaylist = false }
        if (devices) OutputDevicesDialog { devices = false }
        BackHandler(queue || equalizer) { if (equalizer) equalizer = false else queue = false }
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            colors.primaryContainer.copy(alpha = 0.5f).compositeOver(base),
            colors.surfaceContainerLow, base, base)))) {
            BoxWithConstraints(Modifier.fillMaxSize().then(if (compact) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
                if (queue) {
                    QueuePage(state, model) { queue = false }
                } else {
                    val short = maxHeight < 650.dp
                    val spacing = if (short) 8.dp else 16.dp
                    val playSize = if (short) 72.dp else 88.dp
                    val artLimit = minOf(maxWidth - 56.dp, 380.dp)
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(close) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") }
                            Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            IconButton({ queue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Open queue") }
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = spacing),
                            contentAlignment = Alignment.Center) {
                            val size = minOf(maxHeight, artLimit).coerceAtLeast(1.dp)
                            AnimatedContent(track.id, transitionSpec = {
                                (fadeIn(Effects) + scaleIn(SlowSpatial, initialScale = 0.94f)) togetherWith fadeOut(Effects)
                            }, label = "Cover transition") { id ->
                                val shown = state.tracks.find { it.id == id } ?: track
                                Artwork(shown, Modifier.size(size).clip(RoundedCornerShape(32.dp)),
                                    "${shown.title} cover", large = true)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(track.title, style = if (short) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold, maxLines = if (short) 1 else 2, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium,
                                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!short) Text(track.album, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FavoriteButton(track, state, model, colors.onSurface)
                        }
                        Spacer(Modifier.height(spacing))
                        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh.copy(alpha = 0.75f)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton({ addPlaylist = true }) { Icon(ImageVector.vectorResource(R.drawable.playlist_add), "Add to playlist") }
                                IconButton({}, enabled = false) { Icon(ImageVector.vectorResource(R.drawable.lyrics), "Lyrics unavailable") }
                                IconButton({
                                    val router = android.media.MediaRouter2.getInstance(context)
                                    if (!runCatching { router.showSystemOutputSwitcher() }.getOrDefault(false)) devices = true
                                }) { Icon(ImageVector.vectorResource(R.drawable.audio_output), "Choose playback device") }
                                IconButton({ equalizer = true }) { Icon(ImageVector.vectorResource(R.drawable.equalizer), "Equalizer") }
                            }
                        }
                        Spacer(Modifier.height(spacing))
                        ExpressiveSeekbar(state, model)
                        Spacer(Modifier.height(spacing))
                        Row(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(model::previous, Modifier.weight(1f).height(if (short) 64.dp else 80.dp),
                                shape = RoundedCornerShape(40.dp)) {
                                Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp))
                            }
                            ExpressivePlay(state.playing, model::togglePlay, playSize)
                            FilledTonalIconButton(model::next, Modifier.weight(1f).height(if (short) 64.dp else 80.dp),
                                enabled = state.queue.size > 1, shape = RoundedCornerShape(40.dp)) {
                                Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp))
                            }
                        }
                        Spacer(Modifier.height(if (short) 8.dp else 24.dp))
                    }
                }
            }
            // Cover-themed EQ overlays the whole player, including the system-bar regions.
            AnimatedVisibility(equalizer, modifier = Modifier.fillMaxSize(),
                enter = slideInHorizontally(spring(0.8f, 200f)) { it } + fadeIn(Effects),
                exit = slideOutHorizontally(spring(0.8f, 200f)) { it } + fadeOut(Effects)) {
                EqualizerScreen({ equalizer = false }, track.title, songContext = true)
            }
        }
    }
}

@Composable
private fun ExpressiveSeekbar(state: MusicState, model: MusicViewModel) {
    var dragging by remember(state.current?.id) { mutableStateOf(false) }
    var scrub by remember(state.current?.id) { mutableFloatStateOf(0f) }
    val slider = remember(state.duration) { SliderState(trackRange = 0f..state.duration.coerceAtLeast(1).toFloat()) }
    slider.value = if (dragging) scrub else state.position.toFloat().coerceAtMost(state.duration.toFloat())
    val motionEnabled = motionEnabled()
    val amplitude by animateFloatAsState(if (state.playing && motionEnabled) 1f else 0f, FastSpatial, label = "Wave amplitude")
    val phase = remember { Animatable(0f) }
    LaunchedEffect(state.playing, motionEnabled) {
        if (state.playing && motionEnabled) while (true) {
            phase.snapTo(0f); phase.animateTo((2 * PI).toFloat(), tween(1400, easing = LinearEasing))
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outlineVariant
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Slider(state = slider, onValueChange = { dragging = true; scrub = it },
        onValueChangeFinished = { model.seek(scrub.toLong()); dragging = false }, enabled = state.duration > 0,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
        thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(primary)) },
        track = { value ->
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                if (rtl) {
                    // Match native slider direction for right-to-left layouts.
                    drawContext.transform.translate(size.width, 0f)
                    drawContext.transform.scale(-1f, 1f, Offset.Zero)
                }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(time(if (dragging) scrub.toLong() else state.position), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row {
            IconToggleButton(state.shuffle, { model.shuffle() }) {
                Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(model::repeat) {
                Icon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat ${when(state.repeat) { Player.REPEAT_MODE_ONE -> "one"; Player.REPEAT_MODE_ALL -> "all"; else -> "off" }}",
                    tint = if (state.repeat != Player.REPEAT_MODE_OFF) primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(time(state.duration), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun QueuePage(state: MusicState, model: MusicViewModel, back: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item { PageTitle("Queue") { UtilityIconButton(back) { Icon(Icons.Rounded.Close, "Close queue") } } }
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
private fun SettingsScreen(state: MusicState, model: MusicViewModel, permission: () -> Unit, import: () -> Unit, plugins: () -> Unit, equalizer: () -> Unit, back: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text("Settings", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                SectionHeading("Appearance")
                ChoiceGroup(listOf("Dark", "Light", "System"), listOf("dark", "light", "system").indexOf(state.theme).coerceAtLeast(0)) {
                    model.theme(listOf("dark", "light", "system")[it])
                }
                Spacer(Modifier.height(16.dp))
                SettingSwitch("Cover colors", "Use artwork colors in Now playing and albums", state.coverColor, model::coverColor)
                SettingSwitch("Wallpaper colors", "Use your phone’s Material You palette", state.dynamicColor, model::dynamicColor)
                SectionHeading("Library")
                SettingsAction(Icons.Rounded.FolderOpen, "Add audio files", "Copy files into Music/Klang", import, !state.busy)
                SettingsAction(Icons.Rounded.LibraryMusic, "Music access", if (state.permission) "Allowed · manage in Android settings" else "Allow access to your phone’s music", permission)
                SettingsAction(Icons.Rounded.Download, "Starter tracks", "KYOTO, Recall and EYES ON US", model::installStarter, !state.busy)
                SettingsAction(Icons.Rounded.Refresh, "Refresh library", "Scan for added or removed files", model::refresh)
                SectionHeading("Extensions")
                SettingsAction(ImageVector.vectorResource(R.drawable.extension), "Plugins", "Browse planned music integrations", plugins)
                SectionHeading("Playback")
                SettingsAction(ImageVector.vectorResource(R.drawable.equalizer), "Equalizer", "Presets and headphone sound", equalizer)
                Text("Sleep timer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                ChoiceGroup(listOf("Off", "15 min", "30 min", "60 min"), listOf(0, 15, 30, 60).indexOf(state.sleepMinutes).coerceAtLeast(0)) {
                    model.sleep(listOf(0, 15, 30, 60)[it])
                }
                Text("Closing the app cancels the timer.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionHeading("About")
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Klang", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("0.5.2 · Created by Lynli", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
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
