package com.pianoscribe.app.ui.result

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pianoscribe.app.playback.PlayMode
import com.pianoscribe.app.ui.components.formatTime

private val SPEEDS = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

@Composable
fun TransportBar(vm: ResultViewModel, s: ResultViewModel.UiState, position: Double, playing: Boolean) {
    var speedMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = s.derived.duration
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp)) {
            Slider(
                value = scrubbing ?: position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { scrubbing = it },
                onValueChangeFinished = { scrubbing?.let { vm.seek(it.toDouble()) }; scrubbing = null },
                valueRange = 0f..maxOf(0.1f, duration.toFloat()),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = vm::togglePlay) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play") }
                IconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, "Stop") }
                Text(formatTime(position) + " / " + formatTime(duration), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { modeMenu = true }) {
                        Text(when (s.mode) { PlayMode.NOTES -> "Notes"; PlayMode.ORIGINAL -> "Original"; PlayMode.BOTH -> "A/B mix" })
                    }
                    DropdownMenu(modeMenu, { modeMenu = false }) {
                        DropdownMenuItem(text = { Text("Transcribed notes (piano)") }, onClick = { modeMenu = false; vm.setMode(PlayMode.NOTES) })
                        DropdownMenuItem(text = { Text("Original audio") }, onClick = { modeMenu = false; vm.setMode(PlayMode.ORIGINAL) })
                        DropdownMenuItem(text = { Text("Both mixed (A/B)") }, onClick = { modeMenu = false; vm.setMode(PlayMode.BOTH) })
                    }
                }
                Box {
                    TextButton(onClick = { speedMenu = true }) { Text("${trim(s.speed)}x") }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        SPEEDS.forEach { sp -> DropdownMenuItem(text = { Text("${trim(sp)}x") }, onClick = { speedMenu = false; vm.setSpeed(sp) }) }
                    }
                }
                IconButton(onClick = vm::loopButton) {
                    val tint = when {
                        s.loop != null -> MaterialTheme.colorScheme.primary
                        s.loopA != null -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(if (s.loopA != null) Icons.Default.RepeatOne else Icons.Default.Repeat, "Loop A-B", tint = tint, modifier = Modifier.size(24.dp))
                }
            }
            s.loop?.let { l ->
                Text("Looping ${formatTime(l.start)} – ${formatTime(l.endInclusive)} (tap the loop button to clear)",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
        }
    }
}

private fun trim(x: Double) = if (x == Math.floor(x)) x.toInt().toString() else x.toString()
