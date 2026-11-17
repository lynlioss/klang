@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.data.Track
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun PlaybackLoadingIndicator(ink: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier.size(32.dp)) {
    LoadingIndicator(modifier.semantics { contentDescription = "Loading song" }, color = ink)
}

/** Check the middle half of a row, where the controls sit, rather than one clipped pixel. */
internal fun controlsAreVisible(offset: Int, size: Int, viewportStart: Int, viewportEnd: Int): Boolean {
    if (size <= 0 || viewportEnd <= viewportStart) return false
    return offset + size / 4 >= viewportStart && offset + size * 3 / 4 <= viewportEnd
}

@Composable
internal fun motionEnabled(): Boolean {
    val context = LocalContext.current
    return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}

@Composable
internal fun FavoriteButton(track: Track, state: MusicState, model: MusicViewModel, ink: Color,
    modifier: Modifier = Modifier) {
    val favorite = track.identities.any { it in state.favorites }
    val scale = remember(track.id) { Animatable(1f) }
    val rotation = remember(track.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val animate = motionEnabled()
    IconToggleButton(favorite, {
        model.favorite(track)
        if (animate) {
            scope.launch { scale.snapTo(0.75f); scale.animateTo(1f, spring(0.6f, 800f), initialVelocity = 13f) }
            scope.launch { rotation.snapTo(if (favorite) 12f else -16f); rotation.animateTo(0f, spring(0.6f, 800f)) }
        }
    }, modifier) {
        Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (favorite) "Remove ${track.title} from favorites" else "Favorite ${track.title}",
            Modifier.scale(scale.value).graphicsLayer { rotationZ = rotation.value }, tint = ink)
    }
}

/** A playing indicator, deliberately decorative rather than a claim of live level metering. */
@Composable
internal fun PlayingIndicator(ink: Color) {
    val enabled = motionEnabled()
    val phase = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (enabled) while (true) {
            phase.snapTo(0f)
            phase.animateTo((2 * PI).toFloat(), tween(1100, easing = LinearEasing))
        }
    }
    Canvas(Modifier.size(24.dp).semantics { contentDescription = "Playing" }) {
        for (bar in 0..3) {
            val height = size.height * (0.25f + 0.55f * ((sin(phase.value + bar * 1.9f) + 1) / 2))
            val x = size.width * (bar + 0.5f) / 4
            drawLine(ink, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                3.dp.toPx(), StrokeCap.Round)
        }
    }
}
