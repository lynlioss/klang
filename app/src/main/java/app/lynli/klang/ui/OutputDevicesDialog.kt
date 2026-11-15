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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Fallback for OEMs that do not expose the Android output switcher. */
@Composable
internal fun OutputDevicesDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    val router = remember { MediaRouter2.getInstance(context) }
    var granted by remember { mutableStateOf(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
    }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(router) {
        val callback = object : MediaRouter2.RouteCallback() {
            override fun onRoutesUpdated(routes: List<MediaRoute2Info>) { revision++ }
        }
        router.registerRouteCallback(context.mainExecutor, callback,
            RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build())
        onDispose { router.unregisterRouteCallback(callback) }
    }
    val routes = remember(revision, granted) {
        val controller = router.systemController
        (controller.selectedRoutes + controller.transferableRoutes).distinctBy { it.id }
    }
    val selectedIds = router.systemController.selectedRoutes.map { it.id }.toSet()
    AlertDialog(onDismissRequest = dismiss, shape = MaterialTheme.shapes.extraLarge, title = { Text("Play on") }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!granted) item {
                Text("Allow nearby devices to choose connected headphones.")
                TextButton({ permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) }) {
                    Text("Show headphones")
                }
            }
            items(routes, key = { it.id }) { route ->
                ListItem(headlineContent = { Text(route.name.toString()) }, supportingContent = {
                    Text(if (route.id in selectedIds) "Current output" else "Available")
                }, trailingContent = { if (route.id in selectedIds) Icon(Icons.Rounded.Check, null) },
                    colors = ListItemDefaults.colors(containerColor = if (route.id in selectedIds)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        headlineColor = if (route.id in selectedIds) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        supportingColor = if (route.id in selectedIds) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        trailingIconColor = if (route.id in selectedIds) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable {
                    runCatching { router.transferTo(route) }; dismiss()
                })
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("Done") } }, dismissButton = {
        TextButton({
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); dismiss()
        }) { Text("Connect device") }
    })
}
