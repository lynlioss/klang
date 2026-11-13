@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.equalizer.*
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun EqualizerScreen(back: () -> Unit, songTitle: String?) {
    BackHandler { back() }
    val context = LocalContext.current
    fun hasBluetoothAccess() = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var bluetoothAccess by remember { mutableStateOf(hasBluetoothAccess()) }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        bluetoothAccess = hasBluetoothAccess()
    }
    val store = rememberEqStore()
    val settings by store.settings.collectAsStateWithLifecycle()
    val route by store.runtime.collectAsStateWithLifecycle()
    val curve = settings.resolve(route.songId, route.deviceId)
    val presets = BUILTIN_PRESETS + settings.presets
    val name = presets.find { it.id == curve.presetId }?.name ?: if (curve.gains == FLAT_GAINS) "Flat" else "Custom"
    var save by rememberSaveable { mutableStateOf(false) }
    var manage by rememberSaveable { mutableStateOf(false) }
    val songAssigned = route.songId in settings.songCurves
    val deviceAssigned = route.deviceId in settings.deviceCurves
    val scopeLabel = when { songAssigned -> songTitle ?: "This song"; deviceAssigned -> route.deviceName; else -> "Default sound" }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    Switch(settings.enabled, { value -> store.update { it.copy(enabled = value) } },
                        modifier = Modifier.semantics { contentDescription = "Enable equalizer" })
                }
                Text("Equalizer", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.displaySmall)
                Text(scopeLabel, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedContent(name, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "EQ preset") {
                                Text(it, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(if (settings.enabled) "ON" else "BYPASS", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        EqCurvePreview(curve, Modifier.fillMaxWidth().height(88.dp).padding(top = 16.dp))
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets, key = { it.id }) { preset ->
                        FilterChip(curve.presetId == preset.id, { store.editCurve(preset.curve()) }, label = { Text(preset.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ save = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Save preset") }
                    if (settings.presets.isNotEmpty()) TextButton({ manage = true }) { Text("Manage") }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        EQ_FREQUENCIES.forEachIndexed { index, frequency ->
                            val value = curve.gains[index]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(frequencyLabel(frequency), Modifier.width(52.dp), style = MaterialTheme.typography.labelLarge)
                                CenteredSlider(value, { store.editCurve(curve.withBand(index, it)) },
                                    Modifier.weight(1f).semantics { contentDescription = "${frequencyLabel(frequency)} gain in decibels" })
                                Text(dbLabel(value), Modifier.width(44.dp), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton({ store.editCurve(EqCurve()) }) { Text("Reset to flat") }
                        }
                    }
                }
            }
            item {
                Text("Remember this sound", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Song presets take priority over headphones. Everything else uses your default.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        AssignmentRow("This song", songTitle ?: "Play a song to save its sound", songAssigned, route.songId != null) { checked ->
                            route.songId?.let { id -> store.update {
                                it.copy(songCurves = if (checked) it.songCurves + (id to curve) else it.songCurves - id)
                            } }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (!bluetoothAccess) {
                            Text("Allow nearby devices to remember a different sound for each pair of headphones.",
                                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            TextButton({ bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) }) {
                                Text("Enable headphone presets")
                            }
                        }
                        AssignmentRow("Bluetooth headphones", if (route.deviceId != null) route.deviceName else "Connect headphones to save their sound",
                            deviceAssigned, route.deviceId != null) { checked ->
                            route.deviceId?.let { id -> store.update {
                                it.copy(deviceCurves = if (checked) it.deviceCurves + (id to curve) else it.deviceCurves - id)
                            } }
                        }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Output trim", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Automatic headroom protects boosted frequencies. Lower the output further if your headphones need it.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val trimSlider = remember { SliderState(trackRange = -12f..0f) }
                            trimSlider.value = settings.outputTrimDb
                            Slider(state = trimSlider, onValueChange = { value -> store.update { it.copy(outputTrimDb = value) } },
                                modifier = Modifier.weight(1f).semantics { contentDescription = "Output trim in decibels" })
                            Text(dbLabel(settings.outputTrimDb), Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
    if (save) PresetNameDialog({ save = false }) { entered ->
        val preset = settings.savePreset(entered, curve)
        store.update { it.copy(presets = it.presets + preset) }
        store.editCurve(preset.curve()); save = false
    }
    if (manage) AlertDialog(onDismissRequest = { manage = false }, title = { Text("Your presets") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(settings.presets, key = { it.id }) { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preset.name, Modifier.weight(1f))
                    IconButton({ store.update { value ->
                        fun unlabel(curve: EqCurve) = if (curve.presetId == preset.id) curve.copy(presetId = null) else curve
                        value.copy(presets = value.presets.filterNot { it.id == preset.id }, defaultCurve = unlabel(value.defaultCurve),
                            songCurves = value.songCurves.mapValues { unlabel(it.value) },
                            deviceCurves = value.deviceCurves.mapValues { unlabel(it.value) })
                    } }) { Icon(Icons.Rounded.Delete, "Delete ${preset.name}") }
                }
            }
        }
    }, confirmButton = { TextButton({ manage = false }) { Text("Done") } })
}

@Composable
internal fun rememberEqStore(): EqStore {
    val context = LocalContext.current
    return remember(context) { EqStore.get(context) }
}

@Composable
private fun AssignmentRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Remember equalizer for $title" })
    }
}

@Composable
private fun CenteredSlider(value: Float, change: (Float) -> Unit, modifier: Modifier) {
    val slider = remember { SliderState(trackRange = -12f..12f) }
    slider.value = value
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    Slider(state = slider, onValueChange = { change((it * 2).roundToInt() / 2f) }, modifier = modifier,
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val middle = size.width / 2
                val end = size.width * ((state.value + 12) / 24)
                drawLine(inactive, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 12.dp.toPx(), StrokeCap.Round)
                drawLine(primary, Offset(middle, size.height / 2), Offset(end, size.height / 2), 12.dp.toPx(), StrokeCap.Round)
                drawCircle(primary, 2.dp.toPx(), Offset(middle, size.height / 2))
            }
        })
}

@Composable
private fun EqCurvePreview(curve: EqCurve, modifier: Modifier) {
    val values = curve.gains.map { animateFloatAsState(it, spring(1f, 1600f), label = "EQ curve").value }
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(modifier) {
        val center = size.height / 2
        drawLine(ink.copy(alpha = 0.2f), Offset(0f, center), Offset(size.width, center), 1.dp.toPx())
        val path = Path()
        val points = values.mapIndexed { i, db -> Offset(size.width * i / (values.size - 1), center - db / 12 * center) }
        path.moveTo(points[0].x, points[0].y)
        points.zipWithNext().forEach { (a, b) ->
            val mid = (a.x + b.x) / 2
            path.cubicTo(mid, a.y, mid, b.y, b.x, b.y)
        }
        drawPath(path, ink, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun PresetNameDialog(dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Save preset") }, text = {
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Preset name") }, singleLine = true)
    }, confirmButton = { TextButton({ save(name.trim()) }, enabled = name.trim().isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
private fun frequencyLabel(hz: Double) = if (hz >= 1000) "${(hz / 1000).roundToInt()}k Hz" else "${hz.roundToInt()} Hz"
private fun dbLabel(value: Float): String = (if (value > 0) "+" else "") + String.format(Locale.ROOT, "%.1f", value) + " dB"
