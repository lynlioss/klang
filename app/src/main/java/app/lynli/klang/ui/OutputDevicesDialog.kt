@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.media.MediaRoute2Info
import android.media.RouteDiscoveryPreference
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lynli.klang.data.Track
import app.lynli.klang.playback.PlaybackOutput
import app.lynli.klang.playback.currentAudioOutputs
import app.lynli.klang.playback.outputRoute
import app.lynli.klang.playback.outputSelection
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** App-owned output picker inherits the same cover theme as the now-playing controls. */
@Composable
internal fun OutputDevicesDialog(track: Track, dismiss: () -> Unit) {
    val context = LocalContext.current
    val router = remember { MediaRouter2.getInstance(context) }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val observedDevices by PlaybackOutput.devices.collectAsState()
    var granted by remember { mutableStateOf(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
    }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(router) {
        val callback = object : MediaRouter2.RouteCallback() {
            override fun onRoutesUpdated(routes: List<MediaRoute2Info>) { revision++ }
        }
        val controllerCallback = object : MediaRouter2.ControllerCallback() {
            override fun onControllerUpdated(controller: MediaRouter2.RoutingController) { revision++ }
        }
        val transferCallback = object : MediaRouter2.TransferCallback() {
            override fun onTransfer(oldController: MediaRouter2.RoutingController, newController: MediaRouter2.RoutingController) {
                error = null
                revision++
            }
            override fun onTransferFailure(requestedRoute: MediaRoute2Info) {
                error = "Couldn't switch to ${requestedRoute.name}. Try connecting the device again."
                revision++
            }
        }
        router.registerRouteCallback(context.mainExecutor, callback,
            RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build())
        router.registerControllerCallback(context.mainExecutor, controllerCallback)
        router.registerTransferCallback(context.mainExecutor, transferCallback)
        onDispose {
            router.unregisterRouteCallback(callback)
            router.unregisterControllerCallback(controllerCallback)
            router.unregisterTransferCallback(transferCallback)
        }
    }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) { revision++ }
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) { revision++ }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { revision++ }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_DEVICES_CHANGED_ACTION")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            audioManager.unregisterAudioDeviceCallback(callback)
            context.unregisterReceiver(receiver)
        }
    }
    val controller = remember(revision, granted) {
        router.controllers.lastOrNull { !it.isReleased && it.selectedRoutes.any { route -> !route.isSystemRoute } }
            ?: router.systemController
    }
    val routes = remember(revision, granted, controller) {
        (controller.selectedRoutes + controller.transferableRoutes + router.routes).distinctBy { it.id }
    }
    val selection = remember(revision, granted, controller, routes, observedDevices) {
        outputSelection(routes.map { it.outputRoute() }, controller.selectedRoutes.map { it.id }.toSet(),
            currentAudioOutputs(audioManager, observedDevices))
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(dismiss)
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            try {
                sheet.hide()
                if (!sheet.isVisible) latestDismiss()
            } finally { closing = false }
        }
    }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, sheetGesturesEnabled = !closing,
        shape = KlangSheetShape, containerColor = colors.surfaceContainerLow, contentColor = colors.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.padding(top = 10.dp)) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("Play on", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmallEmphasized)
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(64.dp).clip(MaterialTheme.shapes.large))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                if (!granted) item {
                    Text("Allow nearby devices to choose connected headphones.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    TextButton({ permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) }) {
                        Text("Show headphones")
                    }
                }
                items(routes, key = { it.id }) { route ->
                    val selected = route.id in selection.routeIds
                    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface
                    val icon = when (route.type) {
                        MediaRoute2Info.TYPE_BUILTIN_SPEAKER -> Icons.Rounded.PhoneAndroid
                        MediaRoute2Info.TYPE_WIRED_HEADSET, MediaRoute2Info.TYPE_WIRED_HEADPHONES,
                        MediaRoute2Info.TYPE_BLUETOOTH_A2DP, MediaRoute2Info.TYPE_BLE_HEADSET -> Icons.Rounded.Headphones
                        else -> Icons.Rounded.Speaker
                    }
                    ListItem(headlineContent = { Text(route.name.toString()) }, supportingContent = {
                        Text(if (selected) "Current output" else "Available")
                    }, leadingContent = { Icon(icon, null) },
                        trailingContent = { if (selected) Icon(Icons.Rounded.Check, null) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
                            headlineColor = contentColor,
                            supportingColor = if (selected) contentColor else colors.onSurfaceVariant,
                            leadingIconColor = contentColor, trailingIconColor = contentColor),
                        modifier = Modifier.clip(MaterialTheme.shapes.medium).selectable(
                            selected = selected, enabled = !closing, role = Role.RadioButton,
                            onClick = {
                                if (!selected) {
                                    error = null
                                    runCatching { router.transferTo(route) }.onFailure {
                                        error = "Couldn't switch to ${route.name}. Try connecting the device again."
                                    }
                                }
                            }))
                }
                items(selection.undiscovered, key = { "audio:${it.id}" }) { device ->
                    ListItem(headlineContent = { Text(device.name) }, supportingContent = { Text("Current output") },
                        leadingContent = { Icon(if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                            Icons.Rounded.PhoneAndroid else Icons.Rounded.Headphones, null) },
                        trailingContent = { Icon(Icons.Rounded.Check, null) },
                        colors = ListItemDefaults.colors(containerColor = colors.secondaryContainer,
                            headlineColor = colors.onSecondaryContainer, supportingColor = colors.onSecondaryContainer,
                            leadingIconColor = colors.onSecondaryContainer, trailingIconColor = colors.onSecondaryContainer),
                        modifier = Modifier.clip(MaterialTheme.shapes.medium))
                }
                error?.let { message -> item {
                    Text(message, Modifier.padding(vertical = 12.dp), color = colors.error,
                        style = MaterialTheme.typography.bodyMedium)
                } }
            }
            OutputVolumeSlider(audioManager, controller,
                selection.routeIds.sorted() + selection.undiscovered.map { "audio:${it.id}" },
                revision, !closing) { error = it }
            FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val buttonHeight = ButtonDefaults.MediumContainerHeight
                TextButton({
                    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        .onSuccess { close() }
                        .onFailure { error = "Couldn't open Bluetooth settings." }
                }, enabled = !closing, modifier = Modifier.heightIn(min = buttonHeight),
                    shapes = ButtonDefaults.shapesFor(buttonHeight)) { Text("Connect device") }
                Button({ close() }, enabled = !closing, modifier = Modifier.heightIn(min = buttonHeight),
                    shapes = ButtonDefaults.shapesFor(buttonHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight)) {
                    Text("Done", style = ButtonDefaults.textStyleFor(buttonHeight))
                }
            }
        }
    }
}

@Composable
private fun OutputVolumeSlider(audioManager: AudioManager, controller: MediaRouter2.RoutingController,
    outputIds: List<String>, revision: Int, enabled: Boolean, onError: (String) -> Unit) {
    val local = controller.selectedRoutes.all { it.isSystemRoute }
    val minimum = if (local) audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
    val maximum = if (local) audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) else controller.volumeMax
    val adjustable = if (local) !audioManager.isVolumeFixed else controller.volumeHandling == MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE
    fun readVolume() = if (local) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) else controller.volume
    var volume by remember(controller.id, outputIds) { mutableFloatStateOf(readVolume().toFloat()) }
    var dragging by remember(controller.id, outputIds) { mutableStateOf(false) }
    LaunchedEffect(revision, controller.id, outputIds) { if (!dragging) volume = readVolume().toFloat() }
    val colors = MaterialTheme.colorScheme
    val sliderColors = SliderDefaults.colors()
    val sliderEnabled = enabled && adjustable && maximum > minimum
    val range = minimum.toFloat()..maximum.coerceAtLeast(minimum + 1).toFloat()
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (!adjustable) Text("Fixed volume", style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant)
        }
        val sliderState = remember(range) { SliderState(volume.coerceIn(range), 0, range) }
        sliderState.value = volume.coerceIn(range)
        Slider(state = sliderState, onValueChange = { value ->
                    dragging = true
                    volume = value
                    runCatching {
                        val level = value.roundToInt().coerceIn(minimum, maximum)
                        if (level != readVolume()) {
                            if (local) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                            else controller.setVolume(level)
                        }
                    }.onFailure { onError("Couldn't change the volume.") }
                }, onValueChangeFinished = { dragging = false; volume = readVolume().toFloat() },
                enabled = sliderEnabled, colors = sliderColors,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Media volume" },
                track = { state -> ExpressiveSliderTrack(state, sliderColors, sliderEnabled) })
    }
}

/** Material 3 Expressive "medium" slider geometry: thick rounded track, slim 4x44dp handle. */
@Composable
private fun ExpressiveSliderTrack(state: SliderState, colors: SliderColors, enabled: Boolean,
    modifier: Modifier = Modifier) {
    val inactiveColor = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
    val activeColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    Canvas(modifier.fillMaxWidth().height(VolumeTrackHeight)) {
        val outer = VolumeTrackCorner.toPx()
        val inside = 2.dp.toPx()
        val boundary = size.width * state.coercedValueAsFraction
        val handleEdge = 2.dp.toPx() + 6.dp.toPx()
        val activeEnd = (boundary - handleEdge).coerceAtLeast(0f)
        val inactiveStart = (boundary + handleEdge).coerceAtMost(size.width)
        fun segment(start: Float, end: Float, color: Color, startRadius: Float, endRadius: Float) {
            val width = end - start
            if (width <= 0f) return
            val limit = minOf(size.height / 2f, width / 2f)
            val startCorner = CornerRadius(startRadius.coerceAtMost(limit))
            val endCorner = CornerRadius(endRadius.coerceAtMost(limit))
            val path = Path().apply {
                addRoundRect(RoundRect(Rect(start, 0f, end, size.height),
                    topLeft = startCorner, bottomLeft = startCorner,
                    topRight = endCorner, bottomRight = endCorner))
            }
            drawPath(path, color)
        }
        segment(0f, activeEnd, activeColor, outer, inside)
        if (inactiveStart < size.width) {
            segment(inactiveStart, size.width, inactiveColor, inside, outer)
            drawCircle(activeColor, radius = 2.dp.toPx(),
                center = Offset(size.width - outer, size.height / 2f))
        }
    }
}

private val VolumeTrackHeight = 40.dp
private val VolumeTrackCorner = 12.dp
