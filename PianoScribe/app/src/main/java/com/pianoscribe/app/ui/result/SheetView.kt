package com.pianoscribe.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pianoscribe.app.ui.score.ScorePainter
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.score.EngravedScore
import com.pianoscribe.core.score.EngravedSystem
import com.pianoscribe.core.score.Engraver
import com.pianoscribe.core.theory.KeyDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Left margin (room for the brace) and top margin, in staff spaces. */
private const val MARGIN = 3f

/**
 * The piano score: engraved to fit the screen (core/score, Bravura glyphs), with the playhead,
 * the selected chord highlighted, zoom, and a small bar to fix the selected note. Tap a note to
 * select it; tap elsewhere in a system to play from there.
 */
@Composable
fun SheetView(
    notes: List<Note>,
    grid: TimeGrid,
    key: KeyDetector.Key?,
    title: String?,
    position: Double,
    playing: Boolean,
    selectedId: Long?,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    onSeek: (Double) -> Unit = {},
    onSelect: (Long?) -> Unit = {},
    onPitch: (Int) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = LocalMusicColors.current
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val selection = MaterialTheme.colorScheme.primary
    val painter = remember(ink) { ScorePainter(ScorePainter.music(context), ink) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val scroll = rememberScrollState()

    BoxWithConstraints(modifier) {
        val space = with(density) { (7.5.dp * zoom).toPx() } // one staff space in pixels
        val widthSpaces = with(density) { maxWidth.toPx() } / space - MARGIN - 1.5f
        var score by remember { mutableStateOf<EngravedScore?>(null) }
        // Engrave off the main thread whenever the notes or the available width change.
        LaunchedEffect(notes, grid, key, title, Math.round(widthSpaces)) {
            score = withContext(Dispatchers.Default) { Engraver.engrave(notes, grid, key, widthSpaces, Engraver.Options(title = title)) }
        }
        val sc = score
        if (sc == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@BoxWithConstraints
        }
        val heightDp = with(density) { ((sc.height + MARGIN * 2) * space).toDp() }
        val current = sc.systems.indexOfFirst { position >= it.startTime && position < it.endTime }
        // Keep the playing system in view.
        LaunchedEffect(current, playing) {
            if (!playing || current < 0) return@LaunchedEffect
            val s = sc.systems[current]
            val top = ((s.top + MARGIN) * space).toInt()
            val bottom = ((s.bottom + MARGIN) * space).toInt()
            if (top < scroll.value || bottom > scroll.value + scroll.viewportSize) scroll.animateScrollTo(maxOf(0, top - (space * 2).toInt()))
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                Canvas(
                    Modifier.fillMaxWidth().height(heightDp).pointerInput(sc, space) {
                        detectTapGestures { o ->
                            val x = o.x / space - MARGIN
                            val y = o.y / space - MARGIN
                            val hit = sc.systems.flatMap { it.hits }.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
                            if (hit != null && hit.noteIds.isNotEmpty()) { onSelect(hit.noteIds.last()); onSeek(hit.time); return@detectTapGestures }
                            val sys = sc.systems.firstOrNull { y >= it.top && y <= it.bottom } ?: return@detectTapGestures
                            onSelect(null)
                            onSeek(timeAt(sys, x))
                        }
                    },
                ) {
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        nc.save()
                        nc.translate(MARGIN * space, MARGIN * space)
                        painter.draw(nc, sc.header, space)
                        for (s in sc.systems) painter.draw(nc, s.marks, space)
                        nc.restore()
                    }
                    fun px(v: Float) = (v + MARGIN) * space
                    // The selected chord.
                    if (selectedId != null) for (s in sc.systems) for (h in s.hits) if (selectedId in h.noteIds) {
                        drawRoundRect(selection.copy(alpha = 0.22f), Offset(px(h.x), px(h.y)), Size(h.w * space, h.h * space), CornerRadius(space * 0.4f))
                    }
                    // The playhead.
                    if (current >= 0) {
                        val s = sc.systems[current]
                        val x = px(xAt(s, position))
                        drawLine(colors.playhead, Offset(x, px(s.top)), Offset(x, px(s.bottom)), strokeWidth = space * 0.25f)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selectedId != null && editable) {
                    OutlinedButton(onClick = { onPitch(-1) }) { Text("♭ −1") }
                    OutlinedButton(onClick = { onPitch(1) }) { Text("♯ +1") }
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                } else {
                    Text("Tap a note to select it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                if (selectedId != null && editable) Spacer(Modifier.weight(1f))
                Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
                    Row {
                        FilledTonalIconButton(onClick = { zoom = (zoom / 1.2f).coerceAtLeast(0.6f) }) { Text("−") }
                        FilledTonalIconButton(onClick = { zoom = (zoom * 1.2f).coerceAtMost(2.2f) }) { Text("+") }
                    }
                }
            }
        }
    }
}

/** The x of a song time on a system, between its note columns. */
private fun xAt(s: EngravedSystem, t: Double): Float {
    val c = s.columns
    if (c.isEmpty()) return 0f
    if (t <= c.first().second) return c.first().first
    for (i in 0 until c.size - 1) {
        val (x0, t0) = c[i]; val (x1, t1) = c[i + 1]
        if (t < t1) return (x0 + (x1 - x0) * ((t - t0) / (t1 - t0))).toFloat()
    }
    val (xl, tl) = c.last()
    val end = s.marks.filterIsInstance<com.pianoscribe.core.score.Mark.Line>().maxOf { it.x2 }
    return (xl + (end - xl) * ((t - tl) / (s.endTime - tl)).coerceIn(0.0, 1.0)).toFloat()
}

/** The song time at x on a system. */
private fun timeAt(s: EngravedSystem, x: Float): Double {
    val c = s.columns
    if (c.isEmpty()) return s.startTime
    if (x <= c.first().first) return c.first().second
    for (i in 0 until c.size - 1) {
        val (x0, t0) = c[i]; val (x1, t1) = c[i + 1]
        if (x < x1) return t0 + (t1 - t0) * ((x - x0) / (x1 - x0))
    }
    return c.last().second
}
