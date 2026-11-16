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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        val context = LocalContext.current
        ModalBottomSheet(onDismissRequest = ExtensionRuntime::dismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = KlangSheetShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (panel == null) { LoadingIndicator(Modifier.align(Alignment.CenterHorizontally)); Text("Connecting to plugin…") }
                else {
                    Text(panel.optString("title").take(100), style = MaterialTheme.typography.headlineLargeEmphasized)
                    val nodes = panel.optJSONArray("items") ?: JSONArray()
                    for (index in 0 until minOf(nodes.length(), 40)) {
                        val node = nodes.optJSONObject(index) ?: continue
                        val label = node.optString("label").take(500)
                        val action = node.optString("action")
                        val enabled = node.optBoolean("enabled", true)
                        fun dispatch(extra: JSONObject = JSONObject()) {
                            values.forEach { (key, value) -> extra.put(key, value) }
                            ExtensionRuntime.active?.action(pluginId, action, extra)
                        }
                        when (node.optString("type")) {
                            "text" -> Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            "hero" -> Surface(color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.extraLargeIncreased) {
                                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(40.dp))
                                    Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.displayLarge,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    node.optString("supporting").takeIf { it.isNotBlank() }?.let { Text(it.take(200), style = MaterialTheme.typography.labelLarge) }
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
                            "button" -> {
                                val height = ButtonDefaults.MediumContainerHeight
                                val click: () -> Unit = {
                                    val share = node.optString("shareUrl")
                                    if (share.startsWith("https://") && share.length <= 2048) runCatching {
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share), label))
                                    } else dispatch()
                                }
                                if (node.optString("style") == "tonal") FilledTonalButton(onClick = click, modifier = Modifier.fillMaxWidth().heightIn(min = height),
                                    enabled = enabled, shapes = ButtonDefaults.shapesFor(height), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
                                    Icon(extensionIcon(node.optString("icon")), null); Spacer(Modifier.width(8.dp)); Text(label, style = ButtonDefaults.textStyleFor(height))
                                } else Button(onClick = click, modifier = Modifier.fillMaxWidth().heightIn(min = height), enabled = enabled,
                                    shapes = ButtonDefaults.shapesFor(height), contentPadding = ButtonDefaults.contentPaddingFor(height),
                                    colors = if (node.optString("style") == "danger") ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.buttonColors()) {
                                    Icon(extensionIcon(node.optString("icon")), null); Spacer(Modifier.width(8.dp)); Text(label, style = ButtonDefaults.textStyleFor(height))
                                }
                            }
                            "toggle" -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, style = MaterialTheme.typography.titleMediumEmphasized)
                                        Text(node.optString("supporting").take(300), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(node.optBoolean("checked"), { dispatch(JSONObject().put("checked", it)) }, enabled = enabled)
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
        }
    }
}

internal fun extensionIcon(name: String) = when (name) {
    "jam" -> Icons.Rounded.GraphicEq
    "share" -> Icons.Rounded.Share
    "join" -> Icons.Rounded.Headphones
    "leave" -> Icons.Rounded.Close
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
