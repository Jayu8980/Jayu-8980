package com.gateee.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gateee.core.Figure
import com.gateee.core.FigureRender
import com.gateee.core.RenderItem

/** Theme colours for the display-list tokens; mirrors backend/src/figures/svg.js. */
private data class FigurePalette(val map: Map<String, Color>) {
    operator fun get(token: String?): Color = map[token] ?: map.getValue("fg")
}

@Composable
private fun figurePalette(): FigurePalette {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.4f
    val series = if (dark) listOf(0xFF7AA2F2, 0xFFF0A35E, 0xFF5FC9B3, 0xFFF07FA8, 0xFFA995F0, 0xFF9CCC65)
    else listOf(0xFF2F62C9, 0xFFD4762A, 0xFF21927F, 0xFFBF3F72, 0xFF6F55C9, 0xFF5F8F2C)
    return FigurePalette(
        mapOf(
            "fg" to cs.onSurface,
            "muted" to cs.onSurfaceVariant,
            "grid" to cs.outlineVariant.copy(alpha = 0.6f),
            "accent" to Color(if (dark) 0xFFF0A35E else 0xFFB5530B),
            "bg" to cs.surface,
        ) + series.mapIndexed { i, c -> "s$i" to Color(c) },
    )
}

/**
 * Draws a question figure natively from its display list, so figures work offline and follow
 * light/dark mode. Tap to open a full-screen view with pinch-zoom.
 */
@Composable
fun FigureView(figure: Figure, modifier: Modifier = Modifier) {
    val render = figure.render ?: return
    var zoomed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FigureCanvas(
            render,
            Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { zoomed = true },
        )
        Text("Tap the figure to zoom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (zoomed) {
        Dialog(onDismissRequest = { zoomed = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val state = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset += pan
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                FigureCanvas(
                    render,
                    Modifier.fillMaxWidth().padding(8.dp).transformable(state)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
                TextButton(onClick = { zoomed = false }, modifier = Modifier.align(Alignment.TopEnd)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun FigureCanvas(render: FigureRender, modifier: Modifier) {
    val palette = figurePalette()
    val measurer = rememberTextMeasurer()
    Canvas(modifier.aspectRatio((render.w / render.h).toFloat())) {
        val k = (size.width / render.w).toFloat()
        drawRect(palette["bg"])
        for (item in render.items) drawItem(item, k, palette, measurer)
    }
}

private fun DrawScope.drawItem(it: RenderItem, k: Float, pal: FigurePalette, measurer: TextMeasurer) {
    when (it.k) {
        "path" -> {
            if (it.p.size < 4) return
            val path = Path().apply {
                moveTo(it.p[0].toFloat() * k, it.p[1].toFloat() * k)
                var i = 2
                while (i + 1 < it.p.size) {
                    lineTo(it.p[i].toFloat() * k, it.p[i + 1].toFloat() * k)
                    i += 2
                }
                if (it.close == 1) close()
            }
            it.f?.let { fill -> drawPath(path, pal[fill], style = Fill) }
            drawPath(
                path,
                pal[it.s],
                style = Stroke(
                    width = it.w.toFloat() * k,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (it.d == 1) PathEffect.dashPathEffect(floatArrayOf(6f * k, 4f * k)) else null,
                ),
            )
        }
        "circle" -> {
            if (it.c.size < 2) return
            val center = Offset(it.c[0].toFloat() * k, it.c[1].toFloat() * k)
            val r = it.r.toFloat() * k
            it.f?.let { fill -> drawCircle(pal[fill], r, center, style = Fill) }
            drawCircle(pal[it.s], r, center, style = Stroke(width = it.w.toFloat() * k))
        }
        "text" -> {
            if (it.p.size < 2 || it.t.isEmpty()) return
            val style = TextStyle(
                color = pal[it.s],
                fontSize = (it.z.toFloat() * k).toSp(),
                fontWeight = if (it.b == 1) FontWeight.Bold else FontWeight.Normal,
            )
            val layout = measurer.measure(it.t, style)
            val x = it.p[0].toFloat() * k
            val left = when (it.a) {
                "s" -> x
                "e" -> x - layout.size.width
                else -> x - layout.size.width / 2f
            }
            // the y coordinate is the vertical centre of the text
            drawText(layout, topLeft = Offset(left, it.p[1].toFloat() * k - layout.size.height / 2f))
        }
    }
}
