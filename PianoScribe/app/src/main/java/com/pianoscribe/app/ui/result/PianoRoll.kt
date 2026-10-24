package com.pianoscribe.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.theory.Pitch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Scroll/zoom state of a piano roll. */
@Stable
class RollViewport(start: Double = 0.0, pxPerSec: Float = 140f, topPitch: Float = 88f, rowPx: Float = 26f) {
    var start by mutableDoubleStateOf(start)
    var pxPerSec by mutableFloatStateOf(pxPerSec)
    var topPitch by mutableFloatStateOf(topPitch)
    var rowPx by mutableFloatStateOf(rowPx)
    /** Follow the playhead while playing; any manual pan turns it off until play is pressed again. */
    var follow by mutableStateOf(true)
}

private enum class DragMode { NONE, PAN, MOVE, RESIZE, PINCH }

/**
 * Scrollable, zoomable piano roll with the keyboard on the left, notes coloured by hand and a
 * playhead. With [editable] on: drag a note to move it, drag its right edge to resize,
 * long-press to delete and double-tap empty space to add a note.
 */
@Composable
fun PianoRoll(
    notes: List<Note>,
    grid: TimeGrid,
    duration: Double,
    position: Double,
    playing: Boolean,
    selectedId: Long?,
    editable: Boolean,
    viewport: RollViewport,
    modifier: Modifier = Modifier,
    key: KeyDetector.Key? = null,
    highlightIds: Set<Long> = emptySet(),
    onSelect: (Long?) -> Unit = {},
    onSeek: (Double) -> Unit = {},
    onMove: (Long, Double, Int) -> Unit = { _, _, _ -> },
    onResize: (Long, Double) -> Unit = { _, _ -> },
    onDelete: (Long) -> Unit = {},
    onAdd: (Int, Double) -> Unit = { _, _ -> },
    onKey: (Int) -> Unit = {},
) {
    val colors = LocalMusicColors.current
    val density = LocalDensity.current
    val keyW = with(density) { 44.dp.toPx() }
    val rulerH = with(density) { 22.dp.toPx() }
    val edgePx = with(density) { 14.dp.toPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis

    val notesState = rememberUpdatedState(notes)
    val editState = rememberUpdatedState(editable)
    val cb = rememberUpdatedState(listOf(onSelect, onSeek, onMove, onResize, onDelete, onAdd, onKey))

    // Live drag preview.
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragDt by remember { mutableDoubleStateOf(0.0) }
    var dragDp by remember { mutableFloatStateOf(0f) }
    var dragDur by remember { mutableDoubleStateOf(0.0) }
    var dragResize by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var lastTap by remember { mutableStateOf(0L to Offset.Zero) }

    LaunchedEffect(playing) { if (playing) viewport.follow = true }
    LaunchedEffect(position, playing) {
        if (playing && viewport.follow && widthPx > keyW) {
            viewport.start = position - (widthPx - keyW) / viewport.pxPerSec * 0.3
        }
    }

    Canvas(
        modifier.onSizeChanged { widthPx = it.width.toFloat() }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downTime = System.currentTimeMillis()
                val pos0 = down.position
                @Suppress("UNCHECKED_CAST") val callbacks = cb.value
                val select = callbacks[0] as (Long?) -> Unit
                val seek = callbacks[1] as (Double) -> Unit
                val move = callbacks[2] as (Long, Double, Int) -> Unit
                val resize = callbacks[3] as (Long, Double) -> Unit
                val delete = callbacks[4] as (Long) -> Unit
                val add = callbacks[5] as (Int, Double) -> Unit
                val keyTap = callbacks[6] as (Int) -> Unit

                fun timeAt(x: Float) = viewport.start + (x - keyW) / viewport.pxPerSec
                fun pitchAt(y: Float) = floor(viewport.topPitch - (y - rulerH) / viewport.rowPx).toInt() + 1
                fun hit(p: Offset): Pair<Note, Boolean>? {
                    if (p.x < keyW || p.y < rulerH) return null
                    val t = timeAt(p.x)
                    val pitch = pitchAt(p.y)
                    val n = notesState.value.lastOrNull { it.pitch == pitch && t >= it.start - 0.02 && t <= it.end + 0.02 } ?: return null
                    val endX = keyW + ((n.end - viewport.start) * viewport.pxPerSec).toFloat()
                    return n to (abs(p.x - endX) < edgePx && n.duration * viewport.pxPerSec > edgePx * 1.5)
                }

                val hitNote = hit(pos0)
                var mode = DragMode.NONE
                var last = pos0
                var pinchDist = Offset.Zero
                var finished = false
                var longPressDone = false
                while (!finished) {
                    val elapsed = System.currentTimeMillis() - downTime
                    val event = if (mode == DragMode.NONE && !longPressDone) withTimeoutOrNull((longPressMs - elapsed).coerceAtLeast(1)) { awaitPointerEvent(PointerEventPass.Main) }
                    else awaitPointerEvent(PointerEventPass.Main)
                    if (event == null) {
                        // Long press: delete (edit mode) or select a note, then swallow the gesture.
                        longPressDone = true
                        if (hitNote == null) continue
                        if (editState.value) delete(hitNote.first.id) else select(hitNote.first.id)
                        do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val d = pressed[1].position - pressed[0].position
                        if (mode != DragMode.PINCH) { mode = DragMode.PINCH; pinchDist = d; dragId = null }
                        else {
                            val cx = (pressed[0].position.x + pressed[1].position.x) / 2
                            val tAtCenter = timeAt(cx)
                            if (abs(pinchDist.x) > 30f && abs(d.x) > 30f) {
                                viewport.pxPerSec = (viewport.pxPerSec * abs(d.x) / abs(pinchDist.x)).coerceIn(15f, 1500f)
                                viewport.start = (tAtCenter - (cx - keyW) / viewport.pxPerSec).coerceIn(-1.0, duration)
                            }
                            if (abs(pinchDist.y) > 30f && abs(d.y) > 30f) {
                                viewport.rowPx = (viewport.rowPx * abs(d.y) / abs(pinchDist.y)).coerceIn(6f, 60f)
                            }
                            pinchDist = d
                            viewport.follow = false
                        }
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    if (pressed.isEmpty()) {
                        val up = event.changes.first().position
                        when (mode) {
                            DragMode.NONE -> if (!longPressDone) {
                                val now = System.currentTimeMillis()
                                val isDouble = now - lastTap.first < 320 && (up - lastTap.second).getDistance() < slop * 3
                                lastTap = now to up
                                when {
                                    up.x < keyW && up.y > rulerH -> keyTap(pitchAt(up.y))
                                    up.y < rulerH -> seek(timeAt(up.x).coerceIn(0.0, duration))
                                    isDouble && editState.value && hitNote == null -> add(pitchAt(up.y), timeAt(up.x))
                                    else -> select(hitNote?.first?.id)
                                }
                            }
                            DragMode.MOVE -> {
                                val id = dragId
                                if (id != null) move(id, dragDt, dragDp.roundToInt())
                            }
                            DragMode.RESIZE -> dragId?.let { resize(it, dragDur) }
                            else -> {}
                        }
                        dragId = null; dragDt = 0.0; dragDp = 0f
                        finished = true
                        continue
                    }
                    val c = pressed.first()
                    val p = c.position
                    if (mode == DragMode.NONE && (p - pos0).getDistance() > slop) {
                        mode = when {
                            hitNote != null && editState.value && hitNote.second -> DragMode.RESIZE
                            hitNote != null && editState.value -> DragMode.MOVE
                            else -> DragMode.PAN
                        }
                        if (mode == DragMode.MOVE || mode == DragMode.RESIZE) {
                            dragResize = mode == DragMode.RESIZE
                            dragId = hitNote!!.first.id
                            dragDur = hitNote.first.duration
                            select(hitNote.first.id)
                        }
                    }
                    when (mode) {
                        DragMode.PAN -> {
                            val delta = p - last
                            viewport.start = (viewport.start - delta.x / viewport.pxPerSec).coerceIn(-1.0, maxOf(0.0, duration))
                            viewport.topPitch = (viewport.topPitch + delta.y / viewport.rowPx).coerceIn(30f, 109f)
                            viewport.follow = false
                        }
                        DragMode.MOVE -> {
                            dragDt = ((p.x - pos0.x) / viewport.pxPerSec).toDouble()
                            dragDp = -(p.y - pos0.y) / viewport.rowPx
                        }
                        DragMode.RESIZE -> {
                            val n = hitNote!!.first
                            dragDur = (n.duration + (p.x - pos0.x) / viewport.pxPerSec).coerceAtLeast(0.03)
                        }
                        else -> {}
                    }
                    last = p
                    if (mode != DragMode.NONE) c.consume()
                }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        val vis = (w - keyW) / viewport.pxPerSec
        val t0 = viewport.start
        val t1 = t0 + vis
        fun x(t: Double) = keyW + ((t - t0) * viewport.pxPerSec).toFloat()
        fun y(pitch: Int) = rulerH + (viewport.topPitch - pitch) * viewport.rowPx
        val row = viewport.rowPx
        val lowVisible = floor(viewport.topPitch - (h - rulerH) / row).toInt()
        val highVisible = viewport.topPitch.toInt() + 1

        // Rows.
        for (pitch in maxOf(21, lowVisible)..minOf(108, highVisible)) {
            drawRect(if (Pitch.isBlackKey(pitch)) colors.blackKeyRow else colors.whiteKeyRow, Offset(keyW, y(pitch)), Size(w - keyW, row))
            if (Pitch.pitchClass(pitch) == 0) drawLine(colors.barLine, Offset(keyW, y(pitch) + row), Offset(w, y(pitch) + row), 1f)
        }
        // Beat and bar lines with bar numbers.
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = rulerH * 0.55f; color = colors.barLine.copy(alpha = 1f).toArgb() }
        val spb = grid.secondsPerBeat
        if (spb * viewport.pxPerSec > 6) {
            var b = floor((t0 - grid.offset) / spb).toInt()
            while (true) {
                val t = grid.offset + b * spb
                if (t > t1) break
                if (t >= -0.001) {
                    val isBar = Math.floorMod(b, grid.beatsPerBar) == 0
                    drawLine(if (isBar) colors.barLine else colors.gridLine, Offset(x(t), rulerH), Offset(x(t), h), if (isBar) 1.5f else 1f)
                    if (isBar) drawContext.canvas.nativeCanvas.drawText("${Math.floorDiv(b, grid.beatsPerBar) + 1}", x(t) + 3, rulerH * 0.72f, paint)
                }
                b++
            }
        }
        // Time ruler baseline.
        drawLine(colors.barLine, Offset(keyW, rulerH), Offset(w, rulerH), 1f)

        // Notes.
        val corner = CornerRadius(minOf(6f, row / 3), minOf(6f, row / 3))
        for (n in notes) {
            val previewing = n.id == dragId
            val start = if (previewing && !dragResize) n.start + dragDt else n.start
            val dur = if (previewing && dragResize) dragDur else n.duration
            val pitch = if (previewing && !dragResize) (n.pitch + dragDp.roundToInt()).coerceIn(21, 108) else n.pitch
            if (start > t1 || start + dur < t0) continue
            if (pitch < lowVisible - 1 || pitch > highVisible + 1) continue
            val base = if (n.hand == Hand.RIGHT) colors.rightHand else colors.leftHand
            val alpha = (0.45f + 0.55f * n.confidence).coerceIn(0.35f, 1f)
            val x0 = x(start)
            val x1 = maxOf(x0 + 3f, x(start + dur))
            val top = y(pitch) + 1
            drawRoundRect(base.copy(alpha = alpha), Offset(x0, top), Size(x1 - x0, row - 2), corner)
            when {
                n.id == selectedId || previewing -> drawRoundRect(Color.White, Offset(x0, top), Size(x1 - x0, row - 2), corner, style = Stroke(3f)).also {
                    drawRoundRect(Color.Black, Offset(x0 - 1.5f, top - 1.5f), Size(x1 - x0 + 3f, row + 1f), corner, style = Stroke(1.5f))
                }
                n.id in highlightIds -> drawRoundRect(colors.playhead, Offset(x0, top), Size(x1 - x0, row - 2), corner, style = Stroke(2.5f))
                n.confidence < 0.5f && !n.verified -> drawRoundRect(colors.warning, Offset(x0, top), Size(x1 - x0, row - 2), corner, style = Stroke(2f))
            }
            if (key != null && !key.contains(pitch)) drawCircle(colors.outOfKey, minOf(4f, row / 4), Offset(x0 + 5f, top + row / 2 - 1))
        }

        // Playhead.
        if (position in t0..t1) {
            drawLine(colors.playhead, Offset(x(position), 0f), Offset(x(position), h), 2.5f)
        }

        // Keyboard column.
        drawRect(Color(0xFF888888), Offset(0f, rulerH), Size(keyW, h - rulerH))
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = minOf(row * 0.7f, keyW * 0.3f) }
        for (pitch in maxOf(21, lowVisible)..minOf(108, highVisible)) {
            val black = Pitch.isBlackKey(pitch)
            val yy = y(pitch)
            drawRect(if (black) Color(0xFF26262B) else Color.White, Offset(0f, yy + 0.5f), Size(if (black) keyW * 0.62f else keyW, row - 1f))
            if (Pitch.pitchClass(pitch) == 0 || row > 18) {
                label.color = if (black) android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY
                if (!black || row > 18) drawContext.canvas.nativeCanvas.drawText(Pitch.name(pitch), if (black) keyW * 0.66f else keyW * 0.36f, yy + row * 0.72f, label)
            }
        }
        // Ruler background over the corner.
        drawRect(colors.whiteKeyRow, Offset(0f, 0f), Size(keyW, rulerH))
    }
}
