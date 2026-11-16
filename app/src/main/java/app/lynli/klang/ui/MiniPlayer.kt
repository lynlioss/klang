@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.data.Track
import kotlinx.coroutines.launch

@Composable
internal fun MiniPlayer(state: MusicState, model: MusicViewModel, expand: () -> Unit) {
    val track = state.current ?: return
    val latestState by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val settleOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Offset>()
    val slide = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val animate = motionEnabled()
    val paused = !state.playing && !state.playWhenReady

    LaunchedEffect(track.id) {
        if (!settling) {
            dragging = false
            dragOffset = Offset.Zero
            settleOffset.snapTo(Offset.Zero)
        }
    }

    CoverTheme(track) {
        val colors = MaterialTheme.colorScheme
        // Keep pointer coordinates stationary while the child surface follows the finger.
        Box(Modifier.fillMaxWidth()
            .graphicsLayer {
                val offset = if (dragging) dragOffset else settleOffset.value
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(track.id, animate) {
                var axis: MiniPlayerSwipeAxis? = null
                var displacement = Offset.Zero
                var accepting = false
                val velocityTracker = VelocityTracker()
                // Klang gesture choices, independent of Material's motion and touch-slop tokens.
                val horizontalThreshold = 64.dp.toPx()
                val verticalThreshold = 48.dp.toPx()
                val minFlingDistance = 20.dp.toPx()
                val minFlingVelocity = 800.dp.toPx()

                fun settle(action: MiniPlayerSwipeAction) {
                    if (!animate) {
                        dragging = false
                        dragOffset = Offset.Zero
                        when (action) {
                            MiniPlayerSwipeAction.Next, MiniPlayerSwipeAction.Previous ->
                                model.skipTrack(track.id, forward = action == MiniPlayerSwipeAction.Next)
                            MiniPlayerSwipeAction.Dismiss -> model.dismissPlayer(track.id)
                            MiniPlayerSwipeAction.None -> Unit
                        }
                        return
                    }
                    settling = true
                    scope.launch {
                        try {
                            // Hand the finger's offset to the transition. The whole stack then glides
                            // home while AnimatedContent slides old and new cards over each other, so
                            // there is never an empty player between two songs.
                            settleOffset.snapTo(dragOffset)
                            dragging = false
                            when (action) {
                                MiniPlayerSwipeAction.Next, MiniPlayerSwipeAction.Previous -> {
                                    model.skipTrack(track.id, forward = action == MiniPlayerSwipeAction.Next)
                                    settleOffset.animateTo(Offset.Zero, spatial)
                                }
                                MiniPlayerSwipeAction.Dismiss -> {
                                    settleOffset.animateTo(Offset(0f, size.height * 3f), spatial)
                                    // Playback can resume while the finger is down; bring the card back if
                                    // the live player no longer allows dismissing it.
                                    if (!model.dismissPlayer(track.id)) settleOffset.animateTo(Offset.Zero, spatial)
                                }
                                MiniPlayerSwipeAction.None -> settleOffset.animateTo(Offset.Zero, spatial)
                            }
                            dragOffset = Offset.Zero
                        } finally {
                            settling = false
                            dragging = false
                        }
                    }
                }

                detectDragGestures(orientationLock = null,
                    onDragStart = { down, change, _ ->
                        accepting = !settling
                        if (accepting) {
                            axis = null
                            displacement = Offset.Zero
                            velocityTracker.resetTracking()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            dragOffset = Offset.Zero
                            dragging = true
                        }
                    },
                    onDragCancel = { if (accepting) settle(MiniPlayerSwipeAction.None) },
                    onDragEnd = { change ->
                        if (accepting) {
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val velocity = velocityTracker.calculateVelocity()
                            val current = latestState
                            settle(miniPlayerSwipeAction(axis,
                                distance = if (axis == MiniPlayerSwipeAxis.Horizontal) displacement.x else displacement.y,
                                velocity = if (axis == MiniPlayerSwipeAxis.Horizontal) velocity.x else velocity.y,
                                distanceThreshold = if (axis == MiniPlayerSwipeAxis.Horizontal) horizontalThreshold else verticalThreshold,
                                minFlingDistance = minFlingDistance, minFlingVelocity = minFlingVelocity,
                                paused = !current.playing && !current.playWhenReady,
                                hasPrevious = current.hasPreviousTrack, hasNext = current.hasNextTrack))
                        }
                    }) { change, amount ->
                    change.consume()
                    if (accepting) {
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val lockedAxis = axis ?: miniPlayerSwipeAxis(amount.x, amount.y).also { axis = it }
                        displacement += amount
                        val current = latestState
                        dragOffset = when (lockedAxis) {
                            MiniPlayerSwipeAxis.Horizontal -> {
                                val available = if (displacement.x < 0f) current.hasNextTrack else current.hasPreviousTrack
                                Offset(displacement.x * if (available) 1f else 0.2f, 0f)
                            }
                            MiniPlayerSwipeAxis.Vertical -> Offset(0f,
                                if (!current.playing && !current.playWhenReady) displacement.y.coerceAtLeast(0f) else 0f)
                        }
                    }
                }
            }) {
            AnimatedContent(track.id, transitionSpec = {
                // Follow the queue position rather than only the gesture, so buttons, automatic
                // advances and swipes all slide in the direction the library is moving.
                val from = state.queue.indexOfFirst { it.id == initialState }
                val to = state.queue.indexOfFirst { it.id == targetState }
                val dir = if (from >= 0 && to >= 0 && to != from) if (to > from) 1 else -1 else 1
                val enter = fadeIn(if (animate) effects else snap()) +
                    slideInHorizontally(animationSpec = if (animate) slide else snap()) { width -> dir * width }
                val exit = fadeOut(if (animate) effects else snap()) +
                    slideOutHorizontally(animationSpec = if (animate) slide else snap()) { width -> -dir * width }
                enter togetherWith exit
            }, label = "Mini player track") { id ->
                val shown = state.tracks.find { it.id == id }
                    ?: state.hiddenTracks.find { it.id == id }
                    ?: track
                MiniPlayerCard(shown, state, model, expand, paused, colors)
            }
        }
    }
}

@Composable
private fun MiniPlayerCard(track: Track, state: MusicState, model: MusicViewModel, expand: () -> Unit,
    paused: Boolean, colors: ColorScheme) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).semantics {
        customActions = buildList {
            if (state.hasPreviousTrack) add(CustomAccessibilityAction("Previous song") { model.skipTrack(track.id, forward = false) })
            if (state.hasNextTrack) add(CustomAccessibilityAction("Next song") { model.skipTrack(track.id, forward = true) })
            if (paused) add(CustomAccessibilityAction("Dismiss player") { model.dismissPlayer(track.id) })
        }
    }.clickable(onClickLabel = "Open now playing", onClick = expand),
        color = colors.primaryContainer, shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FavoriteButton(track, state, model, colors.onPrimaryContainer)
                IconButton(model::togglePlay) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) "Pause" else "Play", tint = colors.onPrimaryContainer)
                }
                IconButton(model::next, enabled = state.hasNextTrack) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = colors.onPrimaryContainer)
                }
            }
            LinearProgressIndicator(progress = {
                if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f
            }, modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.primary, trackColor = Color.Transparent)
        }
    }
}
