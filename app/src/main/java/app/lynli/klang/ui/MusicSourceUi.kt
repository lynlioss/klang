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
internal fun SourceConnectionCards(state: MusicState, model: MusicViewModel) {
    state.sourceConnections.filter { !it.connected }.forEach { source ->
        Surface(Modifier.fillMaxWidth().padding(vertical = 12.dp), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (source.badge == "spotify") Icon(painterResource(R.drawable.spotify), null, Modifier.size(32.dp))
                else Icon(Icons.Rounded.MusicNote, null, Modifier.size(32.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Connect ${source.name}", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text("Bring your songs and playlists into Klang.", style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton({ model.musicSources.settings(source.providerId) }) { Text("Connect") }
            }
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
    fun goBack() { if (history.isNotEmpty()) { entityId = history.last(); history = history.dropLast(1); offset = 0 } else back() }
    BackHandler { goBack() }
    suspend fun load(next: Int): JSONObject {
        val args = JSONObject().put("offset", next).put("limit", 50)
        args.put("id", requireNotNull(entityId))
        return controller.call(plugin.id, "lookup", args)
    }
    LaunchedEffect(entityId, refresh, resume, config.enabled) {
        loading = true; error = null; page = null; tracks = emptyMap(); offset = 0
        try {
            page = load(0)
            tracks = page?.optJSONArray("items")?.jsonObjects().orEmpty().filter { it.optString("kind") == "track" }
                .associate { item -> item.getString("id") to controller.track(plugin.id, item) }
            controller.prepare(tracks.values.toList().take(2))
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
        if (entityId != null) model.playSourceCollection(plugin.id, requireNotNull(entityId), playable, selected, shuffled,
            hasMore = page?.isNull("nextOffset") == false)
        else model.play(playable, selected, shuffled)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                UtilityIconButton({ goBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                Box {
                    UtilityIconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Source options") }
                    DropdownMenu(menu, { menu = false }, shape = KlangMenuShape) {
                        DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { menu = false; refresh++ })
                        entity?.optString("url")?.takeIf { it.startsWith("https://") }?.let { url ->
                            DropdownMenuItem(text = { Text("Copy ${spec.linkLabel} link") }, leadingIcon = { Icon(painterResource(R.drawable.link), null) }, onClick = { menu = false; copyMusicLink(context, spec.linkLabel, url) })
                        }
                        if (entity?.optString("kind") in setOf("album", "artist") && "library.write" in spec.capabilities) {
                            val artist = entity?.optString("kind") == "artist"
                            DropdownMenuItem(text = { Text(if (artist) "Follow artist" else "Save album") }, leadingIcon = { Icon(Icons.Rounded.FavoriteBorder, null) }, onClick = {
                                menu = false; change("library.save", JSONObject().put("ids", JSONArray().put(entityId)))
                            })
                            DropdownMenuItem(text = { Text(if (artist) "Unfollow artist" else "Remove saved album") }, leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = {
                                menu = false; change("library.remove", JSONObject().put("ids", JSONArray().put(entityId)))
                            })
                        }
                        if (entity?.optString("kind") == "playlist") {
                            if (entity.optBoolean("owned") && "playlist.edit" in spec.capabilities)
                                DropdownMenuItem(text = { Text("Edit playlist") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; edit = true })
                            if (entity.optBoolean("owned") && "playlist.cover" in spec.capabilities)
                                DropdownMenuItem(text = { Text("Change cover") }, leadingIcon = { Icon(painterResource(R.drawable.photo), null) }, onClick = { menu = false; coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            if ("playlist.follow" in spec.capabilities) {
                                DropdownMenuItem(text = { Text("Save playlist") }, leadingIcon = { Icon(painterResource(R.drawable.playlist_add), null) }, onClick = { menu = false; change("playlist.follow", JSONObject().put("id", entityId)) })
                                DropdownMenuItem(text = { Text(if (entity.optBoolean("owned")) "Remove from your library" else "Unfollow playlist") }, leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = { menu = false; change("playlist.unfollow", JSONObject().put("id", entityId)) })
                            }
                            if ("playlist.create" in spec.capabilities)
                                DropdownMenuItem(text = { Text("Copy to a new playlist") }, leadingIcon = { Icon(painterResource(R.drawable.content_copy), null) }, onClick = { menu = false; copying = true; create = true })
                        }
                    }
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
                    entity?.let { value ->
                        val cover = sourceCollection(plugin.id, value).collection()
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            CollectionCover(cover, Modifier.size(244.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(48.dp)), large = true)
                        }
                    }
                    Text(page?.optString("title") ?: "Loading collection…",
                        Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.headlineLargeEmphasized)
                    entity?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    page?.optString("notice")?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    page?.optInt("unavailableCount")?.takeIf { it > 0 }?.let { Text("$it unavailable entries", style = MaterialTheme.typography.bodySmall) }
                    if (entityId != null && playable.isNotEmpty()) Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val sourceId = MusicSourceProtocol.identity(plugin.id, requireNotNull(entityId))
                        ExpressivePlay(state.playWhenReady && state.isPlayingFrom(sourceId), {
                            if (state.isPlayingFrom(sourceId)) model.togglePlay() else playCollection()
                        }, 72.dp, !loading, state.buffering && state.isPlayingFrom(sourceId))
                        FilledTonalIconButton({ playCollection(shuffled = true) }, enabled = !loading) { Icon(Icons.Rounded.Shuffle, "Shuffle collection") }
                        if ("offline.download" in spec.capabilities) IconButton({ scope.launch {
                            try {
                                loading = true
                                val songs = controller.collection(plugin.id, requireNotNull(entityId))
                                controller.download(songs, page?.optString("title").orEmpty(), collection = true)
                            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
                        } }, enabled = !loading && !mutation) { Icon(Icons.Rounded.Download, "Download collection") }
                    }
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
                if (!loading && items.isEmpty() && error == null) item { EmptyState("No songs yet", "Add songs or refresh this collection.") }
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
    val uid = item.optString("uid").takeIf { it.isNotBlank() && it != "null" }
    TrackRow(track, state, model, play, enabled = item.optBoolean("available", true)) { close ->
        item.optString("albumId").takeIf { it.isNotBlank() && it != "null" }?.let { id ->
            DropdownMenuItem(text = { Text("Go to album") }, leadingIcon = { Icon(painterResource(R.drawable.album), null) },
                onClick = { close(); go(id) })
        }
        item.optJSONArray("artists")?.jsonObjects().orEmpty().forEach { artist ->
            artist.optString("id").takeIf { it.isNotBlank() && it != "null" }?.let { id ->
                DropdownMenuItem(text = { Text("Go to ${artist.optString("name")}") }, leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    onClick = { close(); go(id) })
            }
        }
        if (playlist != null && uid != null && "playlist.items" in spec.capabilities) {
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Remove from playlist") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                onClick = { close(); change("playlist.remove", JSONObject().put("id", playlist.getString("id"))
                    .put("items", JSONArray().put(JSONObject().put("id", track.sourceId).put("uid", uid)))) })
            DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Rounded.KeyboardArrowUp, null) },
                enabled = canUp && !moveBefore.isNullOrBlank(), onClick = { close(); change("playlist.move", JSONObject()
                    .put("id", playlist.getString("id")).put("uids", JSONArray().put(uid)).put("beforeUid", moveBefore)) })
            DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, null) },
                enabled = canDown, onClick = { close(); change("playlist.move", JSONObject()
                    .put("id", playlist.getString("id")).put("uids", JSONArray().put(uid)).put("beforeUid", moveAfter)) })
        }
    }
}

@Composable
internal fun SourceCreatePlaylistDialog(providerId: String, model: MusicViewModel, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    SourcePlaylistEditor(null, true, false, dismiss, submitting, error) { args ->
        if (!submitting) {
            submitting = true; error = null
            scope.launch {
                try {
                    val result = model.musicSources.call(providerId, "playlist.create", args)
                    dismiss()
                    result.optJSONObject("entity")?.optString("id")?.let { model.musicSources.open(providerId, it) }
                } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { error = e.message }
                finally { submitting = false }
            }
        }
    }
}

@Composable
private fun SourcePlaylistEditor(entity: JSONObject?, create: Boolean, supportsCollaboration: Boolean,
    dismiss: () -> Unit, submitting: Boolean = false, error: String? = null, save: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf(entity?.optString("title").orEmpty()) }
    var description by remember { mutableStateOf(entity?.optString("description")?.takeUnless { it == "null" }.orEmpty()) }
    var public by remember { mutableStateOf(entity?.optBoolean("public") ?: false) }
    var collaborative by remember { mutableStateOf(entity?.optBoolean("collaborative") ?: false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (create) "Create playlist" else "Edit playlist") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (submitting) LoadingIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(description, { description = it.take(300) }, label = { Text("Description") }, maxLines = 4)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Public", Modifier.weight(1f)); Switch(public, { public = it; if (it) collaborative = false }) }
            if (!create && entity?.optBoolean("owned") == true && supportsCollaboration) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Collaborative", Modifier.weight(1f)); Switch(collaborative, { collaborative = it; if (it) public = false })
            }
        }
    }, confirmButton = { TextButton({ save(JSONObject().put("name", name.trim()).put("description", description).put("public", public)
        .also { if (!create && supportsCollaboration && entity?.optBoolean("owned") == true) it.put("collaborative", collaborative) }) }, enabled = name.isNotBlank() && !submitting) { Text("Save") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

private fun JSONArray.jsonObjects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
