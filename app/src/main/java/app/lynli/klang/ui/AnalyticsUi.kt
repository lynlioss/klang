@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.lynli.klang.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lynli.klang.analytics.AnalyticsConsent

private const val AnalyticsDescription = "App usage, Android version, phone model and app version."

@Composable
internal fun AnalyticsConsentScreen(choose: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("Share analytics?", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineLargeEmphasized)
                Text("Help improve Klang by sharing app usage, Android version, phone model and app version.",
                    Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("PostHog · EU region", Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text("Optional. Your music stays private. Change anytime in Settings.", Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { choose(false) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes()) { Text("No") }
                FilledTonalButton(onClick = { choose(true) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes()) { Text("Yes") }
            }
        }
    }
}

@Composable
internal fun AnalyticsSetting(consent: AnalyticsConsent, change: (Boolean) -> Unit) {
    val enabled = consent == AnalyticsConsent.GRANTED
    Surface(Modifier.fillMaxWidth().padding(bottom = 4.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = change).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Share analytics", style = MaterialTheme.typography.titleMedium)
                Text("$AnalyticsDescription PostHog · EU region.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}
