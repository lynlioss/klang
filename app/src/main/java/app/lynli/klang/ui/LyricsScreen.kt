@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package app.lynli.klang.ui

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lynli.klang.MusicState
import app.lynli.klang.MusicViewModel
import app.lynli.klang.R
import app.lynli.klang.lyrics.LyricLine
import app.lynli.klang.lyrics.LyricsDocument
import app.lynli.klang.lyrics.LyricsState
import app.lynli.klang.lyrics.LyricsPlaybackClock
import app.lynli.klang.lyrics.charFill
import app.lynli.klang.lyrics.displayText
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
internal fun LyricsScreen(state: MusicState, model: MusicViewModel, close: () -> Unit) {
    val track = state.current ?: return
    val lyrics by model.lyrics.collectAsStateWithLifecycle()
    val config by model.plugins.collectAsStateWithLifecycle()
    val motion = motionEnabled()
    val scheme = MaterialTheme.motionScheme
    val controls = remember { mutableStateOf(LyricsControlsState(SystemClock.uptimeMillis())) }
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var touchExploration by remember(accessibility) { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    val controlsVisible = controls.value.visible || touchExploration
    LaunchedEffect(controls.value.lastInteractionMs, controls.value.touching, touchExploration) {
        if (controls.value.touching || touchExploration) return@LaunchedEffect
        val remaining = LYRICS_CONTROLS_TIMEOUT_MS - (SystemClock.uptimeMillis() - controls.value.lastInteractionMs)
        delay(remaining.coerceAtLeast(0))
        controls.value = controls.value.hideIfIdle(SystemClock.uptimeMillis())
    }
    DisposableEffect(model) { model.lyricsOpened(); onDispose { model.lyricsClosed() } }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(controls, touchExploration) {
        awaitEachGesture {
            // Observe before children, leaving visible buttons, scrubbing and scrolling untouched.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val revealOnly = !controls.value.visible && !touchExploration
            controls.value = controls.value.touchStarted(SystemClock.uptimeMillis())
            if (revealOnly) down.consume()
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (revealOnly) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                controls.value = controls.value.touchEnded(SystemClock.uptimeMillis())
            }
        }
    }) {
        val compact = maxHeight < 560.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
            LyricsControls(controlsVisible, motion, top = true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(track, Modifier.size(52.dp).graphicsLayer { clip = true; shape = RoundedCornerShape(16.dp) })
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalIconButton(close) { Icon(Icons.Rounded.KeyboardArrowDown, "Back to now playing") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(lyrics, transitionSpec = {
                    fadeIn(if (motion) scheme.defaultEffectsSpec() else snap()) togetherWith
                        fadeOut(if (motion) scheme.defaultEffectsSpec() else snap())
                },
                    label = "Lyrics state") { result ->
                    when (result) {
                        is LyricsState.Ready -> {
                            val manifest = model.pluginManifests.find { it.id == result.document.providerId }
                            val offset = manifest?.let { config.integer(it, "timingOffset") } ?: 0
                            if (result.document.instrumental) LyricsMessage("Just the music", "This track is instrumental.")
                            else LyricsBody(result.document, state, model, offset, compact, controlsVisible, motion)
                        }
                        LyricsState.Loading, LyricsState.Idle -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingIndicator(Modifier.size(64.dp))
                            Text("Finding the words…", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        LyricsState.NotFound -> LyricsMessage("Still listening.", "No lyrics found for this song yet. The music is all yours.", model::retryLyrics)
                        LyricsState.Disabled -> LyricsMessage("Make room for words", "Enable a lyrics provider in Settings → Plugins.")
                        is LyricsState.Error -> LyricsMessage("A quiet moment", result.message, model::retryLyrics)
                    }
                }
            }
            LyricsControls(controlsVisible, motion, top = false) {
                ExpressiveSeekbar(state, model, progressOnly = true, scrubTooltip = true)
                Row(Modifier.widthIn(max = 440.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(model::previous, Modifier.weight(1f).height(if (compact) 56.dp else 64.dp), shape = RoundedCornerShape(32.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(28.dp))
                    }
                    ExpressivePlay(state.playing, model::togglePlay, if (compact) 64.dp else 80.dp)
                    FilledTonalIconButton(model::next, Modifier.weight(1f).height(if (compact) 56.dp else 64.dp),
                        enabled = state.queue.size > 1, shape = RoundedCornerShape(32.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(28.dp))
                    }
                }
                val document = (lyrics as? LyricsState.Ready)?.document
                val provider = document?.let { doc -> model.pluginManifests.find { it.id == doc.providerId } }
                // No sync labels: a word-timed document turns the provider name into a pill, so
                // the pill itself is the "word for word" signal.
                val wordSync = document?.hasWordTiming == true
                val caption = when {
                    document == null || document.instrumental -> ""
                    !document.synchronized -> "Plain lyrics"
                    else -> ""
                }
                if (wordSync && provider != null) {
                    Surface(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(50)) {
                        Text(provider.name, Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                } else Text(listOfNotNull(provider?.name, caption.takeIf { it.isNotBlank() }).joinToString(" · "),
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LyricsControls(visible: Boolean, motion: Boolean, top: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    var heightPx by remember(density.density, density.fontScale) { mutableIntStateOf(0) }
    val scheme = MaterialTheme.motionScheme
    // Reserve the controls' measured space: hiding them never moves the reading anchor.
    Box(Modifier.fillMaxWidth().then(if (!visible && heightPx > 0) Modifier.height(with(density) { heightPx.toDp() }) else Modifier)) {
        AnimatedVisibility(visible,
            enter = fadeIn(if (motion) scheme.defaultEffectsSpec() else snap()) +
                slideInVertically(if (motion) scheme.defaultSpatialSpec() else snap()) { if (top) -it / 8 else it / 12 },
            exit = fadeOut(if (motion) scheme.defaultEffectsSpec() else snap()) +
                slideOutVertically(if (motion) scheme.defaultSpatialSpec() else snap()) { if (top) -it / 8 else it / 12 }) {
            Column(Modifier.fillMaxWidth().onSizeChanged { heightPx = it.height }, content = content)
        }
    }
}

@Composable
private fun LyricsBody(document: LyricsDocument, state: MusicState, model: MusicViewModel, offsetMs: Int, compact: Boolean,
    controlsVisible: Boolean, motion: Boolean) {
    val list = rememberLazyListState()
    var following by rememberSaveable(state.current?.id) { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    val position = remember(state.current?.id) { mutableLongStateOf(state.position) }
    val latestPlayback by rememberUpdatedState(state)
    val clock = remember(state.current?.id) { LyricsPlaybackClock() }
    // Keep one frame loop across the app's periodic state updates. Timeline discontinuities
    // reset the cursor immediately; small controller corrections never rewind the word fill.
    LaunchedEffect(model, state.current?.id) {
        while (true) withFrameNanos { frame ->
            val playback = latestPlayback
            position.longValue = clock.update(model.playbackPosition(), frame,
                playback.playing, playback.playbackDiscontinuity)
        }
    }
    // Aurora semantics: a line becomes current the moment it starts and stays current until the
    // next one starts, so the highlight never blinks off in a gap between lines.
    val current by remember(document, offsetMs, position) { derivedStateOf {
        if (document.synchronized) document.lines.indexOfLast { it.startMs <= position.longValue - offsetMs }
        else -1
    } }
    val focus = current.coerceAtLeast(0)
    val scheme = MaterialTheme.motionScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val anchor = maxHeight * 0.2f
        LaunchedEffect(focus, following, anchor, motion) {
            if (!following || !document.synchronized) return@LaunchedEffect
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focus }
            // Item offsets exclude the before-content padding where the focus line sits.
            if (item != null) {
                // Timed reading must settle without spring overshoot that appears to rewind.
                if (motion) list.animateScrollBy(item.offset.toFloat(), spring(dampingRatio = 1f, stiffness = 800f))
                else list.scrollBy(item.offset.toFloat())
            } else {
                // A seek should land on the requested vocal immediately, without traversing
                // every intervening line while its words have already started.
                list.scrollToItem(focus)
            }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.09f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn)
            }, contentPadding = PaddingValues(top = if (document.synchronized) anchor else 32.dp, bottom = maxHeight * 0.72f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 26.dp)) {
            if (document.synchronized) itemsIndexed(document.lines) { index, line ->
                LyricRow(line, index == current, abs(index - focus), position, offsetMs, compact, motion) {
                    model.seek((line.startMs + offsetMs).coerceAtLeast(0)); following = true
                }
            } else item {
                Text(document.plain, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
        AnimatedVisibility(controlsVisible && !following && document.synchronized,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            enter = fadeIn(if (motion) scheme.defaultEffectsSpec() else snap()),
            exit = fadeOut(if (motion) scheme.defaultEffectsSpec() else snap())) {
            FilledTonalButton({ following = true }) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Follow lyrics")
            }
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, active: Boolean, distance: Int, position: State<Long>, offsetMs: Int,
    compact: Boolean, motion: Boolean, seek: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shown = remember(line) { line.displayText }
    val karaoke = active && line.words.isNotEmpty()
    var layout by remember(shown) { mutableStateOf<TextLayoutResult?>(null) }
    val reveal = remember(line) { Path() }
    val scheme = MaterialTheme.motionScheme
    // Aurora's emphasis: brightness carries the current line, the type size never changes.
    val opacity by animateFloatAsState(
        when {
            active -> 1f
            distance <= 1 -> 0.55f
            else -> 0.35f
        },
        if (motion && !active) scheme.fastEffectsSpec() else snap(), label = "Lyric opacity")
    // Depth of field: the next line stays crisp, every later line softens a little more.
    val soften by animateFloatAsState(
        if (!motion || distance <= 1) 0f else ((distance - 1) * 1.6f).coerceAtMost(7f),
        if (motion && !active) scheme.fastEffectsSpec() else snap(), label = "Lyric softness")
    Text(shown, modifier = Modifier.fillMaxWidth().graphicsLayer {
        alpha = opacity
        val radius = soften.dp.toPx()
        renderEffect = if (soften > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
        clip = renderEffect != null
        // Keep unblurred lines off an offscreen buffer.
        compositingStrategy = if (renderEffect == null) CompositingStrategy.ModulateAlpha else CompositingStrategy.Auto
    }.clickable(role = Role.Button, onClickLabel = "Seek to this lyric", onClick = seek).padding(vertical = 8.dp)
        .drawWithContent {
            drawContent()
            val measured = layout
            if (karaoke && measured != null) {
                // The clock invalidates drawing only. Glyph shaping and line wrapping remain
                // cached, even on short words that finish within a handful of frames.
                val fill = line.charFill(position.value - offsetMs)
                reveal.reset()
                fill.forEachIndexed { index, amount ->
                    if (amount > 0f && !shown[index].isWhitespace()) {
                        val bounds = measured.getBoundingBox(index)
                        val rtl = measured.getBidiRunDirection(index) == ResolvedTextDirection.Rtl
                        val rect = if (rtl) bounds.copy(left = bounds.right - bounds.width * amount)
                            else bounds.copy(right = bounds.left + bounds.width * amount)
                        reveal.addRect(rect)
                    }
                }
                clipPath(reveal) { drawText(measured, color = colors.onSurface, alpha = 1f) }
            }
        },
        onTextLayout = { layout = it },
        style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.SemiBold, color = colors.onSurface.copy(alpha = if (karaoke) 0.42f else 1f))
}

@Composable
private fun LyricsMessage(title: String, message: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(vertical = 32.dp), verticalArrangement = Arrangement.Center) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Icon(ImageVector.vectorResource(R.drawable.lyrics), null, Modifier.padding(16.dp).size(32.dp))
        }
        Text(title, Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(message, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) FilledTonalButton(retry, Modifier.padding(top = 20.dp)) { Text("Try again") }
    }
}
