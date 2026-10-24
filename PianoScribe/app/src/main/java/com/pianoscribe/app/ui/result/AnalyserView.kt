package com.pianoscribe.app.ui.result

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianoscribe.app.data.AppSettings
import com.pianoscribe.app.ui.theme.LocalMusicColors
import com.pianoscribe.core.analysis.Step
import com.pianoscribe.core.analysis.StepFilter
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.theory.ChordNamer
import com.pianoscribe.core.theory.Interval
import com.pianoscribe.core.theory.NoteValue
import com.pianoscribe.core.theory.Pitch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AnalyserView(vm: ResultViewModel, s: ResultViewModel.UiState, settings: AppSettings, position: Double, playing: Boolean) {
    val d = s.derived
    val step = d.steps.getOrNull(s.stepIndex)
    if (step == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No notes to analyse") }
        return
    }
    val colors = LocalMusicColors.current
    val focused = step.notes.firstOrNull { it.id == s.focusedNoteId } ?: step.top
    val rollViewport = remember { RollViewport(pxPerSec = 110f, rowPx = 14f) }
    LaunchedEffect(step.index) {
        rollViewport.start = step.start - 1.2
        val mid = (step.notes.maxOf { it.pitch } + step.notes.minOf { it.pitch }) / 2f
        rollViewport.topPitch = mid + 7f
    }
    val flats = d.key?.preferFlats ?: false
    var swipe by remember { mutableFloatStateOf(0f) }
    val swipeModifier = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragEnd = { if (swipe < -120) vm.step(1) else if (swipe > 120) vm.step(-1); swipe = 0f },
            onDragCancel = { swipe = 0f },
        ) { change, amount -> swipe += amount; change.consume() }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 720.dp
        val nav: @Composable () -> Unit = { StepNavigator(vm, s, d.steps.size) }
        val visual: @Composable () -> Unit = {
            PianoRoll(
                notes = d.notes, grid = s.grid, duration = d.duration, position = position, playing = playing,
                selectedId = focused.id, editable = false, viewport = rollViewport, key = d.key,
                highlightIds = step.notes.map { it.id }.toSet(),
                modifier = Modifier.fillMaxWidth().height(150.dp),
                onSelect = { id -> if (id != null) { val idx = d.steps.indexOfFirst { st -> st.notes.any { it.id == id } }; if (idx >= 0) vm.goToStep(idx) } },
                onSeek = vm::seek,
            )
            StepKeyboard(step, d.fingers, colors.rightHand, colors.leftHand, s.practice, vm)
        }
        val card: @Composable () -> Unit = { DetailCard(vm, s, step, focused, settings, flats) }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    nav(); visual()
                    PracticePanel(vm, s)
                }
                Column(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()).then(swipeModifier).padding(8.dp)) { card() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nav(); visual()
                Box(swipeModifier) { card() }
                PracticePanel(vm, s)
            }
        }
    }
}

@Composable
private fun StepNavigator(vm: ResultViewModel, s: ResultViewModel.UiState, total: Int) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { vm.step(-1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text(" Previous") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${s.stepIndex + 1} / $total", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = vm::playCurrentStep) { Icon(Icons.Default.VolumeUp, null, Modifier.size(18.dp)); Text(" Play") }
            }
            FilledTonalButton(onClick = { vm.step(1) }) { Text("Next ") ; Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(StepFilter.ALL to "All", StepFilter.LOW_CONFIDENCE to "Low confidence", StepFilter.CHORD to "Chords", StepFilter.RIGHT_HAND to "Right hand", StepFilter.LEFT_HAND to "Left hand")
                .forEach { (f, label) -> FilterChip(selected = s.filter == f, onClick = { vm.setFilter(f) }, label = { Text(label) }) }
        }
    }
}

@Composable
private fun StepKeyboard(step: Step, fingers: Map<Long, Int>, rh: Color, lh: Color, practice: PracticeState, vm: ResultViewModel) {
    val held by vm.midiInput.held.collectAsStateWithLifecycle()
    val colors = LocalMusicColors.current
    val lit = HashMap<Int, Color>()
    val labels = HashMap<Int, String>()
    for (n in step.notes) {
        lit[n.pitch] = if (n.hand == Hand.RIGHT) rh else lh
        fingers[n.id]?.let { labels[n.pitch] = it.toString() }
    }
    if (practice.enabled) for (k in held) lit[k] = if (k in lit) colors.good else colors.warning
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val keyW = 18.dp
    LaunchedEffect(step.index) {
        val layout = KeyLayout(21, 108, with(density) { (keyW * 52).toPx() })
        val center = layout.centerX(step.notes.sumOf { it.pitch } / step.notes.size)
        scroll.animateScrollTo((center - scroll.viewportSize / 2f).toInt().coerceAtLeast(0))
    }
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        PianoKeyboard(21, 108, lit, Modifier.width(keyW * 52).height(96.dp), labels, onKey = { vm.midiPreview(it) })
    }
}

@Composable
private fun DetailCard(vm: ResultViewModel, s: ResultViewModel.UiState, step: Step, n: Note, settings: AppSettings, flats: Boolean) {
    val colors = LocalMusicColors.current
    val d = s.derived
    val g = s.grid
    val lowConf = n.confidence < 0.5f && !n.verified
    val key = d.key
    val section = d.sections.lastOrNull { it.startSec <= n.start }?.key ?: key
    val prevStep = d.steps.getOrNull(step.index - 1)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (lowConf) colors.warning.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Chord or single note header.
            if (step.isChord) {
                val chord = ChordNamer.name(step.notes.map { it.pitch }, flats)
                Text(chord?.name ?: "Chord", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (chord != null) Text("${chord.quality} · ${chord.inversionName}", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    step.notes.sortedBy { it.pitch }.forEach { cn ->
                        FilterChip(selected = cn.id == n.id, onClick = { vm.focusNote(cn.id) }, label = { Text(Pitch.name(cn.pitch, flats)) })
                    }
                }
                HorizontalDivider()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Pitch.name(n.pitch, flats), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                        color = if (n.hand == Hand.RIGHT) colors.rightHand else colors.leftHand)
                    Text("${Pitch.sargam(n.pitch, settings.saPitchClass)}  ·  MIDI ${n.pitch}  ·  " + String.format(Locale.US, "%.2f Hz", Pitch.frequency(n.pitch)),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Tuner(n.cents, Modifier.size(width = 120.dp, height = 70.dp))
            }
            Info("Time", String.format(Locale.US, "%.3f s  ·  %s", n.start, NoteValue.barBeat(n.start, g).label))
            Info("Duration", String.format(Locale.US, "%.3f s  ·  %s", n.duration, NoteValue.nameForSeconds(n.duration, g)))
            Info("Velocity", "${n.velocity} / 127  (${loudness(n.velocity)})")
            Info("Hand / finger", (if (n.hand == Hand.RIGHT) "Right hand" else "Left hand") + (d.fingers[n.id]?.let { "  ·  finger $it" } ?: ""))
            Info("Confidence", "${Math.round(n.confidence * 100)}%" + (if (n.verified) "  ·  verified ✓" else if (lowConf) "  ·  low, please check" else ""),
                if (lowConf) colors.warning else null)
            Info("Tuning", String.format(Locale.US, "%+d cents (%s)", Math.round(n.cents), when { abs(n.cents) < 5 -> "in tune"; n.cents > 0 -> "sharp"; else -> "flat" }))
            if (key != null) {
                val inKey = key.contains(n.pitch)
                Info("Key", key.name + (if (section != null && section.name != key.name) "  ·  this section: ${section.name}" else "") + if (inKey) "" else "  ·  outside the key",
                    if (inKey) null else colors.outOfKey)
            }
            if (prevStep != null) {
                val from = if (n.hand == Hand.LEFT) prevStep.notes.minOf { it.pitch } else prevStep.top.pitch
                val dir = Interval.direction(from, n.pitch)
                val arrow = when (dir) { Interval.Direction.UP -> "↑"; Interval.Direction.DOWN -> "↓"; Interval.Direction.SAME -> "→"; else -> "" }
                Info("From previous", "$arrow ${Interval.describe(from, n.pitch)} (from ${Pitch.name(from, flats)})")
            }
            HorizontalDivider()
            Text("Fix it", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AssistChip(onClick = { vm.fixPitch(-1) }, label = { Text("−1 semitone") })
                AssistChip(onClick = { vm.fixPitch(1) }, label = { Text("+1 semitone") })
                AssistChip(onClick = { vm.fixPitch(-12) }, label = { Text("Octave down") })
                AssistChip(onClick = { vm.fixPitch(12) }, label = { Text("Octave up") })
                AssistChip(onClick = vm::fixSplit, label = { Text("Split") }, leadingIcon = { Icon(Icons.Default.CallSplit, null) })
                AssistChip(onClick = vm::fixMergeNext, label = { Text("Merge next") }, leadingIcon = { Icon(Icons.Default.CallMerge, null) })
                AssistChip(onClick = vm::fixDelete, label = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                AssistChip(onClick = vm::fixToggleVerified, label = { Text(if (n.verified) "Unverify" else "Mark verified") }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) })
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Spectrum & pitch contour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (s.spectrumLoading) CircularProgressIndicator(Modifier.size(20.dp))
                else OutlinedButton(onClick = vm::loadSpectrum) { Icon(Icons.Default.GraphicEq, null); Text(if (s.spectrum?.noteId == n.id) " Refresh" else " Show") }
            }
            s.spectrum?.takeIf { it.noteId == n.id }?.let { SpectrumPanel(it, n) }
            Text("Swipe left or right on this card to move between notes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun loudness(v: Int) = when {
    v < 32 -> "pp"; v < 52 -> "p"; v < 68 -> "mp"; v < 84 -> "mf"; v < 100 -> "f"; else -> "ff"
}

@Composable
private fun Info(label: String, value: String, color: Color? = null) {
    Row {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color ?: MaterialTheme.colorScheme.onSurface, fontWeight = if (color != null) FontWeight.SemiBold else null)
    }
}

/** Small tuner: needle from -50 (left) to +50 cents (right). */
@Composable
fun Tuner(cents: Float, modifier: Modifier = Modifier) {
    val colors = LocalMusicColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height * 0.92f
        val r = minOf(size.width / 2, size.height) * 0.88f
        drawArc(ink.copy(alpha = 0.25f), 180f, 180f, false, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(4f))
        drawArc(colors.good.copy(alpha = 0.7f), 270f - 9f, 18f, false, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(6f))
        for (t in listOf(-50, -25, 0, 25, 50)) {
            val a = PI + (t + 50) / 100.0 * PI
            drawLine(ink.copy(alpha = 0.5f), Offset(cx + (r - 8) * cos(a).toFloat(), cy + (r - 8) * sin(a).toFloat()), Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()), 2f)
        }
        val a = PI + (cents.coerceIn(-50f, 50f) + 50) / 100.0 * PI
        val c = if (abs(cents) < 10) colors.good else colors.warning
        drawLine(c, Offset(cx, cy), Offset(cx + (r - 4) * cos(a).toFloat(), cy + (r - 4) * sin(a).toFloat()), 4f)
        drawCircle(c, 5f, Offset(cx, cy))
    }
}

@Composable
private fun SpectrumPanel(sp: SpectrumData, n: Note) {
    val colors = LocalMusicColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    Text("Frequency spectrum (0–${Math.round(sp.maxHz)} Hz). Dashed lines mark the expected harmonics of ${Pitch.name(n.pitch)}.",
        style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val bins = sp.spectrumDb.size
        if (bins < 2) return@Canvas
        val path = Path()
        for (i in 0 until bins) {
            val x = size.width * i / (bins - 1)
            val y = size.height * (-sp.spectrumDb[i] / 80f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (h in 1..8) {
            val f = sp.f0 * h
            if (f > sp.maxHz) break
            val x = (f / sp.maxHz * size.width).toFloat()
            drawLine(if (h == 1) colors.playhead else ink.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        drawPath(path, primary, style = Stroke(1.6f))
    }
    val contour = sp.contour
    if (contour != null) {
        Text("Model pitch contour around ${Pitch.name(n.pitch)} (±1 octave, 3 bins per semitone). The peak shows where the model heard the pitch.",
            style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val center = 3 * (n.pitch - 21) + 1
            val lo = (center - 36).coerceAtLeast(0)
            val hi = (center + 36).coerceAtMost(contour.size - 1)
            val count = hi - lo + 1
            val bw = size.width / count
            val max = (lo..hi).maxOf { contour[it] }.coerceAtLeast(1e-3f)
            for (b in lo..hi) {
                val v = contour[b] / max
                val semitoneMark = (b - 1) % 3 == 0
                drawRect(if (b == center) colors.playhead else primary.copy(alpha = if (semitoneMark) 0.9f else 0.5f),
                    Offset((b - lo) * bw, size.height * (1 - v)), Size(bw * 0.85f, size.height * v))
            }
        }
    }
    Text(String.format(Locale.US, "Measured tuning: %+d cents", Math.round(sp.measuredCents)), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PracticePanel(vm: ResultViewModel, s: ResultViewModel.UiState) {
    val ctx = LocalContext.current
    val colors = LocalMusicColors.current
    val p = s.practice
    val connected by vm.midiInput.connected.collectAsStateWithLifecycle()
    val sources by vm.midiInput.sources.collectAsStateWithLifecycle()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.setPracticeEnabled(true) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.all { it }) vm.midiInput.scanBluetooth() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Piano, null)
                Text("  Practice mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = p.enabled, onCheckedChange = { on ->
                    if (on && p.source == PracticeSource.MIC && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else vm.setPracticeEnabled(on)
                })
            }
            Text("The app plays the note, then you play it on your piano. It moves on only when you get it right.", style = MaterialTheme.typography.bodySmall)
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(p.source == PracticeSource.MIC, { vm.setPracticeSource(PracticeSource.MIC) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Microphone") }
                SegmentedButton(p.source == PracticeSource.MIDI, { vm.setPracticeSource(PracticeSource.MIDI) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("MIDI keyboard") }
            }
            if (p.source == PracticeSource.MIDI) {
                if (!vm.midiInput.available) Text("This phone has no MIDI support.", color = MaterialTheme.colorScheme.error)
                else {
                    Text(connected?.let { "Connected: $it" } ?: "Not connected. Plug in a USB keyboard or scan for Bluetooth.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sources.forEach { src -> AssistChip(onClick = { vm.midiInput.connect(src) }, label = { Text(src.name) }) }
                        AssistChip(onClick = {
                            val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            btPermission.launch(perms)
                        }, label = { Text("Scan Bluetooth") }, leadingIcon = { Icon(Icons.Default.Bluetooth, null) })
                    }
                }
            }
            if (p.enabled) {
                val (text, color) = when (p.status) {
                    PracticeStatus.CORRECT -> ("✓ Correct" + (p.cents?.let { String.format(Locale.US, "  (%+d cents)", it) } ?: "")) to colors.good
                    PracticeStatus.WRONG -> ("✗ Heard ${p.heard}" + (p.cents?.let { c -> if (abs(c) >= 100) "  (${c / 100} semitones off)" else String.format(Locale.US, "  (%+d cents)", c) } ?: "")) to colors.warning
                    PracticeStatus.LISTENING -> "Listening… play the highlighted key(s)" to MaterialTheme.colorScheme.onSurface
                    PracticeStatus.IDLE -> "" to MaterialTheme.colorScheme.onSurface
                }
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
            }
            p.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

