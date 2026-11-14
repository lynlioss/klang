@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.plugins.PluginConfiguration
import app.lynli.klang.plugins.PluginManifest
import app.lynli.klang.plugins.PluginSetting
import kotlin.math.roundToInt

@Composable
internal fun PluginsScreen(model: MusicViewModel, back: () -> Unit) {
    val config by model.plugins.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(selected != null) { selected = null }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(selected, transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally(spring(0.8f, 200f)) { if (forward) it / 5 else -it / 5 } + fadeIn(spring(1f, 800f))) togetherWith
                (slideOutHorizontally(spring(0.8f, 200f)) { if (forward) -it / 5 else it / 5 } + fadeOut(spring(1f, 800f)))
        }, label = "Plugin overview") { id ->
            val plugin = model.pluginManifests.find { it.id == id }
            if (plugin == null) PluginList(model.pluginManifests, config, model, { selected = it }, back)
            else PluginOverview(plugin, config, model) { selected = null }
        }
    }
}

@Composable
private fun PluginList(manifests: List<PluginManifest>, config: PluginConfiguration, model: MusicViewModel,
    open: (String) -> Unit, back: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(24.dp)) {
        item {
            UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to settings") }
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
        items(manifests, key = { it.id }) { plugin ->
            val enabled = plugin.id in config.enabled
            Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .clickable(onClickLabel = "About ${plugin.name}") { open(plugin.id) }.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PluginGlyph(plugin, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(plugin.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${plugin.type.label} · ${if (enabled) "Enabled" else "Disabled"}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(enabled, { model.enablePlugin(plugin.id, it) },
                        modifier = Modifier.padding(end = 20.dp).semantics { contentDescription = "Enable ${plugin.name}" })
                }
            }
        }
    }
}

@Composable
private fun PluginOverview(plugin: PluginManifest, config: PluginConfiguration, model: MusicViewModel, back: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to plugins") }
                Text("${plugin.type.label} plugin", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PluginGlyph(plugin, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(plugin.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("${plugin.author} · v${plugin.version}", Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Use ${plugin.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (plugin.id in config.enabled) "Ready in Now playing" else "Turn on to follow along", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(plugin.id in config.enabled, { model.enablePlugin(plugin.id, it) },
                        modifier = Modifier.semantics { contentDescription = "Enable ${plugin.name}" })
                }
            }
        }
        item {
            Text(plugin.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                plugin.buttons.forEach { button ->
                    FilledTonalButton({ linkError = runCatching { uriHandler.openUri(button.url) }.isFailure }) {
                        Icon(if (button.icon == "donate") Icons.Rounded.Favorite else ImageVector.vectorResource(R.drawable.open_in_new),
                            null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text(button.label)
                    }
                }
            }
            if (linkError) Text("Couldn't open the browser. Visit lrclib.net.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (plugin.settings.isNotEmpty()) item { Text("Your preferences", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(plugin.settings, key = { it.id }) { setting ->
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                when (setting) {
                    is PluginSetting.Toggle -> Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(setting.title, style = MaterialTheme.typography.titleMedium)
                            Text(setting.description, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(config.boolean(plugin, setting.id), { model.pluginSetting(plugin.id, setting.id, it.toString()) },
                            modifier = Modifier.semantics { contentDescription = setting.title })
                    }
                    is PluginSetting.Range -> {
                        var value by remember(config.value(plugin, setting)) { mutableFloatStateOf(config.integer(plugin, setting.id).toFloat()) }
                        val slider = remember(setting) { SliderState(trackRange = setting.min.toFloat()..setting.max.toFloat(),
                            steps = (setting.max - setting.min) / setting.step - 1) }
                        slider.value = value
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text(setting.title, style = MaterialTheme.typography.titleMedium)
                            Text(setting.description, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${if (value > 0) "+" else ""}${value.roundToInt()} ${setting.unit}", Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Slider(state = slider, onValueChange = { value = it },
                                onValueChangeFinished = { model.pluginSetting(plugin.id, setting.id, value.roundToInt().toString()) },
                                modifier = Modifier.semantics { contentDescription = setting.title })
                        }
                    }
                }
            }
        }
        if (plugin.privacy.isNotBlank()) item {
            Text("How it works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(plugin.privacy, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PluginGlyph(plugin: PluginManifest, modifier: Modifier = Modifier) {
    if (plugin.id == "lrclib") Icon(painterResource(R.drawable.lrclib), "LRCLIB logo", modifier, tint = Color.Unspecified)
    else Icon(ImageVector.vectorResource(R.drawable.lyrics), null, modifier, tint = MaterialTheme.colorScheme.primary)
}
