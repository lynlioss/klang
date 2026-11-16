@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lynli.klang.AppIcon
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel

/** Swaps the launcher icon. Choices come from AppIcon, so new variants appear here automatically. */
@Composable
internal fun AppIconScreen(state: MusicState, model: MusicViewModel, back: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(132.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to settings") }
                    Text("App icon", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.displayMedium)
                    Text("Choose how Klang looks on your home screen.",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(AppIcon.entries, key = { it.storageValue }) { icon ->
                AppIconChoice(icon, state.appIcon == icon) { model.appIcon(icon) }
            }
        }
    }
}

@Composable
private fun AppIconChoice(icon: AppIcon, selected: Boolean, choose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        spring(dampingRatio = 1f, stiffness = 800f), label = "Icon container")
    val outline by animateColorAsState(
        if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f),
        spring(dampingRatio = 1f, stiffness = 800f), label = "Icon outline")
    Surface(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton,
                onClickLabel = "Use the ${icon.label} icon", onClick = choose),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        border = BorderStroke(1.dp, outline),
    ) {
        Box(Modifier.padding(20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(icon.preview), null,
                    Modifier.size(96.dp).clip(CircleShape)
                        .border(1.dp, colors.outlineVariant.copy(alpha = 0.35f), CircleShape),
                    contentScale = ContentScale.Crop)
                Text(icon.label, style = MaterialTheme.typography.titleMediumEmphasized,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurface)
            }
            if (selected) {
                Surface(Modifier.align(Alignment.TopEnd).size(28.dp), shape = CircleShape,
                    color = colors.primary, contentColor = colors.onPrimary) {
                    Icon(Icons.Rounded.Check, null, Modifier.padding(6.dp))
                }
            }
        }
    }
}
