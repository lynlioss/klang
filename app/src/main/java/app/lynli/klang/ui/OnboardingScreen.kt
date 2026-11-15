@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState

@Composable
internal fun OnboardingScreen(state: MusicState, musicAccess: () -> Unit, notifications: () -> Unit,
    chooseFiles: () -> Unit, complete: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("Klang", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.displaySmall)
                Text("Your music,\nright here.", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold)
                Text("Connect the music on your phone to start listening.", Modifier.padding(top = 12.dp, bottom = 28.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PermissionCard(Icons.Rounded.LibraryMusic, "Music & audio", "Find and play the songs saved on your phone.",
                    state.permission, "Allow music access", musicAccess)
                Spacer(Modifier.height(12.dp))
                PermissionCard(Icons.Rounded.Notifications, "Playback notifications", "Keep playback controls close, even outside Klang. Optional.",
                    state.notificationPermission, "Allow notifications", notifications)
                TextButton(chooseFiles, Modifier.padding(top = 16.dp)) { Text("Choose individual audio files") }
                Text("Optional analytics use PostHog in the EU.", Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
            Button(complete, Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (state.permission || state.tracks.isNotEmpty()) "Start listening" else "Continue for now")
            }
        }
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, description: String, granted: Boolean,
    action: String, request: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(description, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (granted) Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Allowed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else FilledTonalButton(request, Modifier.padding(top = 12.dp)) { Text(action) }
        }
    }
}
