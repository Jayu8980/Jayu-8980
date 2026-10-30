package com.pianoscribe.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.score.TabWriter
import com.pianoscribe.core.score.Tablature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Guitar tablature of the notes ([Tablature] fingering, standard tuning): six lines, high E on
 * top, fret numbers where each note starts, bars across the width, the playhead, tap to play from
 * a moment.
 */
@Composable
fun TabView(notes: List<Note>, grid: TimeGrid, position: Double, modifier: Modifier = Modifier, onSeek: (Double) -> Unit = {}) {
    val density = LocalDensity.current
    val colors = LocalMusicColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val scroll = rememberScrollState()
    var bars by remember { mutableStateOf<List<TabWriter.TabBar>?>(null) }
    LaunchedEffect(notes, grid) { bars = withContext(Dispatchers.Default) { TabWriter.bars(Tablature.arrange(notes), grid) } }
    BoxWithConstraints(modifier) {
        val b = bars
        if (b == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@BoxWithConstraints }
        val gap = with(density) { 11.dp.toPx() } // between string lines
        val margin = with(density) { 14.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() } - 2 * margin
        val perLine = maxOf(1, (widthPx / with(density) { 170.dp.toPx() }).toInt())
        val lines = b.chunked(perLine)
        val lineH = gap * 5 + gap * 4
        val heightDp = with(density) { (lines.size * lineH + margin * 2).toDp() }
        val barW = widthPx / perLine
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Canvas(
                Modifier.fillMaxWidth().height(heightDp).pointerInput(b, barW) {
                    detectTapGestures { o ->
                        val li = ((o.y - margin) / lineH).toInt()
                        val bi = ((o.x - margin) / barW).toInt()
                        val bar = lines.getOrNull(li)?.getOrNull(bi) ?: return@detectTapGestures
                        val frac = ((o.x - margin - bi * barW - gap) / (barW - 2 * gap)).coerceIn(0f, 1f)
                        onSeek(bar.start + (bar.end - bar.start) * frac)
                    }
                },
            ) {
                val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = ink.toArgb(); textSize = gap * 1.05f; textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val small = android.graphics.Paint(text).apply { textSize = gap * 0.8f; typeface = android.graphics.Typeface.DEFAULT; textAlign = android.graphics.Paint.Align.LEFT }
                for ((li, line) in lines.withIndex()) {
                    val top = margin + li * lineH + gap * 2
                    for (s in 0 until 6) drawLine(ink.copy(alpha = 0.6f), Offset(margin, top + s * gap), Offset(margin + line.size * barW, top + s * gap), strokeWidth = 1.2f)
                    drawIntoCanvas { it.nativeCanvas.drawText("${line.first().index + 1}", margin, top - gap * 0.6f, small) }
                    for ((bi, bar) in line.withIndex()) {
                        val x0 = margin + bi * barW
                        drawLine(ink, Offset(x0 + barW, top), Offset(x0 + barW, top + 5 * gap), strokeWidth = 2f)
                        if (bi == 0) drawLine(ink, Offset(x0, top), Offset(x0, top + 5 * gap), strokeWidth = 2f)
                        val now = position >= bar.start && position < bar.end
                        if (now) {
                            val x = x0 + gap + (barW - 2 * gap) * ((position - bar.start) / (bar.end - bar.start)).toFloat()
                            drawLine(colors.playhead, Offset(x, top - gap), Offset(x, top + 6 * gap), strokeWidth = 3f)
                        }
                        for (item in bar.items) {
                            val x = x0 + gap + (barW - 2 * gap) * item.at
                            val y = top + (item.string - 1) * gap
                            val label = item.fret.toString()
                            val w = text.measureText(label) + 4f
                            drawRect(paper, Offset(x - w / 2, y - gap * 0.5f), Size(w, gap))
                            drawIntoCanvas { c -> c.nativeCanvas.drawText(label, x, y + gap * 0.36f, text) }
                        }
                    }
                }
            }
        }
    }
}
