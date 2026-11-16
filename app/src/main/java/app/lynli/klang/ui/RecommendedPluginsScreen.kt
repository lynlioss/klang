@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import app.lynli.klang.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.MusicViewModel

@Composable
internal fun RecommendedPluginsScreen(model: MusicViewModel, complete: (() -> Unit)? = null, back: (() -> Unit)? = null) {
    val catalog by model.pluginCatalog.collectAsStateWithLifecycle()
    val config by model.plugins.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            back?.let { IconButton(it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to plugins") } }
            Text("Recommended plugins", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineLargeEmphasized)
            Text("Add what you need.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Triple("spotify", "Spotify Integration", "Your Spotify library, YouTube Music and Qobuz playback, and offline downloads."),
                    Triple("music-import", "Music Import", "Songs, albums, EPs and playlists from music links."),
                    Triple("lrclib", "LRCLIB", "Synchronized lyrics for your music."),
                    Triple("klang-jam", "Jam", "Listen together with up to four people. Share a code and bring your music.")).forEach { (id, title, subtitle) ->
                    item(key = id) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (id == "spotify") Icon(painterResource(R.drawable.spotify), null, Modifier.size(24.dp))
                                    Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                                }
                                Text(subtitle, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val installed = catalog.installed.any { it.manifest.id == id }
                                val enabled = id in config.enabled
                                Button({
                                    if (installed) model.enablePlugin(id, true)
                                    else model.installRecommendedPlugin("https://github.com/lynlioss/${when (id) { "spotify" -> "klang-spotify"; "music-import" -> "music-import-klang"; "klang-jam" -> "klang-jam"; else -> "lrclib-klang" }}")
                                }, enabled = !enabled && catalog.busyId == null, modifier = Modifier.padding(top = 16.dp)) {
                                    if (catalog.busyId == id || catalog.busyId == "recommended") LoadingIndicator(Modifier.size(24.dp))
                                    else Text(if (enabled) "Added" else if (installed) "Enable" else "Install")
                                }
                            }
                        }
                    }
                }
                catalog.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            }
            complete?.let { Button(it, Modifier.fillMaxWidth()) { Text("Continue") } }
        }
    }
}
