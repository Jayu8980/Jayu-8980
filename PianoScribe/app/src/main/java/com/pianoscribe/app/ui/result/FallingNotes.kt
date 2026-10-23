package com.pianoscribe.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note

/**
 * Synthesia-style view: notes fall towards an on-screen keyboard and keys light up as they sound.
 * Drag vertically to scrub.
 */
@Composable
fun FallingNotes(
    notes: List<Note>,
    position: Double,
    duration: Double,
    lo: Int,
    hi: Int,
    fingers: Map<Long, Int>,
    modifier: Modifier = Modifier,
    lookahead: Double = 3.0,
    onSeek: (Double) -> Unit = {},
) {
    val colors = LocalMusicColors.current
    val pos = rememberUpdatedState(position)
    Canvas(modifier.pointerInput(duration, lookahead) {
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
        // Octave guide lines.
        for (m in layout.lo..layout.hi) if (m % 12 == 0) {
            val x = layout.keyX(m).first
            drawLine(colors.gridLine, Offset(x, 0f), Offset(x, fallH), 1f)
        }
        val lit = HashMap<Int, Color>()
        val labels = HashMap<Int, String>()
        val corner = CornerRadius(6f, 6f)
        for (n in notes) {
            if (n.start > position + lookahead || n.end < position - 0.05) continue
            if (!layout.contains(n.pitch)) continue
            val c = if (n.hand == Hand.RIGHT) colors.rightHand else colors.leftHand
            val (kx, kw) = layout.keyX(n.pitch)
            val yBottom = fallH - ((n.start - position) * pxPerSec).toFloat()
            val yTop = fallH - ((n.end - position) * pxPerSec).toFloat()
            val top = yTop.coerceAtLeast(0f)
            val bottom = yBottom.coerceAtMost(fallH)
            if (bottom > top) drawRoundRect(c, Offset(kx + 1, top), Size(kw - 2, bottom - top), corner)
            if (n.start <= position && n.end > position) {
                lit[n.pitch] = lerp(c, Color.White, 0.15f)
                fingers[n.id]?.let { labels[n.pitch] = it.toString() }
            }
        }
        drawLine(colors.playhead, Offset(0f, fallH), Offset(size.width, fallH), 3f)
        drawKeyboard(layout, fallH, kbH, lit, labels)
    }
}
