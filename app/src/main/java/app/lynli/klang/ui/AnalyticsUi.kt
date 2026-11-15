@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.lynli.klang.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lynli.klang.analytics.AnalyticsConsent

private const val AnalyticsDescription = "Share how you use Klang and basic device information, including your Android version, " +
    "phone model and app version, with PostHog to help improve the app."

@Composable
internal fun AnalyticsConsentCard(consent: AnalyticsConsent, choose: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                Text("Share analytics?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(AnalyticsDescription, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Optional. Your music, file names and links stay private. Change your choice anytime in Settings → Privacy.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Equal emphasis and no preselection: both are ordinary choices, not an upsell.
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnalyticsChoiceButton("No", consent == AnalyticsConsent.DENIED, { choose(false) }, Modifier.weight(1f))
                AnalyticsChoiceButton("Yes", consent == AnalyticsConsent.GRANTED, { choose(true) }, Modifier.weight(1f))
            }
            Text(when (consent) {
                AnalyticsConsent.NOT_ASKED -> "Choose Yes or No to continue."
                AnalyticsConsent.GRANTED -> "Analytics on"
                AnalyticsConsent.DENIED -> "Analytics off"
            }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnalyticsChoiceButton(label: String, selected: Boolean, click: () -> Unit, modifier: Modifier) {
    FilledTonalButton(onClick = click, modifier = modifier.heightIn(min = 48.dp).semantics { this.selected = selected },
        shapes = ButtonDefaults.shapes(
            shape = if (selected) MaterialTheme.shapes.small else ButtonDefaults.shape,
        ), colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        )) {
        if (selected) {
            Icon(Icons.Rounded.Check, null, Modifier.size(ButtonDefaults.SmallIconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(label)
    }
}

@Composable
internal fun AnalyticsSetting(consent: AnalyticsConsent, change: (Boolean) -> Unit) {
    val enabled = consent == AnalyticsConsent.GRANTED
    Surface(Modifier.fillMaxWidth().padding(bottom = 4.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = change).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Share analytics", style = MaterialTheme.typography.titleMedium)
                    Text(if (enabled) "On · tap to stop sharing" else "Off · tap to enable",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = null)
            }
            Text(AnalyticsDescription, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your music, file names and links stay private. Turning this off stops sharing immediately.",
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
