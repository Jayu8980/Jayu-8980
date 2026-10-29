package com.pianoscribe.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.analysis.ChordTrack
import com.pianoscribe.core.midi.ScoreBuilder
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.theory.Pitch
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Synthesia-style view: notes fall towards an on-screen keyboard and keys light up, named, as
 * they sound. Shows the notes as played or as written in the score, bar lines, chord names and
 * finger numbers. Drag vertically to scrub.
 */
@Composable
fun FallingNotes(
    notes: List<Note>,
    grid: TimeGrid,
    preferFlats: Boolean,
    position: Double,
    duration: Double,
    lo: Int,
    hi: Int,
    fingers: Map<Long, Int>,
    modifier: Modifier = Modifier,
    onSeek: (Double) -> Unit = {},
) {
    val colors = LocalMusicColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    var written by rememberSaveable { mutableStateOf(true) }
    var lookahead by rememberSaveable { mutableDoubleStateOf(3.0) }
    val writtenNotes = remember(notes, grid) { ScoreBuilder.writtenNotes(notes, grid) }
    val shown = if (written) writtenNotes else notes
    val chords = remember(shown, grid, preferFlats) { ChordTrack.labels(shown, grid, preferFlats) }
    val pos = rememberUpdatedState(position)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(!written, { written = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("As played") }
                SegmentedButton(written, { written = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("As written") }
            }
            Spacer(Modifier.weight(1f))
            // Fall speed: fewer seconds on screen spreads the notes out.
            FilledTonalIconButton(onClick = { lookahead = (lookahead * 1.25).coerceAtMost(8.0) }) { Text("−") }
            FilledTonalIconButton(onClick = { lookahead = (lookahead / 1.25).coerceAtLeast(1.0) }) { Text("+") }
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().pointerInput(duration, lookahead) {
            var acc = 0.0
            detectDragGestures(onDragStart = { acc = pos.value }) { change, drag ->
                change.consume()
                acc = (acc + drag.y / (size.height * 0.78f) * lookahead).coerceIn(0.0, duration)
                onSeek(acc)
            }
        }) {
            val kbH = (size.height * 0.22f).coerceIn(60f, 260f)
            val fallH = size.height - kbH
            val layout = KeyLayout(maxOf(21, lo - 2), minOf(108, hi + 2), size.width)
            val pxPerSec = fallH / lookahead
            fun yOf(t: Double) = fallH - ((t - position) * pxPerSec).toFloat()
            val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb(); textSize = 13.dp.toPx() }
            val small = android.graphics.Paint(text).apply { textSize = 10.dp.toPx(); color = Color.White.toArgb(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
            // Octave guide lines.
            for (m in layout.lo..layout.hi) if (m % 12 == 0) {
                val x = layout.keyX(m).first
                drawLine(colors.gridLine, Offset(x, 0f), Offset(x, fallH), 1f)
            }
            // Bar lines, with the bar number.
            val barLen = grid.beatsPerBar.toDouble()
            val b0 = floor(grid.timeToBeats(position) / barLen).toInt()
            val b1 = ceil(grid.timeToBeats(position + lookahead) / barLen).toInt()
            for (b in b0..b1) {
                val y = yOf(grid.beatsToTime(b * barLen))
                if (y < 0 || y > fallH) continue
                drawLine(colors.barLine, Offset(0f, y), Offset(size.width, y), 1.5f)
                drawIntoCanvas { it.nativeCanvas.drawText("${b + 1}", size.width - 28.dp.toPx(), y - 3.dp.toPx(), text.apply { alpha = 120 }) }
            }
            text.alpha = 255
            val lit = HashMap<Int, Color>()
            val labels = HashMap<Int, String>()
            val corner = CornerRadius(6f, 6f)
            for (n in shown) {
                if (n.start > position + lookahead || n.end < position - 0.05) continue
                if (!layout.contains(n.pitch)) continue
                val c = if (n.hand == Hand.RIGHT) colors.rightHand else colors.leftHand
                val (kx, kw) = layout.keyX(n.pitch)
                val top = yOf(n.end).coerceAtLeast(0f)
                val bottom = yOf(n.start).coerceAtMost(fallH)
                if (bottom > top) {
                    drawRoundRect(c, Offset(kx + 1, top), Size(kw - 2, bottom - top), corner)
                    // Finger number at the bottom of the falling note, when there is room.
                    val f = fingers[n.id]
                    if (f != null && bottom - top > 16.dp.toPx() && kw > 9.dp.toPx()) drawIntoCanvas { it.nativeCanvas.drawText("$f", kx + kw / 2, bottom - 4.dp.toPx(), small) }
                }
                if (n.start <= position && n.end > position) {
                    lit[n.pitch] = lerp(c, Color.White, 0.15f)
                    labels[n.pitch] = Pitch.pitchClassName(Pitch.pitchClass(n.pitch), preferFlats)
                }
            }
            // Chord names where each chord begins.
            for (ch in chords) {
                val y = yOf(ch.time)
                if (y < 14.dp.toPx() || y > fallH) continue
                val w = text.measureText(ch.name) + 10.dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(4.dp.toPx(), y - 18.dp.toPx()), Size(w, 18.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawIntoCanvas { it.nativeCanvas.drawText(ch.name, 9.dp.toPx(), y - 5.dp.toPx(), text.apply { color = Color.White.toArgb() }) }
                text.color = ink.toArgb()
            }
            drawLine(colors.playhead, Offset(0f, fallH), Offset(size.width, fallH), 3f)
            drawKeyboard(layout, fallH, kbH, lit, labels)
        }
    }
}
