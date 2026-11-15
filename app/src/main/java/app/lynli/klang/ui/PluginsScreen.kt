@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.plugins.PluginConfiguration
import app.lynli.klang.plugins.PluginManifest
import app.lynli.klang.plugins.PluginSetting
import app.lynli.klang.plugins.PluginCatalog
import app.lynli.klang.plugins.PluginCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun PluginsScreen(model: MusicViewModel, back: () -> Unit) {
    val config by model.plugins.collectAsStateWithLifecycle()
    val catalog by model.pluginCatalog.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val motion = motionEnabled()
    val scheme = MaterialTheme.motionScheme
    LaunchedEffect(catalog.installed) {
        if (selected != null && catalog.installed.none { it.manifest.id == selected }) selected = null
    }
    BackHandler(selected != null) { selected = null }
    if (adding) InstallPluginSheet(model, catalog) { adding = false }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(selected, transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally(if (motion) scheme.defaultSpatialSpec() else snap()) { if (forward) it / 5 else -it / 5 } +
                fadeIn(if (motion) scheme.defaultEffectsSpec() else snap())) togetherWith
                (slideOutHorizontally(if (motion) scheme.defaultSpatialSpec() else snap()) { if (forward) -it / 5 else it / 5 } +
                    fadeOut(if (motion) scheme.defaultEffectsSpec() else snap()))
        }, label = "Plugin overview") { id ->
            val plugin = catalog.installed.find { it.manifest.id == id }?.manifest
            if (plugin == null) PluginList(catalog, config, model, { selected = it }, { adding = true }, back)
            else PluginOverview(plugin, config, catalog, model) { selected = null }
        }
    }
}

@Composable
private fun PluginList(catalog: PluginCatalog, config: PluginConfiguration, model: MusicViewModel,
    open: (String) -> Unit, add: () -> Unit, back: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to settings") }
                Spacer(Modifier.weight(1f))
                IconButton(model::checkPluginUpdates, enabled = !catalog.checking && catalog.busyId == null) {
                    if (catalog.checking) LoadingIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Rounded.Refresh, "Check plugin updates")
                }
                FilledTonalIconButton(add) { Icon(Icons.Rounded.Add, "Add plugin from GitHub") }
            }
            Text("Plugins", Modifier.padding(top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.displayMedium)
            Text("Music integrations", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            listOf("Spotify" to R.drawable.spotify, "YouTube Music" to R.drawable.youtube_music).forEach { (name, logo) ->
                Surface(Modifier.padding(bottom = 12.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
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
        items(catalog.installed, key = { it.manifest.id }) { entry ->
            val plugin = entry.manifest
            val enabled = plugin.id in config.enabled
            Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                        .clickable(onClickLabel = "About ${plugin.name}") { open(plugin.id) }.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PluginGlyph(plugin, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(plugin.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${plugin.type.label} · ${if (enabled) "Enabled" else "Disabled"}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    catalog.updates[plugin.id]?.let { update ->
                        IconButton({ model.installPlugin(update) }, enabled = catalog.busyId == null) {
                            Icon(Icons.Rounded.SystemUpdateAlt, "Update ${plugin.name} to ${update.manifest.version}", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (catalog.busyId == plugin.id) LoadingIndicator(Modifier.size(32.dp).padding(end = 4.dp))
                    Switch(enabled, { model.enablePlugin(plugin.id, it) }, enabled = catalog.busyId == null,
                        modifier = Modifier.padding(end = 20.dp).semantics { contentDescription = "Enable ${plugin.name}" })
                }
            }
        }
        if (catalog.installed.isEmpty()) item {
            EmptyState("Add your first plugin", "Tap + and paste a plugin's GitHub repository link.")
        }
        item { PluginError(catalog.error, model::clearPluginError) }
    }
}

@Composable
private fun PluginGlyph(plugin: PluginManifest, modifier: Modifier = Modifier) {
    if (plugin.id == "lrclib") Icon(painterResource(R.drawable.lrclib), "LRCLIB logo", modifier, tint = Color.Unspecified)
    else Icon(ImageVector.vectorResource(R.drawable.lyrics), null, modifier, tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PluginOverview(plugin: PluginManifest, config: PluginConfiguration, catalog: PluginCatalog,
    model: MusicViewModel, back: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    var linkError by remember(plugin.id) { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val busy = catalog.busyId != null
    if (removing) AlertDialog(onDismissRequest = { removing = false }, shape = MaterialTheme.shapes.extraLarge,
        icon = { Icon(Icons.Rounded.DeleteOutline, null) }, title = { Text("Uninstall ${plugin.name}?") },
        text = { Text("This removes the plugin, its settings and saved lyrics. You can install it again from its GitHub link.") },
        confirmButton = { TextButton({ removing = false; model.uninstallPlugin(plugin.id) }) { Text("Uninstall", color = colors.error) } },
        dismissButton = { TextButton({ removing = false }) { Text("Cancel") } })
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
                PluginGlyph(plugin, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(plugin.name, style = MaterialTheme.typography.headlineLargeEmphasized)
                    Text("${plugin.author} · v${plugin.version}", Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Use ${plugin.name}", style = MaterialTheme.typography.titleMediumEmphasized)
                        Text(if (plugin.id in config.enabled) "Ready in Now playing" else "Turn on to follow along",
                            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(plugin.id in config.enabled, { model.enablePlugin(plugin.id, it) }, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Enable ${plugin.name}" })
                }
            }
        }
        catalog.updates[plugin.id]?.let { update -> item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.SystemUpdateAlt, null)
                        Text("Version ${update.manifest.version} available", style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                    Text("Your settings and enabled state will be kept.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Button({ model.installPlugin(update) }, enabled = !busy, modifier = Modifier.padding(top = 12.dp)) {
                        if (catalog.busyId == plugin.id) LoadingIndicator(Modifier.size(24.dp)) else Text("Update plugin")
                    }
                }
            }
        } }
        item { PluginError(catalog.error, model::clearPluginError) }
        item {
            Text(plugin.description, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plugin.buttons.forEach { button ->
                    FilledTonalButton({ linkError = runCatching { uriHandler.openUri(button.url) }.isFailure }) {
                        Icon(when (button.icon) {
                            "donate" -> Icons.Rounded.Favorite
                            "source" -> Icons.Rounded.Code
                            else -> Icons.Rounded.OpenInNew
                        }, null, Modifier.size(ButtonDefaults.SmallIconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(button.label)
                    }
                }
            }
            if (linkError) Text("Couldn't open this link. Check that a browser is installed.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        if (plugin.settings.isNotEmpty()) item {
            Text("Your preferences", style = MaterialTheme.typography.titleLargeEmphasized)
        }
        items(plugin.settings, key = { it.id }) { setting -> PluginSettingCard(plugin, setting, config, model, !busy) }
        if (plugin.privacy.isNotBlank()) item {
            Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("How it works", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(plugin.privacy, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedButton({ removing = true }, enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error)) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(ButtonDefaults.SmallIconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text("Uninstall plugin")
            }
        }
    }
}

@Composable
private fun PluginSettingCard(plugin: PluginManifest, setting: PluginSetting, config: PluginConfiguration,
    model: MusicViewModel, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(setting.title, style = MaterialTheme.typography.titleMediumEmphasized)
                    if (setting.description.isNotBlank()) Text(setting.description, Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (setting is PluginSetting.Toggle) Switch(config.boolean(plugin, setting.id),
                    { model.pluginSetting(plugin.id, setting.id, it.toString()) }, enabled = enabled,
                    modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = setting.title })
            }
            when (setting) {
                is PluginSetting.Toggle -> Unit
                is PluginSetting.Range -> {
                    var value by remember(plugin.id, config.value(plugin, setting)) { mutableFloatStateOf(config.integer(plugin, setting.id).toFloat()) }
                    val slider = remember(setting) { SliderState(trackRange = setting.min.toFloat()..setting.max.toFloat(),
                        steps = (setting.max - setting.min) / setting.step - 1) }
                    slider.value = value
                    Text("${value.roundToInt()} ${setting.unit}".trim(), Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleLargeEmphasized, color = colors.primary)
                    Slider(state = slider, enabled = enabled, onValueChange = { value = it },
                        onValueChangeFinished = { model.pluginSetting(plugin.id, setting.id, value.roundToInt().toString()) },
                        modifier = Modifier.semantics { contentDescription = setting.title })
                }
                is PluginSetting.Text -> {
                    val saved = config.value(plugin, setting)
                    var value by remember(plugin.id, saved) { mutableStateOf(saved) }
                    OutlinedTextField(value, { value = it.take(4096).replace("\n", "").replace("\r", "") },
                        Modifier.fillMaxWidth().padding(top = 12.dp), enabled = enabled, singleLine = true,
                        shape = KlangFieldShape, label = { Text(setting.title) },
                        visualTransformation = if (setting.secret) PasswordVisualTransformation() else VisualTransformation.None)
                    FilledTonalButton({ model.pluginSetting(plugin.id, setting.id, value) }, enabled = enabled && value != saved,
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Save") }
                }
                is PluginSetting.Choice -> FlowRow(Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    setting.options.forEach { option ->
                        FilterChip(config.value(plugin, setting) == option.value,
                            { model.pluginSetting(plugin.id, setting.id, option.value) }, enabled = enabled,
                            label = { Text(option.label) }, shapes = klangFilterChipShapes())
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginError(error: String?, dismiss: () -> Unit) {
    if (error == null) return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(dismiss) { Icon(Icons.Rounded.Close, "Dismiss plugin error") }
        }
    }
}

@Composable
private fun InstallPluginSheet(model: MusicViewModel, catalog: PluginCatalog, dismiss: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var candidate by remember { mutableStateOf<PluginCandidate?>(null) }
    var installing by remember { mutableStateOf(false) }
    val alreadyInstalled = candidate?.let { result -> catalog.installed.any {
        it.manifest.id == result.manifest.id && it.manifest.version == result.manifest.version
    } } == true
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(installing, catalog.busyId, catalog.installed) {
        if (installing && catalog.busyId == null) {
            if (catalog.installed.any { it.manifest.id == candidate?.manifest?.id && it.manifest.version == candidate?.manifest?.version }) {
                sheet.hide(); dismiss()
            } else { installing = false; error = catalog.error }
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, shape = KlangSheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.padding(top = 10.dp)) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text("Add plugin", style = MaterialTheme.typography.headlineMediumEmphasized)
            Text("Paste a GitHub repository link to install its latest release.", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(link, { link = it; candidate = null; error = null }, Modifier.fillMaxWidth().padding(top = 20.dp),
                enabled = !loading && !installing, singleLine = true, shape = KlangFieldShape,
                label = { Text("GitHub repository") }, placeholder = { Text("https://github.com/owner/plugin") },
                leadingIcon = { Icon(Icons.Rounded.Code, null) }, isError = error != null)
            candidate?.let { result ->
                Surface(Modifier.padding(top = 16.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(result.manifest.name, style = MaterialTheme.typography.titleLargeEmphasized)
                        Text("${result.manifest.author} · v${result.manifest.version}", style = MaterialTheme.typography.bodySmall)
                        Text(result.manifest.summary, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        if (result.manifest.privacy.isNotBlank()) Text(result.manifest.privacy, Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                val height = ButtonDefaults.MediumContainerHeight
                Button({
                    val ready = candidate
                    if (ready == null) {
                        loading = true; error = null
                        scope.launch {
                            try { candidate = model.inspectPlugin(link) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "Couldn't find this plugin." }
                            finally { loading = false }
                        }
                    } else { error = null; installing = true; model.installPlugin(ready) }
                }, enabled = link.isNotBlank() && !loading && !installing && !alreadyInstalled && catalog.busyId == null,
                    modifier = Modifier.heightIn(min = height), shapes = ButtonDefaults.shapesFor(height),
                    contentPadding = ButtonDefaults.contentPaddingFor(height)) {
                    if (loading || installing) LoadingIndicator(Modifier.size(24.dp))
                    else {
                        Icon(if (candidate == null) Icons.Rounded.Search else Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.SmallIconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(if (candidate == null) "Find plugin" else if (alreadyInstalled) "Installed" else "Install",
                            style = ButtonDefaults.textStyleFor(height))
                    }
                }
            }
        }
    }
}
