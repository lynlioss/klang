@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.data.Track
import app.lynli.klang.plugins.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun SourceBadge(track: Track, modifier: Modifier = Modifier) {
    val label = if (track.providerId != null && track.offline) "On Device · ${track.sourceLabel}" else track.sourceLabel
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (track.providerId != null && !track.offline && track.sourceBadge == "spotify")
            Icon(painterResource(R.drawable.spotify), label, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else Icon(if (track.offline) Icons.Rounded.PhoneAndroid else Icons.Rounded.MusicNote, label, Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun copyMusicLink(context: Context, label: String, url: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, url))
}

@Composable
internal fun MusicSourceChips(model: MusicViewModel, root: String, modifier: Modifier = Modifier) {
    val config by model.plugins.collectAsStateWithLifecycle()
    val catalog by model.pluginCatalog.collectAsStateWithLifecycle()
    val sources = catalog.installed.filter { it.manifest.musicSource != null && it.manifest.id in config.enabled }
    if (sources.isNotEmpty()) LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { AssistChip({}, label = { Text("On Device") }, leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp)) }) }
        items(sources, key = { it.manifest.id }) { source ->
            AssistChip({ model.musicSources.open(source.manifest.id, root = root) }, label = { Text(source.manifest.name) },
                leadingIcon = { if (source.manifest.musicSource?.badge == "spotify") Icon(painterResource(R.drawable.spotify), null, Modifier.size(18.dp))
                    else Icon(Icons.Rounded.MusicNote, null, Modifier.size(18.dp)) })
        }
    }
}

@Composable
internal fun MusicSourceOverlay(state: MusicState, model: MusicViewModel, player: () -> Unit) {
    val destination by model.musicSources.destination.collectAsStateWithLifecycle()
    destination?.let { target ->
        Box(Modifier.fillMaxSize()) {
            MusicSourceScreen(target, state, model) { model.musicSources.dismiss() }
            if (state.current != null) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .widthIn(max = 520.dp).padding(horizontal = 16.dp, vertical = 8.dp)) { MiniPlayer(state, model, player) }
        }
    }
}

/** Reusable native renderer for every API 1 source; source-specific labels come from the manifest. */
@Composable
internal fun MusicSourceScreen(destination: SourceDestination, state: MusicState, model: MusicViewModel, back: () -> Unit) {
    val config by model.plugins.collectAsStateWithLifecycle()
    val catalog by model.pluginCatalog.collectAsStateWithLifecycle()
    val plugin = catalog.installed.firstOrNull { it.manifest.id == destination.providerId }?.manifest
    val spec = plugin?.musicSource
    if (plugin == null || spec == null || plugin.id !in config.enabled) {
        LaunchedEffect(Unit) { back() }; return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val controller = model.musicSources
    val resume by controller.resumes.collectAsStateWithLifecycle()
    val offlineChanges by controller.changes.collectAsStateWithLifecycle()
    var entityId by rememberSaveable(destination) { mutableStateOf(destination.entityId) }
    var root by rememberSaveable(destination) { mutableStateOf(destination.root) }
    var query by rememberSaveable(destination) { mutableStateOf("") }
    var kind by rememberSaveable(destination) { mutableStateOf(if (destination.root == "search") "all" else "playlist") }
    var history by remember { mutableStateOf<List<String?>>(emptyList()) }
    var page by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var mutation by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf<Map<String, Track>>(emptyMap()) }
    val entity = page?.optJSONObject("entity")
    fun go(id: String) { history = history + entityId; entityId = id; offset = 0; error = null }
    fun goBack() { if (history.isNotEmpty()) { entityId = history.last(); history = history.dropLast(1); offset = 0 } else if (entityId != null) entityId = null else back() }
    BackHandler { goBack() }
    suspend fun load(next: Int): JSONObject {
        val args = JSONObject().put("offset", next).put("limit", 50)
        val op = if (entityId != null) { args.put("id", entityId); "lookup" } else when (root) {
            "search" -> { args.put("query", query).put("kind", kind); "search" }
            "library" -> { args.put("kind", kind); "library" }
            else -> "home"
        }
        return controller.call(plugin.id, op, args)
    }
    LaunchedEffect(entityId, root, query, kind, refresh, resume, config.enabled) {
        loading = true; error = null; page = null; tracks = emptyMap(); offset = 0
        if (entityId == null && root == "search" && query.isBlank()) { loading = false; return@LaunchedEffect }
        try {
            if (entityId == null && root == "search") delay(350)
            page = load(0)
            tracks = page?.optJSONArray("items")?.jsonObjects().orEmpty().filter { it.optString("kind") == "track" }
                .associate { item -> item.getString("id") to controller.track(plugin.id, item) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    LaunchedEffect(offlineChanges) {
        tracks = tracks.mapValues { (_, track) -> controller.storedTrack(track.id) ?: track }
    }
    fun change(operation: String, args: JSONObject) {
        if (mutation) return
        mutation = true; error = null
        scope.launch {
            try {
                page?.optString("accountRevision")?.takeIf { it.isNotBlank() }?.let { args.put("accountRevision", it) }
                controller.call(plugin.id, operation, args); refresh++
            }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
            finally { mutation = false }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && entityId != null) scope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    val bitmap = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        val ratio = minOf(1f, 512f / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                    }
                    java.io.ByteArrayOutputStream().use { bytes ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bytes); bitmap.recycle()
                        require(bytes.size() <= 250_000) { "Choose a smaller cover image." }
                        android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
                    }
                }
                change("playlist.cover", JSONObject().put("id", entityId).put("imageBase64", image))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
        }
    }
    val items = page?.optJSONArray("items")?.jsonObjects().orEmpty()
    val playable = items.filter { it.optString("kind") == "track" && it.optBoolean("available", true) }.mapNotNull { tracks[it.optString("id")] }
    fun playCollection(selected: Int? = null, shuffled: Boolean = false) {
        if (loading) return
        if (entityId == null || page?.isNull("nextOffset") != false) {
            model.play(playable, selected, shuffled, "${plugin.id}:${entityId ?: root}"); return
        }
        scope.launch {
            loading = true
            try { model.play(controller.collection(plugin.id, requireNotNull(entityId)), selected, shuffled, "${plugin.id}:$entityId") }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                UtilityIconButton({ goBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(plugin.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMediumEmphasized)
                UtilityIconButton({ controller.settings(plugin.id) }) { Icon(Icons.Rounded.Settings, "${plugin.name} settings") }
                Box {
                    UtilityIconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Source options") }
                    DropdownMenu(menu, { menu = false }, shape = KlangMenuShape) {
                        DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; refresh++ })
                        entity?.optString("url")?.takeIf { it.startsWith("https://") }?.let { url ->
                            DropdownMenuItem(text = { Text("Copy ${spec.linkLabel} link") }, onClick = { menu = false; copyMusicLink(context, spec.linkLabel, url) })
                        }
                        if (entity?.optString("kind") in setOf("album", "artist") && "library.write" in spec.capabilities) {
                            val artist = entity?.optString("kind") == "artist"
                            DropdownMenuItem(text = { Text(if (artist) "Follow artist" else "Save album") }, onClick = {
                                menu = false; change("library.save", JSONObject().put("ids", JSONArray().put(entityId)))
                            })
                            DropdownMenuItem(text = { Text(if (artist) "Unfollow artist" else "Remove saved album") }, onClick = {
                                menu = false; change("library.remove", JSONObject().put("ids", JSONArray().put(entityId)))
                            })
                        }
                        if (entity?.optString("kind") == "playlist") {
                            if (entity.optBoolean("owned") && "playlist.edit" in spec.capabilities)
                                DropdownMenuItem(text = { Text("Edit playlist") }, onClick = { menu = false; edit = true })
                            if (entity.optBoolean("owned") && "playlist.cover" in spec.capabilities)
                                DropdownMenuItem(text = { Text("Change cover") }, onClick = { menu = false; coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            if ("playlist.follow" in spec.capabilities) {
                                DropdownMenuItem(text = { Text("Save playlist") }, onClick = { menu = false; change("playlist.follow", JSONObject().put("id", entityId)) })
                                DropdownMenuItem(text = { Text(if (entity.optBoolean("owned")) "Remove from your library" else "Unfollow playlist") }, onClick = { menu = false; change("playlist.unfollow", JSONObject().put("id", entityId)) })
                            }
                            if ("playlist.create" in spec.capabilities)
                                DropdownMenuItem(text = { Text("Copy to a new playlist") }, onClick = { menu = false; copying = true; create = true })
                        }
                    }
                }
            }
            if (entityId == null) {
                LazyRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("home" to "For you", "search" to "Search", "library" to "Your library")) { (value, label) ->
                        FilterChip(root == value, { root = value; kind = if (value == "search") "all" else "playlist" }, label = { Text(label) }, shapes = klangFilterChipShapes())
                    }
                }
                if (root == "search") OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    label = { Text("Search ${plugin.name}") }, singleLine = true, shape = CircleShape,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } })
                if (root in listOf("library", "search")) LazyRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val kinds = if (root == "search") listOf("all" to "All", "track" to "Songs", "album" to "Albums", "artist" to "Artists", "playlist" to "Playlists")
                        else listOf("playlist" to "Playlists", "track" to "Liked songs", "album" to "Albums", "artist" to "Artists", "folder" to "Folders")
                    items(kinds) { (value, label) -> FilterChip(kind == value, { kind = value }, label = { Text(label) }, shapes = klangFilterChipShapes()) }
                }
            }
            if (loading || mutation) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            error?.let { text ->
                Surface(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Text(text); Row { TextButton({ refresh++ }) { Text("Retry") }; TextButton({ controller.settings(plugin.id) }) { Text("Account settings") } }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp,
                bottom = if (state.current != null) 112.dp else 24.dp)) {
                item {
                    Text(page?.optString("title") ?: if (root == "search") "Find your next song" else plugin.name,
                        Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.headlineLargeEmphasized)
                    entity?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    page?.optString("notice")?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    page?.optInt("unavailableCount")?.takeIf { it > 0 }?.let { Text("$it unavailable entries", style = MaterialTheme.typography.bodySmall) }
                    if (entityId != null && playable.isNotEmpty()) Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button({ playCollection() }, enabled = !loading) { Text("Play") }
                        FilledTonalButton({ playCollection(shuffled = true) }, enabled = !loading) { Text("Shuffle") }
                        if ("offline.download" in spec.capabilities) IconButton({ scope.launch {
                            try {
                                loading = true
                                val songs = controller.collection(plugin.id, requireNotNull(entityId))
                                controller.download(songs, page?.optString("title").orEmpty(), collection = true)
                            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
                        } }, enabled = !loading && !mutation) { Icon(Icons.Rounded.Download, "Download collection") }
                    }
                    if (entityId == null && root == "library" && "playlist.create" in spec.capabilities)
                        FilledTonalButton({ copying = false; create = true }, Modifier.padding(bottom = 12.dp)) { Icon(Icons.Rounded.Add, null); Text("Create playlist") }
                }
                items(items.size, key = { index -> items[index].optString("uid").takeIf { it.isNotBlank() && it != "null" } ?: "$index:${items[index].optString("id")}" }) { index ->
                    val item = items[index]
                    val track = tracks[item.optString("id")]
                    if (item.optString("kind") == "track" && track != null) {
                        val selected = items.take(index).count { it.optString("kind") == "track" && it.optBoolean("available", true) }
                        SourceTrackRow(item, track, model, plugin.id, spec, state, { playCollection(selected) },
                            go = ::go, playlist = entity?.takeIf { it.optString("kind") == "playlist" && it.optBoolean("editable") },
                            moveBefore = items.getOrNull(index - 1)?.optString("uid"), moveAfter = items.getOrNull(index + 2)?.optString("uid"),
                            canUp = index > 0, canDown = index < items.lastIndex, change = ::change)
                    } else CatalogEntityRow(item) { go(item.getString("id")) }
                }
                if (!loading && items.isEmpty() && error == null) item { EmptyState("Nothing here yet", if (root == "search") "Search songs, albums, artists or playlists." else "Refresh to check your library.") }
                if (page != null && !page!!.isNull("nextOffset")) item {
                    TextButton({ if (!loading) scope.launch {
                        loading = true
                        try {
                            val next = page!!.getInt("nextOffset")
                            require(next > offset) { "The source returned invalid pagination." }
                            val more = load(next)
                            val incoming = more.getJSONArray("items").jsonObjects()
                            page = more.put("items", JSONArray(items + incoming))
                            tracks = tracks + incoming.filter { it.optString("kind") == "track" }
                                .associate { it.getString("id") to controller.track(plugin.id, it) }
                            offset = next
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
                    } }, enabled = !loading) { Text("Load more") }
                }
            }
        }
    }
    if (create || edit) SourcePlaylistEditor(entity.takeIf { edit }, create,
        supportsCollaboration = "playlist.collaboration" in spec.capabilities, { create = false; edit = false }) { args ->
        if (edit) { args.put("id", entityId); change("playlist.edit", args); edit = false }
        else {
            create = false; scope.launch {
                mutation = true
                try {
                    val ids = if (copying && entityId != null) controller.collection(plugin.id, requireNotNull(entityId)).mapNotNull { it.sourceId } else emptyList()
                    val newEntity = controller.call(plugin.id, "playlist.create", args).getJSONObject("entity")
                    if (ids.isNotEmpty()) controller.addToPlaylist(plugin.id, newEntity.getString("id"), ids)
                    copying = false; go(newEntity.getString("id"))
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { mutation = false }
            }
        }
    }
}

@Composable
private fun CatalogEntityRow(item: JSONObject, open: () -> Unit) {
    val art = item.optString("artwork").takeIf { it.startsWith("https://") }
    val coverTrack = Track(android.net.Uri.parse("klang-art:${item.optString("id")}"), item.optString("title"), "", "", 0, artworkUrl = art)
    Surface(onClick = open, Modifier.fillMaxWidth().padding(bottom = 4.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(coverTrack, Modifier.size(56.dp).clip(MaterialTheme.shapes.small))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.optString("title"), style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.optString("subtitle").ifBlank { item.optString("kind").replaceFirstChar(Char::uppercase) }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open ${item.optString("title")}")
        }
    }
}

@Composable
private fun SourceTrackRow(item: JSONObject, track: Track, model: MusicViewModel, providerId: String,
    spec: MusicSourcePluginSpec, state: MusicState, play: () -> Unit, go: (String) -> Unit, playlist: JSONObject?,
    moveBefore: String?, moveAfter: String?, canUp: Boolean, canDown: Boolean, change: (String, JSONObject) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val available = item.optBoolean("available", true)
    val uid = item.optString("uid").takeIf { it.isNotBlank() && it != "null" }
    val fill = if (state.current?.id == track.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Surface(onClick = play, enabled = available, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), shape = MaterialTheme.shapes.medium, color = fill) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(52.dp).clip(MaterialTheme.shapes.small))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SourceBadge(track); Text(track.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if ("offline.download" in spec.capabilities && available) IconButton({ model.musicSources.download(listOf(track)) }, enabled = !track.offline) {
                Icon(if (track.offline) Icons.Rounded.Check else Icons.Rounded.Download,
                    if (track.offline) "Available offline" else "Download ${track.title}")
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${track.title}") }
                DropdownMenu(menu, { menu = false }, shape = KlangMenuShape) {
                    if ("library.write" in spec.capabilities) DropdownMenuItem(text = { Text(if (item.optBoolean("saved")) "Remove from liked songs" else "Like song") },
                        onClick = { menu = false; change(if (item.optBoolean("saved")) "library.remove" else "library.save", JSONObject().put("ids", JSONArray().put(track.sourceId))) })
                    if ("playlist.items" in spec.capabilities) DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menu = false; adding = true })
                    DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; model.playNext(track) }, enabled = available)
                    track.shareUrl?.let { url -> DropdownMenuItem(text = { Text("Copy ${spec.linkLabel} link") }, onClick = { menu = false; copyMusicLink(context, spec.linkLabel, url) }) }
                    item.optString("albumId").takeIf { it.isNotBlank() && it != "null" }?.let { id -> DropdownMenuItem(text = { Text("Go to album") }, onClick = { menu = false; go(id) }) }
                    item.optJSONArray("artists")?.jsonObjects().orEmpty().forEach { artist -> artist.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { id ->
                        DropdownMenuItem(text = { Text("Go to ${artist.optString("name")}") }, onClick = { menu = false; go(id) })
                    } }
                    if (playlist != null && uid != null && "playlist.items" in spec.capabilities) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { menu = false; change("playlist.remove", JSONObject()
                            .put("id", playlist.getString("id")).put("items", JSONArray().put(JSONObject().put("id", track.sourceId).put("uid", uid)))) })
                        DropdownMenuItem(text = { Text("Move up") }, enabled = canUp && !moveBefore.isNullOrBlank(), onClick = { menu = false; change("playlist.move", JSONObject()
                            .put("id", playlist.getString("id")).put("uids", JSONArray().put(uid)).put("beforeUid", moveBefore)) })
                        DropdownMenuItem(text = { Text("Move down") }, enabled = canDown, onClick = { menu = false; change("playlist.move", JSONObject()
                            .put("id", playlist.getString("id")).put("uids", JSONArray().put(uid)).put("beforeUid", moveAfter)) })
                    }
                }
            }
        }
    }
    if (adding) SourceAddToPlaylistDialog(providerId, listOfNotNull(track.sourceId), model) { adding = false }
}

@Composable
internal fun SourceAddToPlaylistDialog(providerId: String, ids: List<String>, model: MusicViewModel, dismiss: () -> Unit) {
    var playlists by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(providerId) {
        try {
            var offset = 0
            do {
                val result = model.musicSources.call(providerId, "library", JSONObject().put("kind", "playlist").put("offset", offset).put("limit", 100))
                playlists = playlists + result.getJSONArray("items").jsonObjects().filter { it.optBoolean("editable") }
                if (result.isNull("nextOffset")) break
                val next = result.getInt("nextOffset")
                require(next > offset && next < 10_000) { "The source returned invalid playlist pagination." }
                offset = next
            } while (true)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add to playlist") }, text = {
        Column {
            if (loading) LoadingIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(playlists) { playlist -> TextButton({ submitting = true; scope.launch {
                    try { model.musicSources.addToPlaylist(providerId, playlist.getString("id"), ids); dismiss() }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                    finally { submitting = false }
                } }, enabled = !loading && !submitting) { Text(playlist.optString("title")) } }
            }
            if (!loading && playlists.isEmpty() && error == null) Text("Create a playlist in your source library first.")
        }
    }, confirmButton = { TextButton(dismiss) { Text("Close") } })
}

@Composable
private fun SourcePlaylistEditor(entity: JSONObject?, create: Boolean, supportsCollaboration: Boolean,
    dismiss: () -> Unit, save: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf(entity?.optString("title").orEmpty()) }
    var description by remember { mutableStateOf(entity?.optString("description")?.takeUnless { it == "null" }.orEmpty()) }
    var public by remember { mutableStateOf(entity?.optBoolean("public") ?: false) }
    var collaborative by remember { mutableStateOf(entity?.optBoolean("collaborative") ?: false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (create) "Create playlist" else "Edit playlist") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(description, { description = it.take(300) }, label = { Text("Description") }, maxLines = 4)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Public", Modifier.weight(1f)); Switch(public, { public = it; if (it) collaborative = false }) }
            if (!create && entity?.optBoolean("owned") == true && supportsCollaboration) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Collaborative", Modifier.weight(1f)); Switch(collaborative, { collaborative = it; if (it) public = false })
            }
        }
    }, confirmButton = { TextButton({ save(JSONObject().put("name", name.trim()).put("description", description).put("public", public)
        .also { if (!create && supportsCollaboration && entity?.optBoolean("owned") == true) it.put("collaborative", collaborative) }) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

private fun JSONArray.jsonObjects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
