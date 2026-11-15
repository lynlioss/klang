@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lynli.klang.AppIcon
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R

/** Swaps the launcher icon. The previews are the icon itself, so no labels. */
@Composable
internal fun AppIconScreen(state: MusicState, model: MusicViewModel, back: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            UtilityIconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    AppIconChoice(R.drawable.app_icon_light, "Light icon", state.appIcon == AppIcon.Light) {
                        model.appIcon(AppIcon.Light)
                    }
                    AppIconChoice(R.drawable.app_icon_dark, "Dark icon", state.appIcon == AppIcon.Dark) {
                        model.appIcon(AppIcon.Dark)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.AppIconChoice(icon: Int, description: String, selected: Boolean, choose: () -> Unit) {
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        spring(dampingRatio = 1f, stiffness = 800f), label = "Icon ring")
    val thickness by animateDpAsState(if (selected) 4.dp else 1.dp,
        spring(dampingRatio = 1f, stiffness = 800f), label = "Icon ring width")
    val scale by animateFloatAsState(if (selected) 1f else 0.86f,
        spring(dampingRatio = 0.6f, stiffness = 500f), label = "Icon scale")
    Surface(
        modifier = Modifier.weight(1f).aspectRatio(1f).scale(scale)
            .selectable(selected = selected, role = Role.RadioButton, onClick = choose),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(thickness, ring),
    ) {
        Image(painterResource(icon), description, contentScale = ContentScale.Crop)
    }
}
