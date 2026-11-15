@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.lynli.klang.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import app.lynli.klang.data.Track

/** The collection header occupies the first lazy item; songs use their persistent IDs as keys. */
@Stable
internal class PlaylistReorderState(val list: LazyListState, initial: List<Track>) {
    var tracks by mutableStateOf(initial)
        private set
    var draggingId by mutableStateOf<String?>(null)
        private set
    var settlingId by mutableStateOf<String?>(null)
        private set
    val settleOffset = Animatable(0f)
    private var top by mutableFloatStateOf(0f)
    private var height = 0
    var pointerY by mutableFloatStateOf(0f)
        private set

    fun sync(updated: List<Track>) {
        if (draggingId == null && settlingId == null) tracks = updated
        else if (tracks.map { it.id }.toSet() != updated.map { it.id }.toSet()) {
            draggingId = null; tracks = updated
        }
    }

    fun start(y: Float): Boolean {
        if (settlingId != null) return false
        val item = list.layoutInfo.visibleItemsInfo.find { y >= it.offset && y < it.offset + it.size }
            ?.takeIf { item -> tracks.any { it.id == item.key } } ?: return false
        draggingId = item.key as String
        top = item.offset.toFloat(); height = item.size; pointerY = y
        return true
    }

    fun drag(delta: Float): Boolean {
        top += delta; pointerY += delta
        return moveIfNeeded()
    }

    fun checkPosition(): Boolean = moveIfNeeded()

    private fun moveIfNeeded(): Boolean {
        val id = draggingId ?: return false
        val from = tracks.indexOfFirst { it.id == id }
        val visible = list.layoutInfo.visibleItemsInfo
        // Wait for a swapped list to lay out before evaluating another crossing.
        if (visible.find { it.key == id }?.index != from + 1) return false
        val center = top + height / 2f
        val target = visible.filter { it.key != id }.firstOrNull { item ->
            val index = tracks.indexOfFirst { it.id == item.key }
            index >= 0 && item.index == index + 1 &&
                ((index > from && center > item.offset + item.size / 2f) ||
                    (index < from && center < item.offset + item.size / 2f))
        } ?: return false
        val to = tracks.indexOfFirst { it.id == target.key }
        // Keep the viewport fixed when its first visible song changes position.
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        tracks = tracks.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }

    fun offset(id: String): Float = when (id) {
        draggingId -> top - (list.layoutInfo.visibleItemsInfo.find { it.key == id }?.offset ?: top.toInt())
        settlingId -> settleOffset.value
        else -> 0f
    }

    suspend fun finish(save: (List<String>) -> Unit, haptics: HapticFeedback) {
        val id = draggingId ?: return
        val offset = offset(id)
        settlingId = id
        settleOffset.snapTo(offset)
        draggingId = null
        save(tracks.map { it.id })
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        try { settleOffset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 380f)) }
        finally { settlingId = null }
    }

    fun cancel(updated: List<Track>) {
        draggingId = null; tracks = updated
    }
}

internal fun Modifier.playlistDrag(state: PlaylistReorderState, haptics: HapticFeedback,
    finish: () -> Unit, cancel: () -> Unit) = pointerInput(state) {
    detectDragGesturesAfterLongPress(
        onDragStart = { if (state.start(it.y)) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        onDrag = { change, amount ->
            if (state.draggingId != null) {
                change.consume()
                if (state.drag(amount.y)) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        },
        onDragEnd = finish,
        onDragCancel = cancel,
    )
}

@Composable
internal fun PlaylistDragAutoScroll(state: PlaylistReorderState, edge: Float, maxStep: Float, haptics: HapticFeedback) {
    LaunchedEffect(state.draggingId) {
        if (state.draggingId == null) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (state.draggingId != null) {
            val frame = withFrameNanos { it }
            val elapsed = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = frame
            val layout = state.list.layoutInfo
            // Edge zone and scroll speed are app choreography, independent of M3 spring tokens.
            val velocity = when {
                state.pointerY < layout.viewportStartOffset + edge ->
                    -((layout.viewportStartOffset + edge - state.pointerY) / edge).coerceIn(0f, 1f)
                state.pointerY > layout.viewportEndOffset - edge ->
                    ((state.pointerY - layout.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (velocity != 0f) {
                state.list.scrollBy(velocity * maxStep * elapsed)
            }
            if (state.checkPosition()) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
}
