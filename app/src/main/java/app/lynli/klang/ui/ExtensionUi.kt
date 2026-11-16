@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.MusicState
import app.lynli.klang.plugins.ExtensionRuntime
import org.json.JSONArray
import org.json.JSONObject

/** Native rendering of a bounded, declarative API 1 panel. Plugins own text, state and actions;
 * host color roles, typography, accessibility and motion remain consistent with Klang. */
@Composable
internal fun ExtensionPanel(state: MusicState) {
    val id by ExtensionRuntime.panel.collectAsStateWithLifecycle()
    val states by ExtensionRuntime.states.collectAsStateWithLifecycle()
    val pluginId = id ?: return
    val panel = states[pluginId]?.optJSONObject("panel")
    ContentTheme(state.coverColor, state.current) {
        val values = remember(pluginId) { mutableStateMapOf<String, String>() }
        val items = panel?.optJSONArray("items") ?: JSONArray()
        val nodes = (0 until minOf(items.length(), 40)).mapNotNull { items.optJSONObject(it) }
        val footer = nodes.filter { it.optString("type") == "button" && it.optString("placement") == "footer" }.take(2)
        fun dispatch(node: JSONObject, extra: JSONObject = JSONObject()) {
            values.forEach { (key, value) -> extra.put(key, value) }
            ExtensionRuntime.active?.action(pluginId, node.optString("action"), extra)
        }
        ModalBottomSheet(onDismissRequest = ExtensionRuntime::dismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = KlangSheetShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (panel == null) { LoadingIndicator(Modifier.align(Alignment.CenterHorizontally)); Text("Connecting to plugin…") }
                    else {
                        Text(panel.optString("title").take(100), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLargeEmphasized)
                        for (node in nodes.filterNot { it in footer }) {
                            val label = node.optString("label").take(500)
                            val enabled = node.optBoolean("enabled", true)
                            when (node.optString("type")) {
                                "text" -> Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                "hero" -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.extraLarge) {
                                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(28.dp))
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            node.optString("eyebrow").takeIf { it.isNotBlank() }?.let {
                                                Text(it.take(100), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Text(label, style = MaterialTheme.typography.displaySmall,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            node.optString("supporting").takeIf { it.isNotBlank() }?.let {
                                                Text(it.take(200), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                "field" -> {
                                    val key = node.optString("id").take(64)
                                    var value by rememberSaveable(pluginId, key, node.optString("value")) { mutableStateOf(node.optString("value")) }
                                    val max = node.optInt("maxLength", 256).coerceIn(1, 4096)
                                    OutlinedTextField(value, { input ->
                                        value = if (node.optString("input") == "code") input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(max) else input.take(max)
                                        values[key] = value
                                    }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, shape = KlangFieldShape,
                                        label = { Text(label) }, keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.None,
                                            keyboardType = if (node.optBoolean("secret")) KeyboardType.Password else KeyboardType.Ascii),
                                        visualTransformation = if (node.optBoolean("secret")) PasswordVisualTransformation() else VisualTransformation.None)
                                }
                                "button" -> ExtensionPanelButton(node, Modifier.fillMaxWidth()) { dispatch(node) }
                                "toggle" -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(label, style = MaterialTheme.typography.titleMediumEmphasized)
                                            Text(node.optString("supporting").take(300), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(node.optBoolean("checked"), { dispatch(node, JSONObject().put("checked", it)) }, enabled = enabled)
                                    }
                                }
                                "member" -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text(label.take(1), style = MaterialTheme.typography.titleLargeEmphasized) }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(label, style = MaterialTheme.typography.titleMediumEmphasized)
                                            Text(node.optString("supporting").take(100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        node.optString("badge").takeIf { it.isNotBlank() }?.let {
                                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                                Text(it.take(20), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLargeEmphasized)
                                            }
                                        }
                                    }
                                }
                                "progress" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LoadingIndicator(Modifier.size(28.dp)); Text(label, style = MaterialTheme.typography.bodyMedium)
                                }
                                "error" -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer) { Text(label, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
                if (footer.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    footer.forEachIndexed { index, node ->
                        if (index > 0) Spacer(Modifier.weight(1f))
                        ExtensionPanelButton(node) { dispatch(node) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionPanelButton(node: JSONObject, modifier: Modifier = Modifier, dispatch: () -> Unit) {
    val context = LocalContext.current
    val label = node.optString("label").take(500)
    val enabled = node.optBoolean("enabled", true)
    val height = ButtonDefaults.MediumContainerHeight
    val click: () -> Unit = {
        val share = node.optString("shareUrl")
        if (share.startsWith("https://") && share.length <= 2048) runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share), label))
        } else dispatch()
    }
    if (node.optString("presentation") == "icon") {
        TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
            FilledTonalIconButton(onClick = click, modifier = modifier.size(height), enabled = enabled,
                shapes = IconButtonDefaults.shapes(), colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Icon(extensionIcon(node.optString("icon")), label)
            }
        }
    } else {
        val content: @Composable RowScope.() -> Unit = {
            Icon(extensionIcon(node.optString("icon")), null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(label, style = ButtonDefaults.textStyleFor(height))
        }
        if (node.optString("style") == "tonal") FilledTonalButton(onClick = click, modifier = modifier.heightIn(min = height),
            enabled = enabled, shapes = ButtonDefaults.shapesFor(height), contentPadding = ButtonDefaults.contentPaddingFor(height), content = content)
        else Button(onClick = click, modifier = modifier.heightIn(min = height), enabled = enabled,
            shapes = ButtonDefaults.shapesFor(height), contentPadding = ButtonDefaults.contentPaddingFor(height),
            colors = if (node.optString("style") == "danger") ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.buttonColors(),
            content = content)
    }
}

internal fun extensionIcon(name: String) = when (name) {
    "jam" -> Icons.Rounded.GraphicEq
    "share" -> Icons.Rounded.Share
    "join" -> Icons.Rounded.Headphones
    "leave" -> Icons.AutoMirrored.Rounded.ExitToApp
    "download" -> Icons.Rounded.Download
    "add" -> Icons.Rounded.Add
    else -> Icons.Rounded.GraphicEq
}

@Composable
internal fun ExtensionNowPlayingBadge() {
    val states by ExtensionRuntime.states.collectAsStateWithLifecycle()
    val entry = states.entries.firstOrNull { it.value.optJSONObject("badge")?.optBoolean("visible") == true }
    val width by animateDpAsState(if (entry == null) 0.dp else 88.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(), label = "Plugin badge space")
    Box(Modifier.width(width), contentAlignment = Alignment.Center) {
        AnimatedVisibility(entry != null,
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())) {
            val badge = entry?.value?.optJSONObject("badge")
            FilledTonalButton({ entry?.let { ExtensionRuntime.open(it.key) } }, contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                if (badge?.optBoolean("loading") == true) { LoadingIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)) }
                Text(badge?.optString("label", "Jam")?.take(8).orEmpty(), style = MaterialTheme.typography.labelLargeEmphasized)
            }
        }
    }
}

@Composable
internal fun ExtensionTrackAction(trackId: String): Boolean {
    val states by ExtensionRuntime.states.collectAsStateWithLifecycle()
    val entry = states.entries.firstOrNull { it.value.optJSONObject("trackAction")?.optString("trackId")?.let { hash -> hash == trackId || ExtensionRuntime.active?.audioUri(hash)?.toString() == trackId } == true } ?: return false
    val action = entry.value.getJSONObject("trackAction")
    IconButton({ ExtensionRuntime.active?.action(entry.key, action.optString("action")) }) {
        Icon(Icons.Rounded.Download, action.optString("label", "Download song"))
    }
    return true
}
