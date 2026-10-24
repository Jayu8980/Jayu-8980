package com.pianoscribe.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pianoscribe.core.transcribe.AudioType
import java.util.Locale

fun formatTime(sec: Double): String {
    val s = sec.coerceAtLeast(0.0)
    val m = (s / 60).toInt()
    return String.format(Locale.US, "%d:%05.2f", m, s - m * 60)
}

fun formatDuration(sec: Double): String {
    val s = sec.toInt()
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Mirrored bar waveform (values 0..1). */
@Composable
fun Waveform(levels: FloatArray, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (levels.isEmpty()) return@Canvas
        val mid = size.height / 2
        val step = size.width / levels.size
        val w = maxOf(1f, step * 0.7f)
        for (i in levels.indices) {
            val h = maxOf(1f, levels[i] * size.height * 0.48f)
            val x = i * step + step / 2
            drawLine(color, Offset(x, mid - h), Offset(x, mid + h), strokeWidth = w)
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    help: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Solo piano / full song / auto choice, with a one-line explanation of the selected mode. */
@Composable
fun AudioTypeSelector(value: AudioType, onChange: (AudioType) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Audio type", style = MaterialTheme.typography.titleSmall)
        val options = listOf(AudioType.AUTO to "Auto", AudioType.SOLO_PIANO to "Solo piano", AudioType.FULL_SONG to "Full song")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (t, label) ->
                SegmentedButton(value == t, { onChange(t) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
            }
        }
        Text(
            when (value) {
                AudioType.AUTO -> "Detects drums and band automatically and switches to full-song mode when needed."
                AudioType.SOLO_PIANO -> "Every note the model hears is kept. Best for a piano or one instrument."
                AudioType.FULL_SONG -> "Removes drums before listening, then drops overtone ghosts, faint background notes and extra simultaneous notes."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
