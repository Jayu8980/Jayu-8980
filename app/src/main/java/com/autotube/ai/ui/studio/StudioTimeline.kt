package com.autotube.ai.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.autotube.ai.studio.AudioKind
import com.autotube.ai.studio.AudioLayer
import com.autotube.ai.studio.ClipTransition
import com.autotube.ai.studio.StudioClip
import com.autotube.ai.studio.StudioEdits
import com.autotube.ai.studio.StudioProject
import com.autotube.ai.studio.TextLayer
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.theme.AutoTubeColors
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val ClipHeight = 56.dp
private val LaneHeight = 30.dp
private val HandleWidth = 16.dp
private val ThumbWidth = 40.dp
private val RulerHeight = 22.dp

/** Callbacks from the timeline to the editor. */
class TimelineActions(
    val onScrub: (Long) -> Unit,
    val onSelect: (Selection) -> Unit,
    val onGestureStart: () -> Unit,
    val onGestureEnd: () -> Unit,
    val onTrimClip: (id: String, startMs: Long, endMs: Long) -> Unit,
    val onPlaceText: (id: String, startMs: Long, endMs: Long) -> Unit,
    val onPlaceAudio: (id: String, startMs: Long, trimStartMs: Long, durationMs: Long) -> Unit,
    val onTransition: (clipId: String) -> Unit,
    val onAddMedia: () -> Unit,
    val onAddText: () -> Unit,
    val onAddAudio: () -> Unit,
)

/**
 * CapCut-style timeline: a fixed playhead in the middle, tracks scroll underneath it. Scrolling scrubs,
 * pinch zooms, tap selects; the selected item gets trim handles (and can be dragged when it is a text
 * or audio layer).
 */
@Composable
fun StudioTimeline(
    project: StudioProject,
    playheadMs: Long,
    selection: Selection,
    actions: TimelineActions,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var pxPerMs by remember { mutableFloatStateOf(with(density) { 64.dp.toPx() } / 1000f) }
    val minPxPerMs = with(density) { 6.dp.toPx() } / 1000f
    val maxPxPerMs = with(density) { 600.dp.toPx() } / 1000f
    val scroll = rememberScrollState()
    var lastProgrammaticPx by remember { mutableIntStateOf(-1) }
    val onScrub by rememberUpdatedState(actions.onScrub)
    val scale by rememberUpdatedState(pxPerMs)

    val targetPx = (playheadMs * pxPerMs).roundToInt()
    LaunchedEffect(targetPx) {
        if (!scroll.isScrollInProgress && abs(scroll.value - targetPx) > 1) {
            lastProgrammaticPx = if (scroll.maxValue in 1 until targetPx) scroll.maxValue else targetPx
            scroll.scrollTo(targetPx)
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.collect { v ->
            if (scroll.isScrollInProgress && v != lastProgrammaticPx) onScrub((v / scale).toLong())
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(AutoTubeColors.SurfaceLow)
            .pointerInput(Unit) {
                // Two-finger pinch zooms the timeline; intercepted before the scroll sees it.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) pxPerMs = (pxPerMs * zoom).coerceIn(minPxPerMs, maxPxPerMs)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val viewportPx = constraints.maxWidth
        val halfPx = viewportPx / 2
        val totalPx = (project.durationMs * pxPerMs).roundToInt()
        val contentWidth = with(density) { (totalPx + halfPx * 2).toDp() }
        // Coarse visible window (content px) so thumbnails only load near the screen.
        val window by remember(viewportPx) {
            derivedStateOf {
                val bucket = scroll.value / viewportPx.coerceAtLeast(1)
                ((bucket - 1) * viewportPx)..((bucket + 3) * viewportPx)
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.horizontalScroll(scroll)) {
                Ruler(project.durationMs, pxPerMs, halfPx, contentWidth, scroll, viewportPx)
                VideoTrack(project, pxPerMs, halfPx, contentWidth, selection, actions, window)
                Spacer(Modifier.height(6.dp))
                TextTrack(project, pxPerMs, halfPx, contentWidth, selection, actions)
                Spacer(Modifier.height(4.dp))
                AudioTrack(project, pxPerMs, halfPx, contentWidth, selection, actions)
                Spacer(Modifier.height(8.dp))
            }
        }

        // Fixed playhead.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.White),
        )
        Text(
            Format.clock(playheadMs) + "." + ((playheadMs % 1000) / 100),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun Ruler(durationMs: Long, pxPerMs: Float, halfPx: Int, contentWidth: Dp, scroll: ScrollState, viewportPx: Int) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val minGapPx = with(density) { 64.dp.toPx() }
    val stepMs = RULER_STEPS.firstOrNull { it * pxPerMs >= minGapPx } ?: RULER_STEPS.last()
    Canvas(Modifier.width(contentWidth).height(RulerHeight)) {
        val from = ((scroll.value - halfPx) / pxPerMs).coerceAtLeast(0f)
        val to = ((scroll.value + viewportPx) / pxPerMs).coerceAtMost(durationMs.toFloat())
        var t = floor(from / stepMs).toLong() * stepMs
        while (t.toFloat() <= to) {
            val x = halfPx + t * pxPerMs
            drawLine(labelColor.copy(alpha = 0.5f), Offset(x, size.height * 0.55f), Offset(x, size.height), strokeWidth = 1.5f)
            val half = x + stepMs * pxPerMs / 2f
            if (t + stepMs / 2 <= durationMs) {
                drawLine(labelColor.copy(alpha = 0.3f), Offset(half, size.height * 0.8f), Offset(half, size.height), strokeWidth = 1f)
            }
            drawText(measurer, rulerLabel(t), Offset(x + 3f, 0f), TextStyle(color = labelColor, fontSize = 9.sp))
            t += stepMs
        }
    }
}

private val RULER_STEPS = longArrayOf(250, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000)

private fun rulerLabel(ms: Long): String = if (ms % 1000 == 0L) Format.clock(ms) else String.format(java.util.Locale.US, "%.1fs", ms / 1000f)

// ---------------------------------------------------------------------------------------- video

@Composable
private fun VideoTrack(
    project: StudioProject,
    pxPerMs: Float,
    halfPx: Int,
    contentWidth: Dp,
    selection: Selection,
    actions: TimelineActions,
    window: IntRange,
) {
    val density = LocalDensity.current
    val starts = project.clipStarts()
    Box(
        Modifier
            .width(contentWidth)
            .height(ClipHeight)
            .pointerInput(Unit) { detectTapGestures { actions.onSelect(Selection.None) } },
    ) {
        project.clips.forEachIndexed { i, clip ->
            val x = halfPx + (starts[i] * pxPerMs).roundToInt()
            val wPx = (clip.durationMs * pxPerMs).roundToInt().coerceAtLeast(2)
            val visible = x + wPx >= window.first && x <= window.last
            key(clip.id) {
                ClipBox(
                    clip = clip,
                    widthPx = wPx,
                    pxPerMs = pxPerMs,
                    selected = selection == Selection.Clip(clip.id),
                    loadThumbs = visible,
                    actions = actions,
                    modifier = Modifier.offset { IntOffset(x, 0) },
                )
            }
        }
        // Transition buttons between clips.
        val btn = 22.dp
        val btnPx = with(density) { btn.roundToPx() }
        for (i in 0 until project.clips.lastIndex) {
            val clip = project.clips[i]
            val x = halfPx + ((starts[i] + clip.durationMs) * pxPerMs).roundToInt() - btnPx / 2
            Box(
                Modifier
                    .offset { IntOffset(x, (with(density) { ClipHeight.roundToPx() } - btnPx) / 2) }
                    .size(btn)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (clip.transition == ClipTransition.NONE) Color.White else AutoTubeColors.Amber)
                    .clickable { actions.onTransition(clip.id) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Transition", tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
        // Add media at the end.
        val endX = halfPx + (project.durationMs * pxPerMs).roundToInt() + with(density) { 8.dp.roundToPx() }
        Box(
            Modifier
                .offset { IntOffset(endX, 0) }
                .size(ClipHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable { actions.onAddMedia() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Add media", tint = Color.Black)
        }
    }
}

@Composable
private fun ClipBox(
    clip: StudioClip,
    widthPx: Int,
    pxPerMs: Float,
    selected: Boolean,
    loadThumbs: Boolean,
    actions: TimelineActions,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val widthDp = with(density) { widthPx.toDp() }
    val thumbPx = with(density) { ThumbWidth.roundToPx() }
    Box(
        modifier
            .width(widthDp)
            .height(ClipHeight)
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AutoTubeColors.SurfaceHighest)
            .pointerInput(clip.id) { detectTapGestures { actions.onSelect(Selection.Clip(clip.id)) } },
    ) {
        if (loadThumbs) {
            val count = ceil(widthPx / thumbPx.toFloat()).toInt().coerceIn(1, 400)
            for (k in 0 until count) {
                val localMs = (k * thumbPx / pxPerMs).toLong()
                // Snap to a 500 ms grid so zooming reuses cached frames.
                val frameMs = (clip.sourceTimeAt(localMs) / 500L) * 500L
                val request = remember(clip.path, frameMs, clip.isImage) {
                    val b = ImageRequest.Builder(context).data(File(clip.path)).size(160).crossfade(false)
                    if (!clip.isImage) b.decoderFactory(VideoFrameDecoder.Factory()).videoFrameMillis(frameMs)
                    b.build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset(k * thumbPx, 0) }
                        .width(ThumbWidth)
                        .fillMaxHeight(),
                )
            }
        }
        // Badges: speed and mute.
        Row(Modifier.align(Alignment.BottomStart).padding(3.dp)) {
            if (!clip.isImage && clip.speed != 1f) Badge(String.format(java.util.Locale.US, "%.2gx", clip.speed))
            if (!clip.isImage && (clip.volume == 0f || !clip.hasAudio)) {
                Icon(Icons.Rounded.VolumeOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        if (widthPx > with(density) { 60.dp.toPx() }) {
            Badge(Format.clock(clip.durationMs) + "." + ((clip.durationMs % 1000) / 100), Modifier.align(Alignment.TopEnd).padding(3.dp))
        }
        if (selected) {
            Box(Modifier.fillMaxSize().border(2.dp, Color.White, RoundedCornerShape(6.dp)))
            ClipTrimHandle(clip, pxPerMs, left = true, actions, Modifier.align(Alignment.CenterStart))
            ClipTrimHandle(clip, pxPerMs, left = false, actions, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun ClipTrimHandle(clip: StudioClip, pxPerMs: Float, left: Boolean, actions: TimelineActions, modifier: Modifier) {
    val current by rememberUpdatedState(clip)
    val scale by rememberUpdatedState(pxPerMs)
    Box(
        modifier
            .width(HandleWidth)
            .fillMaxHeight()
            .background(Color.White)
            .pointerInput(clip.id, left) {
                var origin: StudioClip? = null
                var accum = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        origin = current
                        accum = 0f
                        actions.onGestureStart()
                    },
                    onDragEnd = { actions.onGestureEnd() },
                    onDragCancel = { actions.onGestureEnd() },
                ) { change, dx ->
                    change.consume()
                    val o = origin ?: return@detectHorizontalDragGestures
                    accum += dx
                    val deltaMs = (accum / scale).toLong()
                    if (o.isImage) {
                        val newLen = if (left) o.trimmedMs - deltaMs else o.trimmedMs + deltaMs
                        actions.onTrimClip(o.id, 0L, newLen.coerceAtLeast(StudioEdits.MIN_MS))
                    } else {
                        val srcDelta = (deltaMs * o.speed).toLong()
                        if (left) actions.onTrimClip(o.id, o.trimStartMs + srcDelta, o.trimEndMs)
                        else actions.onTrimClip(o.id, o.trimStartMs, o.trimEndMs + srcDelta)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(18.dp).background(Color.Black.copy(alpha = 0.6f)))
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 3.dp),
    )
}

// ---------------------------------------------------------------------------------------- text

@Composable
private fun TextTrack(
    project: StudioProject,
    pxPerMs: Float,
    halfPx: Int,
    contentWidth: Dp,
    selection: Selection,
    actions: TimelineActions,
) {
    val density = LocalDensity.current
    val lanes = remember(project.texts) { StudioEdits.textLanes(project.texts) }
    val laneCount = (lanes.values.maxOrNull() ?: -1) + 1
    val laneHeightPx = with(density) { LaneHeight.roundToPx() }
    Box(Modifier.width(contentWidth).height(LaneHeight * laneCount.coerceAtLeast(1))) {
        if (project.texts.isEmpty()) {
            AddRow("Add text", Icons.Rounded.TextFields, halfPx, actions.onAddText)
        }
        project.texts.forEach { t ->
            val x = halfPx + (t.startMs * pxPerMs).roundToInt()
            val wPx = (t.durationMs * pxPerMs).roundToInt().coerceAtLeast(8)
            key(t.id) {
                LayerBar(
                    label = t.text.replace('\n', ' '),
                    color = if (t.sticker) AutoTubeColors.Purple else AutoTubeColors.Amber,
                    icon = Icons.Rounded.TextFields,
                    widthPx = wPx,
                    selected = selection == Selection.Text(t.id),
                    modifier = Modifier.offset { IntOffset(x, (lanes[t.id] ?: 0) * laneHeightPx) },
                    onTap = { actions.onSelect(Selection.Text(t.id)) },
                    drag = TextDrag(t, pxPerMs, actions),
                )
            }
        }
    }
}

/** Drag rules for a text bar: body moves, handles change start/end. */
private class TextDrag(val layer: TextLayer, val pxPerMs: Float, val actions: TimelineActions) : BarDrag {
    private var origin: TextLayer? = null
    override val key: Any get() = layer.id
    override fun start() {
        origin = layer
        actions.onGestureStart()
    }
    override fun end() = actions.onGestureEnd()
    override fun move(part: BarPart, totalDx: Float) {
        val o = origin ?: return
        val d = (totalDx / pxPerMs).toLong()
        when (part) {
            BarPart.BODY -> {
                val start = (o.startMs + d).coerceAtLeast(0L)
                actions.onPlaceText(o.id, start, start + o.durationMs)
            }
            BarPart.LEFT -> actions.onPlaceText(o.id, (o.startMs + d).coerceIn(0L, o.endMs - StudioEdits.MIN_MS), o.endMs)
            BarPart.RIGHT -> actions.onPlaceText(o.id, o.startMs, (o.endMs + d).coerceAtLeast(o.startMs + StudioEdits.MIN_MS))
        }
    }
}

// ---------------------------------------------------------------------------------------- audio

@Composable
private fun AudioTrack(
    project: StudioProject,
    pxPerMs: Float,
    halfPx: Int,
    contentWidth: Dp,
    selection: Selection,
    actions: TimelineActions,
) {
    val density = LocalDensity.current
    val lanes = remember(project.audio) { StudioEdits.audioLanes(project.audio) }
    val laneCount = (lanes.values.maxOrNull() ?: -1) + 1
    val laneHeightPx = with(density) { LaneHeight.roundToPx() }
    Box(Modifier.width(contentWidth).height(LaneHeight * (laneCount + 1))) {
        AddRow(
            if (project.audio.isEmpty()) "Add music or sound" else "Add audio",
            Icons.Rounded.MusicNote,
            halfPx,
            actions.onAddAudio,
            Modifier.offset { IntOffset(0, laneCount * laneHeightPx) },
        )
        project.audio.forEach { a ->
            val x = halfPx + (a.startMs * pxPerMs).roundToInt()
            val wPx = (a.durationMs * pxPerMs).roundToInt().coerceAtLeast(8)
            key(a.id) {
                LayerBar(
                    label = a.name,
                    color = when (a.kind) {
                        AudioKind.MUSIC -> AutoTubeColors.Teal
                        AudioKind.VOICEOVER -> AutoTubeColors.Orange
                        AudioKind.EXTRACTED -> AutoTubeColors.Blue
                        AudioKind.SFX -> AutoTubeColors.Green
                    },
                    icon = when (a.kind) {
                        AudioKind.VOICEOVER -> Icons.Rounded.Mic
                        AudioKind.MUSIC -> Icons.Rounded.MusicNote
                        else -> Icons.Rounded.GraphicEq
                    },
                    widthPx = wPx,
                    selected = selection == Selection.Audio(a.id),
                    modifier = Modifier.offset { IntOffset(x, (lanes[a.id] ?: 0) * laneHeightPx) },
                    onTap = { actions.onSelect(Selection.Audio(a.id)) },
                    drag = AudioDrag(a, pxPerMs, actions),
                )
            }
        }
    }
}

/** Drag rules for an audio bar: body moves on the timeline, handles trim the file window. */
private class AudioDrag(val layer: AudioLayer, val pxPerMs: Float, val actions: TimelineActions) : BarDrag {
    private var origin: AudioLayer? = null
    override val key: Any get() = layer.id
    override fun start() {
        origin = layer
        actions.onGestureStart()
    }
    override fun end() = actions.onGestureEnd()
    override fun move(part: BarPart, totalDx: Float) {
        val o = origin ?: return
        val d = (totalDx / pxPerMs).toLong()
        when (part) {
            BarPart.BODY -> actions.onPlaceAudio(o.id, (o.startMs + d).coerceAtLeast(0L), o.trimStartMs, o.durationMs)
            BarPart.LEFT -> {
                // Keep the right edge fixed: start, file offset and length change together.
                val dd = d.coerceIn(maxOf(-o.trimStartMs, -o.startMs), o.durationMs - StudioEdits.MIN_MS)
                actions.onPlaceAudio(o.id, o.startMs + dd, o.trimStartMs + dd, o.durationMs - dd)
            }
            BarPart.RIGHT -> actions.onPlaceAudio(
                o.id,
                o.startMs,
                o.trimStartMs,
                (o.durationMs + d).coerceIn(StudioEdits.MIN_MS, o.sourceDurationMs - o.trimStartMs),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------- shared bars

private enum class BarPart { LEFT, BODY, RIGHT }

private interface BarDrag {
    val key: Any
    fun start()
    fun end()
    fun move(part: BarPart, totalDx: Float)
}

@Composable
private fun LayerBar(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    widthPx: Int,
    selected: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    drag: BarDrag,
) {
    val density = LocalDensity.current
    val currentDrag by rememberUpdatedState(drag)
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier
            .width(with(density) { widthPx.toDp() })
            .height(LaneHeight)
            .padding(vertical = 2.dp, horizontal = 1.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = if (selected) 0.95f else 0.7f))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(5.dp)) else Modifier)
            .pointerInput(Unit) { detectTapGestures { tap() } }
            .then(if (selected) Modifier.barDrag(BarPart.BODY) { currentDrag } else Modifier),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = if (selected) HandleWidth else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Black, maxLines = 1, overflow = TextOverflow.Clip)
        }
        if (selected) {
            BarHandle(Modifier.align(Alignment.CenterStart).barDrag(BarPart.LEFT) { currentDrag })
            BarHandle(Modifier.align(Alignment.CenterEnd).barDrag(BarPart.RIGHT) { currentDrag })
        }
    }
}

@Composable
private fun BarHandle(modifier: Modifier) {
    Box(modifier.width(HandleWidth).fillMaxHeight().background(Color.White), contentAlignment = Alignment.Center) {
        Box(Modifier.width(2.dp).height(12.dp).background(Color.Black.copy(alpha = 0.6f)))
    }
}

private fun Modifier.barDrag(part: BarPart, drag: () -> BarDrag): Modifier = pointerInput(part) {
    var accum = 0f
    var active: BarDrag? = null
    detectHorizontalDragGestures(
        onDragStart = {
            accum = 0f
            active = drag().also { it.start() }
        },
        onDragEnd = { active?.end(); active = null },
        onDragCancel = { active?.end(); active = null },
    ) { change, dx ->
        change.consume()
        accum += dx
        active?.move(part, accum)
    }
}

@Composable
private fun AddRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    halfPx: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .offset { IntOffset(halfPx, 0) }
            .height(LaneHeight)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(AutoTubeColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
