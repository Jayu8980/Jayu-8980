package com.pianoscribe.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pianoscribe.app.playback.PlayMode
import com.pianoscribe.app.ui.components.formatTime
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.theory.Pitch
import kotlin.math.roundToInt

/**
 * Compares the MIDI with the recording and fixes it. The headline number is the share of notes the
 * recording confirms (an estimate of how many notes are right); the sound match is only shown as a
 * before/after change, because a resynthesis never sounds exactly like a recording.
 */
@Composable
fun CompareView(vm: ResultViewModel, s: ResultViewModel.UiState, position: Double, playing: Boolean) {
    val c = s.compare
    val colors = LocalMusicColors.current
    val d = s.derived
    val viewport = remember { RollViewport(pxPerSec = 60f, rowPx = 10f) }
    LaunchedEffect(d.pitchHi) { viewport.topPitch = (d.pitchHi + 3).toFloat() }
    val stale = vm.compareIsStale
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val w = Modifier.widthIn(max = 900.dp).fillMaxWidth()
        Card(w) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Compare with the recording", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The MIDI is turned back into sound and compared with your recording, note by note. " +
                        "Auto-fix then removes notes the recording doesn't contain and adds ones it clearly does, " +
                        "keeping a change only if the MIDI sounds closer to the recording.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (c.running) {
                    LinearProgressIndicator(progress = { c.progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = vm::cancelCompare) { Text("Cancel") }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::runCompare) {
                        Icon(Icons.Default.CompareArrows, null)
                        Text(if (c.report == null) "  Compare" else "  Compare again")
                    }
                    if (c.report != null) FilledTonalButton(onClick = vm::runAutoFix, enabled = !stale) {
                        Icon(Icons.Default.AutoFixHigh, null)
                        Text("  Auto-fix")
                    }
                }
                c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        val r = c.report ?: return@Column
        if (stale) Text("The notes have changed since this comparison. Compare again to update it.", color = colors.warning, style = MaterialTheme.typography.bodyMedium)

        Card(w) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val fix = c.lastFix
                if (fix != null && fix.accepted) {
                    Text("Auto-fix result", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Before the fix, the recording confirmed ${pct(fix.before.confirmedShare)} of ${fix.before.noteCount} notes " +
                            "(an estimate of how many were right).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Removed ${fix.changes.removed} notes the recording doesn't contain, added ${fix.changes.added} it was missing, " +
                            "joined ${fix.changes.merged} split notes. Now ${r.noteCount} notes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Sound match: ${pct(fix.before.soundMatch)} → ${pct(r.soundMatch)} (higher = closer)",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.good)
                } else {
                    Text("Notes the recording confirms", style = MaterialTheme.typography.labelLarge)
                    Text(pct(r.confirmedShare), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                        color = lerp(colors.warning, colors.good, r.confirmedShare.toFloat().coerceIn(0f, 1f)))
                    Text("${r.noteCount - r.unconfirmed.size} of ${r.noteCount} notes make the MIDI sound more like your recording. " +
                        "This is an estimate of how many notes are right.", style = MaterialTheme.typography.bodySmall)
                    if (fix != null && !fix.accepted) Text("Auto-fix found no change that made the MIDI sound closer, so nothing was changed.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Unconfirmed notes: ${r.unconfirmed.size} (outlined below) · Possibly missing: ${r.possiblyMissing.size}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Compared using a ${r.timbre.label.lowercase()} sound, the closest to your recording.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Why not 100%: a resynthesis never sounds exactly like a real instrument in a real room, and notes hidden under " +
                        "others or under drums can't always be found. Mark notes you have checked as verified in the Analyser: auto-fix never changes those.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("How closely each second matches (tap to jump there)", style = MaterialTheme.typography.labelLarge, modifier = w)
        MatchTimeline(r.timeline, position, w.height(36.dp)) { vm.seek(it) }

        PianoRoll(
            notes = d.notes, grid = s.grid, duration = d.duration, position = position, playing = playing,
            selectedId = s.selectedId, editable = false, viewport = viewport, key = d.key,
            highlightIds = if (stale) emptySet() else r.unconfirmed,
            modifier = w.height(240.dp),
            onSelect = vm::select, onSeek = vm::seek,
        )
        if (r.possiblyMissing.isNotEmpty()) {
            Text("Possibly missing: " + r.possiblyMissing.take(8).joinToString(", ") { "${Pitch.name(it.pitch)} at ${formatTime(it.start)}" } +
                if (r.possiblyMissing.size > 8) " …" else "", style = MaterialTheme.typography.bodySmall, modifier = w)
        }
        OutlinedButton(onClick = { vm.setMode(PlayMode.BOTH); if (!playing) vm.togglePlay() }) {
            Icon(Icons.Default.Headphones, null)
            Text("  Listen: recording and MIDI together")
        }
    }
}

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"

@Composable
private fun MatchTimeline(timeline: DoubleArray, position: Double, modifier: Modifier, onSeek: (Double) -> Unit) {
    val colors = LocalMusicColors.current
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.pointerInput(timeline.size) {
        detectTapGestures { p -> if (timeline.isNotEmpty()) onSeek((p.x / size.width * timeline.size).toDouble()) }
    }) {
        if (timeline.isEmpty()) return@Canvas
        val bw = size.width / timeline.size
        // Colour relative to this song's own range: the absolute value depends on timbre and room.
        val valid = timeline.filter { !it.isNaN() }
        val lo = valid.minOrNull() ?: 0.0
        val hi = valid.maxOrNull() ?: 1.0
        for ((i, v) in timeline.withIndex()) {
            val c = if (v.isNaN()) empty else lerp(colors.warning, colors.good, (if (hi > lo) ((v - lo) / (hi - lo)) else 1.0).toFloat())
            drawRect(c, Offset(i * bw, 0f), Size(bw + 0.5f, size.height))
        }
        val x = (position / timeline.size * size.width).toFloat()
        drawLine(colors.playhead, Offset(x, 0f), Offset(x, size.height), 3f)
    }
}
