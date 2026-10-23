package com.pianoscribe.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.midi.ScoreBuilder
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid

/**
 * Basic grand-staff rendering (treble + bass) from the same measure layout used for MusicXML:
 * noteheads, stems, flags, dots, accidentals, ledger lines, rests and ties. Tap a bar to seek.
 */
@Composable
fun SheetView(notes: List<Note>, grid: TimeGrid, position: Double, preferFlats: Boolean, modifier: Modifier = Modifier, onSeek: (Double) -> Unit = {}) {
    val measures = remember(notes, grid) { ScoreBuilder.build(notes, grid) }
    val colors = LocalMusicColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val perSystem = when {
            maxWidth < 420.dp -> 2
            maxWidth < 700.dp -> 3
            maxWidth < 1000.dp -> 4
            else -> 6
        }
        val gap = with(density) { 8.dp.toPx() } // staff line spacing
        val systemH = gap * 22
        val systems = (measures.size + perSystem - 1) / perSystem
        val heightDp = with(density) { (systemH * systems + gap * 6).toDp() }
        val measureDiv = grid.beatsPerBar * ScoreBuilder.DIVISIONS * 4 / grid.beatUnit
        val currentMeasure = (grid.timeToBeats(position) / grid.beatsPerBar).toInt()
        LaunchedEffect(currentMeasure / perSystem) {
            val target = ((currentMeasure / perSystem) * systemH - systemH).toInt().coerceAtLeast(0)
            if (target !in scroll.value..(scroll.value + scroll.viewportSize - systemH.toInt())) scroll.animateScrollTo(target)
        }
        Canvas(
            Modifier.fillMaxWidth().verticalScroll(scroll).height(heightDp).pointerInput(measures.size, perSystem) {
                detectTapGestures { p ->
                    val sys = ((p.y - gap * 3) / systemH).toInt().coerceAtLeast(0)
                    val left = gap * 7
                    val mw = (widthPx - left - gap * 2) / perSystem
                    val col = ((p.x - left) / mw).toInt().coerceIn(0, perSystem - 1)
                    val m = sys * perSystem + col
                    if (m < measures.size) onSeek(grid.beatsToTime((m * grid.beatsPerBar).toDouble()).coerceAtLeast(0.0))
                }
            },
        ) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb() }
            val left = gap * 7
            val mw = (size.width - left - gap * 2) / perSystem
            for (sIdx in 0 until systems) {
                val top = gap * 3 + sIdx * systemH
                val trebleTop = top + gap * 2
                val bassTop = trebleTop + gap * 10
                // Staff lines.
                for (st in listOf(trebleTop, bassTop)) for (l in 0 until 5) {
                    drawLine(ink.copy(alpha = 0.7f), Offset(gap, st + l * gap), Offset(size.width - gap, st + l * gap), 1.2f)
                }
                drawLine(ink, Offset(gap, trebleTop), Offset(gap, bassTop + gap * 4), 2f)
                paint.textSize = gap * 6.2f
                drawContext.canvas.nativeCanvas.drawText("𝄞", gap * 1.4f, trebleTop + gap * 4.4f, paint)
                paint.textSize = gap * 3.6f
                drawContext.canvas.nativeCanvas.drawText("𝄢", gap * 1.6f, bassTop + gap * 2.9f, paint)
                if (sIdx == 0) {
                    paint.textSize = gap * 2.2f
                    paint.isFakeBoldText = true
                    for (st in listOf(trebleTop, bassTop)) {
                        drawContext.canvas.nativeCanvas.drawText("${grid.beatsPerBar}", gap * 5.2f, st + gap * 1.8f, paint)
                        drawContext.canvas.nativeCanvas.drawText("${grid.beatUnit}", gap * 5.2f, st + gap * 3.8f, paint)
                    }
                    paint.isFakeBoldText = false
                }
                for (c in 0 until perSystem) {
                    val mi = sIdx * perSystem + c
                    if (mi >= measures.size) break
                    val m = measures[mi]
                    val mx = left + c * mw
                    if (mi == currentMeasure) drawRect(colors.playhead.copy(alpha = 0.08f), Offset(mx, trebleTop - gap * 2), Size(mw, gap * 18))
                    drawLine(ink, Offset(mx + mw, trebleTop), Offset(mx + mw, bassTop + gap * 4), 1.5f)
                    paint.textSize = gap * 1.3f
                    drawContext.canvas.nativeCanvas.drawText("${mi + 1}", mx + 2, trebleTop - gap * 0.8f, paint)
                    val inner = mw - gap * 2
                    fun xOf(div: Int) = mx + gap + (div - mi * measureDiv).toFloat() / measureDiv * inner
                    drawVoice(m.treble, true, trebleTop, gap, ::xOf, ink, paint, preferFlats)
                    drawVoice(m.bass, false, bassTop, gap, ::xOf, ink, paint, preferFlats)
                }
            }
        }
    }
}

private val SHARP_STEP = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
private val SHARP_ALT = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
private val FLAT_STEP = intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6)
private val FLAT_ALT = intArrayOf(0, -1, 0, -1, 0, 0, -1, 0, -1, 0, -1, 0)

/** Diatonic position: octave * 7 + step (C=0..B=6). */
private fun diatonic(midi: Int, flats: Boolean): Pair<Int, Int> {
    val pc = Math.floorMod(midi, 12)
    val oct = Math.floorDiv(midi, 12) - 1
    return if (flats) (oct * 7 + FLAT_STEP[pc]) to FLAT_ALT[pc] else (oct * 7 + SHARP_STEP[pc]) to SHARP_ALT[pc]
}

private fun DrawScope.drawVoice(
    events: List<ScoreBuilder.Event>,
    treble: Boolean,
    staffTop: Float,
    gap: Float,
    xOf: (Int) -> Float,
    ink: Color,
    paint: android.graphics.Paint,
    flats: Boolean,
) {
    // Bottom line: E4 (treble) or G2 (bass).
    val bottomPos = if (treble) 4 * 7 + 2 else 2 * 7 + 4
    val bottomY = staffTop + gap * 4
    fun yOf(pos: Int) = bottomY - (pos - bottomPos) * gap / 2
    val middleLine = bottomPos + 4
    var prevTie: Pair<Float, List<Float>>? = null
    for (e in events) {
        val x = xOf(e.startDiv) + gap
        if (e.isRest) {
            val y = staffTop + gap * 2
            when (e.type) {
                "whole" -> drawRect(ink, Offset(x - gap * 0.6f, staffTop + gap), Size(gap * 1.2f, gap * 0.5f))
                "half" -> drawRect(ink, Offset(x - gap * 0.6f, staffTop + gap * 1.5f), Size(gap * 1.2f, gap * 0.5f))
                else -> {
                    val glyph = when (e.type) { "quarter" -> "𝄽"; "eighth" -> "𝄾"; else -> "𝄿" }
                    paint.textSize = gap * 3.2f
                    drawContext.canvas.nativeCanvas.drawText(glyph, x - gap * 0.5f, y + gap * 1.0f, paint)
                }
            }
            prevTie = null
            continue
        }
        val positions = e.pitches.map { diatonic(it, flats) }
        val hollow = e.type == "half" || e.type == "whole"
        val ys = positions.map { yOf(it.first) }
        val avg = positions.map { it.first }.average()
        val stemUp = avg < middleLine
        val headW = gap * 1.25f
        val headH = gap * 0.95f
        for ((i, p) in positions.withIndex()) {
            val y = ys[i]
            // Ledger lines.
            if (p.first < bottomPos) {
                var lp = bottomPos - 2
                while (lp >= p.first) { val ly = yOf(lp); drawLine(ink, Offset(x - headW * 0.9f, ly), Offset(x + headW * 0.9f, ly), 1.2f); lp -= 2 }
            }
            if (p.first > bottomPos + 8) {
                var lp = bottomPos + 10
                while (lp <= p.first) { val ly = yOf(lp); drawLine(ink, Offset(x - headW * 0.9f, ly), Offset(x + headW * 0.9f, ly), 1.2f); lp += 2 }
            }
            val r = Rect(x - headW / 2, y - headH / 2, x + headW / 2, y + headH / 2)
            if (hollow) drawOval(ink, r.topLeft, r.size, style = Stroke(gap * 0.22f)) else drawOval(ink, r.topLeft, r.size)
            if (p.second != 0) {
                paint.textSize = gap * 2.0f
                drawContext.canvas.nativeCanvas.drawText(if (p.second > 0) "♯" else "♭", x - headW * 1.55f, y + gap * 0.65f, paint)
            }
            if (e.dotted) drawCircle(ink, gap * 0.18f, Offset(x + headW * 0.9f, y - gap * 0.2f))
        }
        if (e.type != "whole") {
            val stemX = if (stemUp) x + headW / 2 - 0.8f else x - headW / 2 + 0.8f
            val yFrom = if (stemUp) ys.max() else ys.min()
            val yTo = if (stemUp) ys.min() - gap * 3.3f else ys.max() + gap * 3.3f
            drawLine(ink, Offset(stemX, yFrom), Offset(stemX, yTo), 1.4f)
            val flags = when (e.type) { "eighth" -> 1; "16th" -> 2; else -> 0 }
            for (f in 0 until flags) {
                val fy = yTo + (if (stemUp) f * gap * 0.8f else -f * gap * 0.8f)
                drawLine(ink, Offset(stemX, fy), Offset(stemX + gap * 0.9f, fy + (if (stemUp) gap * 1.4f else -gap * 1.4f)), 1.8f)
            }
        }
        val pt = prevTie
        if (e.tieStop && pt != null) {
            for (y in ys) {
                val x0 = pt.first + headW * 0.6f
                val x1 = x - headW * 0.6f
                if (x1 > x0) drawArc(ink, 0f, 180f, false, Offset(x0, y + gap * 0.3f), Size(x1 - x0, gap * 0.9f), style = Stroke(1.2f))
            }
        }
        prevTie = if (e.tieStart) x to ys else null
    }
}
