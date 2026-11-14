@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.lynli.klang.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MaxSleepMinutes = 180

/**
 * A radial timer instead of a row of preset buttons: drag anywhere around the ring to dial in any
 * length, with quick presets and fine steps as shortcuts.
 */
@Composable
internal fun SleepTimerSheet(state: MusicState, model: MusicViewModel, dismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val active = state.sleepEndsAt > System.currentTimeMillis()
    var minutes by remember(state.sleepEndsAt) {
        val initial = if (active) ((state.sleepRemaining + 59_999) / 60_000).toInt().coerceIn(1, MaxSleepMinutes) else 30
        mutableIntStateOf(initial)
    }
    val primary = MaterialTheme.colorScheme.primary
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(modifier = Modifier.padding(top = 10.dp)) }) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Sleep timer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (active) "Playback fades out after the timer ends." else "Playback pauses when the timer ends.",
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(dismiss) { Icon(Icons.Rounded.Close, "Close sleep timer") }
            }
            Spacer(Modifier.height(12.dp))
            SleepDial(minutes, active) { minutes = it }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton({ minutes = (minutes - 5).coerceIn(0, MaxSleepMinutes) },
                    shape = RoundedCornerShape(18.dp)) { Text("− 5 min") }
                FilledTonalButton({ minutes = (minutes + 5).coerceIn(0, MaxSleepMinutes) },
                    shape = RoundedCornerShape(18.dp)) { Text("+ 5 min") }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60, 90).forEach { preset ->
                    FilterChip(minutes == preset, { minutes = preset }, label = { Text(if (preset >= 60) "${preset / 60} h" else "$preset m") })
                }
            }
            if (active) {
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ImageVector.vectorResource(R.drawable.timer), null, Modifier.size(20.dp), tint = primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Ends in ${remainingText(state.sleepRemaining)}", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (active) TextButton({ model.sleep(0); dismiss() }, Modifier.height(56.dp)) { Text("End timer") }
                Button({ model.sleep(minutes); dismiss() }, Modifier.weight(1f).height(56.dp),
                    enabled = minutes > 0, shape = RoundedCornerShape(20.dp)) {
                    Icon(ImageVector.vectorResource(R.drawable.timer), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (active) "Update timer" else "Start timer")
                }
            }
        }
    }
}

@Composable
private fun SleepDial(minutes: Int, active: Boolean, change: (Int) -> Unit) {
    val label = if (minutes == 0) "Off" else if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val ticks = MaterialTheme.colorScheme.outlineVariant
    val sweep by animateFloatAsState(minutes / MaxSleepMinutes.toFloat() * 360f,
        spring(dampingRatio = 0.7f, stiffness = 500f), label = "Sleep sweep")
    Box(Modifier.size(272.dp).semantics {
        contentDescription = "Sleep timer: $label. Drag around the ring to change."
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures { event, _ ->
                event.consume()
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                var degrees = Math.toDegrees(atan2(event.position.y - centerY,
                    event.position.x - centerX).toDouble()).toFloat() + 90f
                if (degrees < 0f) degrees += 360f
                val raw = degrees / 360f * MaxSleepMinutes
                change((raw / 5f).roundToInt().coerceIn(0, MaxSleepMinutes / 5) * 5)
            }
        }) {
            val center = size.center
            val radius = size.minDimension / 2f - 22.dp.toPx()
            val stroke = 14.dp.toPx()
            drawCircle(primary.copy(alpha = 0.10f), radius = radius - 6.dp.toPx(), center = center)
            drawCircle(track, radius = radius, center = center, style = Stroke(stroke))
            val tickCount = 36
            for (index in 0 until tickCount) {
                val angle = (index * 360f / tickCount - 90f) * PI.toFloat() / 180f
                val outer = radius - 16.dp.toPx()
                val inner = outer - if (index % 3 == 0) 8.dp.toPx() else 4.dp.toPx()
                drawLine(ticks.copy(alpha = 0.8f),
                    Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                    Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                    if (index % 3 == 0) 2.5.dp.toPx() else 1.5.dp.toPx(), StrokeCap.Round)
            }
            if (sweep > 0f) {
                drawArc(brush = Brush.sweepGradient(listOf(primary, secondary, primary)),
                    startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2f, radius * 2f),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                val end = Math.toRadians((-90f + sweep).toDouble())
                drawCircle(primary, radius = 9.dp.toPx(),
                    center = Offset(center.x + cos(end).toFloat() * radius, center.y + sin(end).toFloat() * radius))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(minutes, transitionSpec = { fadeIn(spring(1f, 800f)) togetherWith fadeOut(spring(1f, 800f)) },
                label = "Sleep minutes") { value ->
                Text(if (value == 0) "Off" else "$value", style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black)
            }
            Text(when {
                minutes == 0 -> "drag the ring to set"
                minutes >= 60 -> "hours"
                else -> "minutes"
            }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (active) Text("Running", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall,
                color = primary)
        }
    }
}

internal fun remainingText(milliseconds: Long): String {
    val total = (milliseconds / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
