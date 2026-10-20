package com.gateee.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Categorical palette (colour-blind friendly). */
val SeriesColors = listOf(
    Color(0xFF3B6FD8), Color(0xFFE0912E), Color(0xFF2E9E8F), Color(0xFFC2457A), Color(0xFF7A62D6),
    Color(0xFF7FA33A), Color(0xFFD45D3A), Color(0xFF4AA3DF), Color(0xFF9C7A3C), Color(0xFF5F6B7A),
)

/** Horizontal bars: label, bar, value. Works well on narrow phone screens. */
@Composable
fun HBarChart(rows: List<Pair<String, Double>>, valueLabel: (Double) -> String = { "%.1f".format(it) }, color: Color = MaterialTheme.colorScheme.primary) {
    val max = rows.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (label, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0f, 1f)).height(14.dp).background(color))
                }
                Text(valueLabel(v), Modifier.width(48.dp).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Multi-series line chart with a legend; x values are evenly spaced labels. */
@Composable
fun LineChart(xLabels: List<String>, series: List<Pair<String, List<Double>>>, height: Int = 180) {
    val all = series.flatMap { it.second }
    val maxY = (all.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(height.dp).padding(vertical = 8.dp)) {
            val n = xLabels.size.coerceAtLeast(2)
            val dx = size.width / (n - 1)
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            series.forEachIndexed { si, (_, ys) ->
                val color = SeriesColors[si % SeriesColors.size]
                val path = Path()
                ys.forEachIndexed { i, v ->
                    val p = Offset(i * dx, size.height - (v / maxY).toFloat() * size.height)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    drawCircle(color, 4f, p)
                }
                drawPath(path, color, style = Stroke(width = 3f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(xLabels.firstOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall)
            Text("max %.0f".format(maxY), style = MaterialTheme.typography.labelSmall)
            Text(xLabels.lastOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall)
        }
        Legend(series.map { it.first })
    }
}

/** One stacked bar per row (e.g. MCQ/MSQ/NAT split per year). */
@Composable
fun StackedBars(rows: List<Pair<String, List<Double>>>, keys: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (label, parts) ->
            val total = parts.sum().takeIf { it > 0 } ?: 1.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp))) {
                    parts.forEachIndexed { i, v ->
                        if (v > 0) Box(Modifier.weight((v / total).toFloat()).height(14.dp).background(SeriesColors[i % SeriesColors.size]))
                    }
                }
            }
        }
        Legend(keys)
    }
}

@Composable
fun Legend(names: List<String>) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        names.forEachIndexed { i, n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(SeriesColors[i % SeriesColors.size]))
                Spacer(Modifier.width(4.dp))
                Text(n, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Difficulty curve: one coloured cell per question in paper order. */
@Composable
fun DifficultyStrip(difficulties: List<String>) {
    Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(3.dp))) {
        difficulties.forEach { d ->
            val c = when (d) { "Easy" -> Color(0xFF7FC97F); "Hard" -> Color(0xFFD9442B); else -> Color(0xFFF0B429) }
            Box(Modifier.weight(1f).height(18.dp).background(c))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Easy" to Color(0xFF7FC97F), "Medium" to Color(0xFFF0B429), "Hard" to Color(0xFFD9442B)).forEach { (n, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(c)); Spacer(Modifier.width(4.dp)); Text(n, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
