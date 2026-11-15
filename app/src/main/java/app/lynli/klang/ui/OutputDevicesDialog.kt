@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.media.MediaRouter2
import android.media.MediaRoute2Info
import android.media.RouteDiscoveryPreference
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lynli.klang.data.Track
import kotlinx.coroutines.launch

/** App-owned output picker inherits the same cover theme as the now-playing controls. */
@Composable
internal fun OutputDevicesDialog(track: Track, dismiss: () -> Unit) {
    val context = LocalContext.current
    val router = remember { MediaRouter2.getInstance(context) }
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
    val routes = remember(revision, granted) {
        val controller = router.systemController
        (controller.selectedRoutes + controller.transferableRoutes).distinctBy { it.id }
    }
    val selectedIds = router.systemController.selectedRoutes.map { it.id }.toSet()
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
                    val selected = route.id in selectedIds
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
                error?.let { message -> item {
                    Text(message, Modifier.padding(vertical = 12.dp), color = colors.error,
                        style = MaterialTheme.typography.bodyMedium)
                } }
            }
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
