package com.pianoscribe.app.ui.studio

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.audio.Draft
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.export.ExportFormat
import com.pianoscribe.app.export.Exporter
import com.pianoscribe.app.playback.PlayMode
import com.pianoscribe.app.playback.PlaybackEngine
import com.pianoscribe.app.studio.AudioStudio
import com.pianoscribe.app.studio.Separation
import com.pianoscribe.app.ui.components.LabeledSlider
import com.pianoscribe.app.ui.components.Waveform
import com.pianoscribe.app.ui.components.formatTime
import com.pianoscribe.core.audio.AudioReport
import com.pianoscribe.core.audio.CleanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudioViewModel(app: Application, private val container: AppContainer) : AndroidViewModel(app) {
    data class UiState(
        val error: String? = null,
        val name: String = "",
        val waveform: FloatArray = FloatArray(0),
        val duration: Double = 0.0,
        val selStart: Float = 0f,
        val selEnd: Float = 0f,
        val playing: Boolean = false,
        val position: Double = 0.0,
        /** Playing the version before the last change, to compare. */
        val hearingBefore: Boolean = false,
        val busy: String? = null,
        val progress: Float = 0f,
        val message: String? = null,
        val report: AudioReport? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val history: List<String> = emptyList(),
        val stereo: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val studio: AudioStudio? = container.draft.value?.let { AudioStudio(app, it) }
    private var player: PlaybackEngine? = null
    private var playerJob: Job? = null
    private var work: Job? = null

    init {
        if (studio == null) _state.value = UiState(error = "The audio is no longer available. Please import it again.")
        else viewModelScope.launch { reload(keepSelection = false) }
    }

    private suspend fun reload(keepSelection: Boolean) {
        val st = studio ?: return
        val d = st.current
        val wf = withContext(Dispatchers.IO) { PcmFile.waveform(d.file, 400) }
        val dur = d.durationSec
        val old = _state.value
        _state.value = old.copy(
            name = d.name, waveform = wf, duration = dur,
            selStart = if (keepSelection) old.selStart.coerceAtMost(dur.toFloat()) else 0f,
            selEnd = if (keepSelection) old.selEnd.coerceAtMost(dur.toFloat()) else dur.toFloat(),
            canUndo = st.canUndo, canRedo = st.canRedo, history = st.history, stereo = st.stereoAvailable,
            hearingBefore = false, report = null,
        )
        startPlayer(d.file, d.durationSec)
    }

    private fun startPlayer(file: java.io.File, duration: Double) {
        playerJob?.cancel()
        player?.release()
        val p = PlaybackEngine(null, file).apply { mode = PlayMode.ORIGINAL; this.duration = duration }
        player = p
        playerJob = viewModelScope.launch { p.state.collect { s -> _state.value = _state.value.copy(playing = s.playing, position = s.position) } }
    }

    fun setSelection(a: Float, b: Float) {
        val dur = _state.value.duration.toFloat()
        val s = a.coerceIn(0f, dur)
        _state.value = _state.value.copy(selStart = s, selEnd = b.coerceIn(s, dur))
    }

    fun togglePlay() {
        val p = player ?: return
        val s = _state.value
        if (!s.playing && (s.position < s.selStart || s.position >= s.selEnd - 0.05)) p.seek(s.selStart.toDouble())
        p.loop = if (s.selEnd - s.selStart < s.duration - 0.01) s.selStart.toDouble()..s.selEnd.toDouble() else null
        p.toggle()
    }

    fun stopPlayback() = player?.pause()

    /** Switches playback between the current version and the one before the last change. */
    fun toggleBefore() {
        val st = studio ?: return
        val before = st.previousFile ?: return
        val pos = _state.value.position
        val wasPlaying = _state.value.playing
        val hearBefore = !_state.value.hearingBefore
        val file = if (hearBefore) before else st.current.file
        startPlayer(file, PcmFile.length(file).toDouble() / st.sampleRate)
        _state.value = _state.value.copy(hearingBefore = hearBefore)
        player?.seek(pos)
        if (wasPlaying) player?.play()
    }

    private fun range(): Pair<Int, Int> {
        val s = _state.value
        val sr = studio?.sampleRate ?: 22050
        return (s.selStart * sr).toInt() to (s.selEnd * sr).toInt()
    }

    private fun run(label: String, op: suspend (AudioStudio) -> Any?) {
        val st = studio ?: return
        if (work?.isActive == true) return
        player?.pause()
        work = viewModelScope.launch {
            _state.value = _state.value.copy(busy = label, progress = 0f, message = null)
            try {
                val result = op(st)
                if (result is Draft) {
                    container.draft.value = result
                    reload(keepSelection = label.startsWith("Volume") || label.startsWith("Fade") || label.startsWith("Normalise") || label.startsWith("Silence"))
                    _state.value = _state.value.copy(message = "Done: ${st.lastChange ?: label}. Use undo or \"Compare with before\" to check it.")
                }
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(message = "Cancelled")
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = e.message ?: "That didn't work")
            } finally {
                _state.value = _state.value.copy(busy = null)
            }
        }
    }

    private fun progress(p: Float) { _state.value = _state.value.copy(progress = p) }
    private fun progress(step: String, p: Float) { _state.value = _state.value.copy(busy = step, progress = p) }

    fun cancel() { work?.cancel() }

    fun analyze() {
        val st = studio ?: return
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            _state.value = _state.value.copy(busy = "Checking the recording", progress = 0f, message = null)
            try {
                _state.value = _state.value.copy(report = st.analyze())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(message = e.message ?: "Couldn't analyse the audio")
            } finally {
                _state.value = _state.value.copy(busy = null)
            }
        }
    }

    fun clean(options: CleanOptions) = run("Cleaning") { it.clean(options, ::progress) }
    fun separate(what: Separation) = run(what.label) { it.separate(what, container.settings.value, ::progress) }
    fun cut() = range().let { (a, b) -> run("Cutting") { it.cut(a, b) } }
    fun crop() = range().let { (a, b) -> run("Keeping the selection") { it.crop(a, b) } }
    fun silence() = range().let { (a, b) -> run("Silence") { it.silence(a, b) } }
    fun fadeIn() = range().let { (a, b) -> run("Fade in") { it.fadeIn(a, b) } }
    fun fadeOut() = range().let { (a, b) -> run("Fade out") { it.fadeOut(a, b) } }
    fun gain(db: Float) = range().let { (a, b) -> run("Volume") { it.gain(a, b, db.toDouble()) } }
    fun normalize() = range().let { (a, b) -> run("Normalise") { it.normalize(a, b) } }
    fun tempo(percent: Float) = run("Changing tempo") { it.tempo(percent / 100.0) }
    fun pitch(semitones: Int) = run("Changing pitch") { it.pitch(semitones) }

    fun undo() { val st = studio ?: return; if (work?.isActive == true) return; container.draft.value = st.undo(); viewModelScope.launch { reload(keepSelection = false) } }
    fun redo() { val st = studio ?: return; if (work?.isActive == true) return; container.draft.value = st.redo(); viewModelScope.launch { reload(keepSelection = false) } }

    private suspend fun wav(): java.io.File? = studio?.exportWav(_state.value.name)

    fun share() = viewModelScope.launch {
        try { wav()?.let { Exporter(getApplication<Application>()).share(it, ExportFormat.WAV) } } catch (e: Exception) { _state.value = _state.value.copy(message = e.message) }
    }

    fun saveTo(uri: Uri) = viewModelScope.launch {
        try {
            wav()?.let { Exporter(getApplication<Application>()).saveTo(it, uri) }
            _state.value = _state.value.copy(message = "Saved")
        } catch (e: Exception) { _state.value = _state.value.copy(message = e.message ?: "Couldn't save") }
    }

    /** Keeps the current version as the draft and frees the others. */
    fun finish() {
        player?.pause()
        studio?.let { container.draft.value = it.current; it.discardHistory() }
    }

    override fun onCleared() {
        work?.cancel()
        player?.release()
    }
}

@Composable
fun StudioScreen(container: AppContainer, onDone: () -> Unit) {
    val vm: StudioViewModel = viewModel { StudioViewModel(container.app, container) }
    val s by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.stopPlayback() } }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri -> if (uri != null) vm.saveTo(uri) }
    val finish = { vm.finish(); onDone() }
    val idle = s.busy == null
    BackHandler { if (idle) finish() else vm.cancel() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Edit & clean audio", maxLines = 1) },
            navigationIcon = { IconButton(onClick = finish) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = vm::undo, enabled = idle && s.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = vm::redo, enabled = idle && s.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val width = Modifier.widthIn(max = 800.dp).fillMaxWidth()
            if (s.error != null) {
                Text(s.error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = onDone) { Text("Back") }
                return@Column
            }
            val dur = s.duration.toFloat().coerceAtLeast(0.01f)
            val selColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            val playColor = MaterialTheme.colorScheme.error
            Box(width.height(110.dp).drawWithContent {
                val x0 = size.width * s.selStart / dur
                val x1 = size.width * s.selEnd / dur
                drawRect(selColor, Offset(x0, 0f), Size(x1 - x0, size.height))
                drawContent()
                val px = (size.width * s.position / dur).toFloat()
                if (s.playing) drawLine(playColor, Offset(px, 0f), Offset(px, size.height), 3f)
            }) {
                Waveform(s.waveform, if (s.hearingBefore) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, Modifier.fillMaxSize())
            }
            RangeSlider(value = s.selStart..s.selEnd, onValueChange = { vm.setSelection(it.start, it.endInclusive) }, valueRange = 0f..dur, modifier = width, enabled = idle)
            Row(width, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Selection ${formatTime(s.selStart.toDouble())}–${formatTime(s.selEnd.toDouble())}", style = MaterialTheme.typography.bodySmall)
                FilledTonalIconButton(onClick = vm::togglePlay) { Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play") }
                FilterChip(selected = s.hearingBefore, onClick = vm::toggleBefore, enabled = s.canUndo, label = { Text("Compare with before") })
            }
            if (s.busy != null) {
                Text(s.busy!!, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { s.progress }, modifier = width)
                TextButton(onClick = vm::cancel) { Text("Cancel") }
            }
            s.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            CheckCard(s, idle, vm, width)
            CleanCard(idle, vm, width)
            SeparateCard(s, idle, vm, width)
            EditCard(s, idle, vm, width)

            if (s.history.isNotEmpty()) StudioCard("Changes", width) {
                s.history.forEachIndexed { i, h -> Text("${i + 1}. $h", style = MaterialTheme.typography.bodySmall) }
            }
            Row(width, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { saveLauncher.launch("${s.name}-edited.wav") }, enabled = idle) { Text("Save as WAV") }
                OutlinedButton(onClick = { vm.share() }, enabled = idle) { Text("Share") }
            }
            Button(onClick = finish, enabled = idle, modifier = width.height(52.dp)) { Text("Use this audio") }
            Text("Edits work on the 22 kHz mono copy PianoScribe listens to; your original file is never changed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StudioCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CheckCard(s: StudioViewModel.UiState, idle: Boolean, vm: StudioViewModel, modifier: Modifier) = StudioCard("Check the recording", modifier) {
    val r = s.report
    if (r == null) {
        Text("Looks for background noise, mains hum, room echo, clipping and low level.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = vm::analyze, enabled = idle) { Text("Check") }
    } else {
        r.findings().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        val forNotes = r.suggested()
        if (!forNotes.isEmpty) {
            Button(onClick = { vm.clean(forNotes) }, enabled = idle) { Text("Fix for transcription") }
            Text("Removes ${listOfNotNull("hum".takeIf { forNotes.removeHum }, "rumble".takeIf { forNotes.removeRumble }, "low level".takeIf { forNotes.normalize }).joinToString(", ")}: these help the note detection.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Nothing here gets in the way of note detection.", style = MaterialTheme.typography.bodySmall)
        }
        val listen = r.suggestedForListening()
        if (listen != forNotes && !listen.isEmpty) OutlinedButton(onClick = { vm.clean(listen) }, enabled = idle) { Text("Make it sound cleaner") }
    }
}

@Composable
private fun CleanCard(idle: Boolean, vm: StudioViewModel, modifier: Modifier) = StudioCard("Clean up", modifier) {
    var noise by remember { mutableFloatStateOf(0f) }
    var echo by remember { mutableFloatStateOf(0f) }
    var hum by remember { mutableStateOf(false) }
    var rumble by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(false) }
    LabeledSlider("Noise reduction", noise, "${Math.round(noise * 100)}%", 0f..1f, { noise = it })
    LabeledSlider("Echo reduction", echo, "${Math.round(echo * 100)}%", 0f..1f, { echo = it })
    SwitchRow("Remove mains hum (50/60 Hz)", hum) { hum = it }
    SwitchRow("Remove rumble (below 22 Hz)", rumble) { rumble = it }
    SwitchRow("Normalise level", level) { level = it }
    val options = CleanOptions(noise, echo, hum, rumble, level)
    Button(onClick = { vm.clean(options) }, enabled = idle && !options.isEmpty) { Text("Apply") }
    Text("Hum removal helps note detection. Noise and echo reduction make listening nicer, but the note model copes with noise and echo better than with what removing them changes, so use them for listening, or compare the notes with and without.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange)
    }
}

@Composable
private fun SeparateCard(s: StudioViewModel.UiState, idle: Boolean, vm: StudioViewModel, modifier: Modifier) = StudioCard("Separate", modifier) {
    var choice by remember { mutableStateOf(Separation.REMOVE_DRUMS) }
    Separation.entries.forEach { opt ->
        val enabled = !opt.needsStereo || s.stereo
        Row(
            Modifier.fillMaxWidth().selectable(selected = choice == opt, enabled = enabled, role = Role.RadioButton) { choice = opt },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = choice == opt, onClick = null, enabled = enabled)
            Text("  " + opt.label, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
    Text(
        when {
            choice.needsStereo -> "Works on stereo songs where the voice is in the middle and the instruments are spread left and right."
            choice.usesNotes -> "Finds the notes first, then keeps (or removes) just that part's harmonics. Takes as long as a conversion."
            else -> "Splits sustained tones from short hits."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!s.stereo) Text("Stereo options need a stereo file, before any other change.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { vm.separate(choice) }, enabled = idle && (!choice.needsStereo || s.stereo)) { Text("Separate") }
}

@Composable
private fun EditCard(s: StudioViewModel.UiState, idle: Boolean, vm: StudioViewModel, modifier: Modifier) = StudioCard("Edit the selection", modifier) {
    val whole = s.selEnd - s.selStart >= s.duration - 0.01
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::cut, enabled = idle && !whole) { Text("Cut") }
        OutlinedButton(onClick = vm::crop, enabled = idle && !whole) { Text("Keep only this") }
        OutlinedButton(onClick = vm::silence, enabled = idle) { Text("Silence") }
        OutlinedButton(onClick = vm::fadeIn, enabled = idle) { Text("Fade in") }
        OutlinedButton(onClick = vm::fadeOut, enabled = idle) { Text("Fade out") }
        OutlinedButton(onClick = vm::normalize, enabled = idle) { Text("Normalise") }
    }
    var gain by remember { mutableFloatStateOf(0f) }
    LabeledSlider("Volume", gain, "${if (gain > 0) "+" else ""}${Math.round(gain)} dB", -12f..12f, { gain = Math.round(it).toFloat() }, steps = 23)
    OutlinedButton(onClick = { vm.gain(gain) }, enabled = idle && gain != 0f) { Text("Apply volume") }
    Text("Whole recording", style = MaterialTheme.typography.titleSmall)
    var tempo by remember { mutableFloatStateOf(100f) }
    LabeledSlider("Tempo", tempo, "${Math.round(tempo)}%", 50f..200f, { tempo = (Math.round(it / 5) * 5).toFloat() }, help = "Slower or faster without changing the pitch.")
    OutlinedButton(onClick = { vm.tempo(tempo) }, enabled = idle && tempo != 100f) { Text("Apply tempo") }
    var semis by remember { mutableFloatStateOf(0f) }
    LabeledSlider("Pitch", semis, "${if (semis > 0) "+" else ""}${Math.round(semis)} semitones", -12f..12f, { semis = Math.round(it).toFloat() }, steps = 23,
        help = "Transposes the audio without changing its length.")
    OutlinedButton(onClick = { vm.pitch(Math.round(semis)) }, enabled = idle && semis != 0f) { Text("Apply pitch") }
}
