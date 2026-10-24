package com.pianoscribe.app.ui.result

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.data.Notation
import com.pianoscribe.app.data.ProjectEntity
import com.pianoscribe.app.export.ExportFormat
import com.pianoscribe.app.export.Exporter
import com.pianoscribe.app.ml.BasicPitchModel
import com.pianoscribe.app.playback.PlayMode
import com.pianoscribe.app.playback.PlaybackEngine
import com.pianoscribe.app.practice.MicPitchListener
import com.pianoscribe.core.analysis.Step
import com.pianoscribe.core.analysis.StepFilter
import com.pianoscribe.core.analysis.Steps
import com.pianoscribe.core.audio.Fft
import com.pianoscribe.core.edit.EditHistory
import com.pianoscribe.core.edit.NoteOps
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.rhythm.Quantizer
import com.pianoscribe.core.rhythm.TempoDetector
import com.pianoscribe.core.theory.Fingering
import com.pianoscribe.core.theory.KeyDetector
import com.pianoscribe.core.theory.Pitch
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10

/** Notes plus everything derived from them, recomputed after each edit. */
data class Derived(
    val notes: List<Note> = emptyList(),
    val steps: List<Step> = emptyList(),
    val fingers: Map<Long, Int> = emptyMap(),
    val key: KeyDetector.Key? = null,
    val sections: List<KeyDetector.Section> = emptyList(),
    val pitchLo: Int = 48,
    val pitchHi: Int = 84,
    val duration: Double = 1.0,
)

enum class PracticeSource { MIC, MIDI }
enum class PracticeStatus { IDLE, LISTENING, CORRECT, WRONG }

data class PracticeState(
    val enabled: Boolean = false,
    val source: PracticeSource = PracticeSource.MIC,
    val status: PracticeStatus = PracticeStatus.IDLE,
    val heard: String? = null,
    val cents: Int? = null,
    val message: String? = null,
)

/** Data for the spectrum view of one note. */
data class SpectrumData(
    val noteId: Long,
    /** dB magnitudes (0 = max) for 0..maxHz. */
    val spectrumDb: FloatArray,
    val maxHz: Double,
    val f0: Double,
    /** Mean Basic Pitch contour (264 bins, 3 per semitone from A0) over the note. */
    val contour: FloatArray?,
    val measuredCents: Float,
)

class ResultViewModel(app: Application, private val container: AppContainer, private val projectId: Long) : AndroidViewModel(app) {
    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val project: ProjectEntity? = null,
        val grid: TimeGrid = TimeGrid(),
        val derived: Derived = Derived(),
        val selectedId: Long? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val editMode: Boolean = false,
        val mode: PlayMode = PlayMode.NOTES,
        val speed: Double = 1.0,
        val loopA: Double? = null,
        val loop: ClosedFloatingPointRange<Double>? = null,
        val hasAudio: Boolean = false,
        val stepIndex: Int = 0,
        val filter: StepFilter = StepFilter.ALL,
        val focusedNoteId: Long? = null,
        val practice: PracticeState = PracticeState(),
        val spectrum: SpectrumData? = null,
        val spectrumLoading: Boolean = false,
        val message: String? = null,
        val usingSoundFont: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0.0)
    val position: StateFlow<Double> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    val settings = container.settings.state
    val midiInput = container.midiInput

    private var history = EditHistory<List<Note>>(emptyList())
    private val ids = NoteIdGenerator()
    private var engine: PlaybackEngine? = null
    private var saveJob: Job? = null
    private var deriveJob: Job? = null
    private var practiceJob: Job? = null
    private var model: BasicPitchModel? = null
    private val exporter = Exporter(app)

    init {
        viewModelScope.launch {
            val p = container.repository.load(projectId)
            if (p == null) { _state.value = UiState(loading = false, error = "Project not found"); return@launch }
            history.reset(p.notes)
            ids.advancePast(p.notes)
            val audio = container.repository.audioFile(p.entity).takeIf { p.entity.audioPath.isNotEmpty() && it.exists() }
            val preset = container.soundFonts.preset()
            container.soundFonts.lastError?.let { msg(it) }
            val e = PlaybackEngine(preset, audio).apply {
                duration = p.entity.durationSec
                setNotes(p.notes)
            }
            engine = e
            _state.update { it.copy(loading = false, project = p.entity, grid = p.grid, hasAudio = audio != null, usingSoundFont = preset != null) }
            derive(p.notes)
            launch { e.state.collect { s -> _position.value = s.position; _playing.value = s.playing } }
        }
        viewModelScope.launch {
            midiInput.events.collect { ev ->
                if (ev.on) engine?.liveNoteOn(ev.key, ev.velocity) else engine?.liveNoteOff(ev.key)
            }
        }
    }

    // ---------------------------------------------------------------- derived data

    private fun derive(notes: List<Note>) {
        deriveJob?.cancel()
        deriveJob = viewModelScope.launch {
            val d = withContext(Dispatchers.Default) {
                val key = KeyDetector.detect(notes)
                Derived(
                    notes = notes,
                    steps = Steps.build(notes),
                    fingers = Fingering.suggest(notes),
                    key = key,
                    sections = KeyDetector.sections(notes),
                    pitchLo = notes.minOfOrNull { it.pitch } ?: 48,
                    pitchHi = notes.maxOfOrNull { it.pitch } ?: 84,
                    duration = maxOf(_state.value.project?.durationSec ?: 0.0, notes.maxOfOrNull { it.end } ?: 0.0, 1.0),
                )
            }
            _state.update { s ->
                s.copy(
                    derived = d,
                    stepIndex = s.stepIndex.coerceIn(0, maxOf(0, d.steps.size - 1)),
                    canUndo = history.canUndo, canRedo = history.canRedo,
                    selectedId = s.selectedId?.takeIf { id -> notes.any { it.id == id } },
                    focusedNoteId = s.focusedNoteId?.takeIf { id -> notes.any { it.id == id } },
                )
            }
            engine?.duration = d.duration
        }
    }

    private fun commit(notes: List<Note>) {
        history.push(notes)
        applyNotes(history.current)
    }

    private fun applyNotes(notes: List<Note>) {
        engine?.setNotes(notes)
        derive(notes)
        scheduleSave()
    }

    /** True while edits are not yet written to the database. */
    @Volatile private var dirty = false

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val p = _state.value.project ?: return
        val g = _state.value.grid
        val notes = history.current
        dirty = false
        val updated = p.copy(bpm = g.bpm, beatsPerBar = g.beatsPerBar, beatUnit = g.beatUnit, gridOffset = g.offset, keyName = KeyDetector.detect(notes)?.name)
        container.repository.save(updated, notes)
        _state.update { it.copy(project = updated) }
    }

    private fun msg(text: String) = _state.update { it.copy(message = text) }
    fun messageShown() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- editing

    fun undo() { if (history.canUndo) applyNotes(history.undo()) }
    fun redo() { if (history.canRedo) applyNotes(history.redo()) }
    fun toggleEditMode() = _state.update { it.copy(editMode = !it.editMode) }

    fun select(id: Long?) {
        _state.update { it.copy(selectedId = id) }
        if (id != null) {
            val n = history.current.firstOrNull { it.id == id } ?: return
            if (!_playing.value) engine?.preview(listOf(n))
            val idx = _state.value.derived.steps.indexOfFirst { s -> s.notes.any { it.id == id } }
            if (idx >= 0) _state.update { it.copy(stepIndex = idx, focusedNoteId = id) }
        }
    }

    fun moveNote(id: Long, dt: Double, dPitch: Int) {
        if (dt == 0.0 && dPitch == 0) return
        val moved = NoteOps.move(history.current, id, dt, dPitch)
        commit(rehand(moved, id))
        history.current.firstOrNull { it.id == id }?.let { engine?.preview(listOf(it)) }
    }

    fun resizeNote(id: Long, newDuration: Double) = commit(NoteOps.resize(history.current, id, newDuration))

    fun deleteNote(id: Long) {
        commit(NoteOps.delete(history.current, id))
        if (_state.value.selectedId == id) _state.update { it.copy(selectedId = null) }
        msg("Note deleted")
    }

    fun addNote(pitch: Int, start: Double) {
        val g = _state.value.grid
        val split = _state.value.project?.splitPoint ?: Note.MIDDLE_C
        val n = Note(ids.next(), pitch.coerceIn(21, 108), start.coerceAtLeast(0.0), g.secondsPerBeat, 90, 1f,
            if (pitch >= split) Hand.RIGHT else Hand.LEFT, verified = true)
        commit(NoteOps.add(history.current, n))
        _state.update { it.copy(selectedId = n.id) }
        engine?.preview(listOf(n))
    }

    private fun rehand(notes: List<Note>, id: Long): List<Note> {
        val split = _state.value.project?.splitPoint ?: return notes
        return notes.map { if (it.id == id) it.copy(hand = if (it.pitch >= split) Hand.RIGHT else Hand.LEFT) else it }
    }

    fun transpose(semitones: Int) {
        if (semitones == 0) return
        commit(NoteOps.transpose(history.current, semitones))
        msg("Transposed ${if (semitones > 0) "+" else ""}$semitones semitones")
    }

    /** Sets a new tempo. With [stretch], note timings change so the music plays at the new tempo. */
    fun setTempo(bpm: Double, stretch: Boolean) {
        val g = _state.value.grid
        val newBpm = bpm.coerceIn(20.0, 300.0)
        if (stretch) {
            val notes = NoteOps.changeTempo(history.current, g.bpm, newBpm, g.offset)
            _state.update { it.copy(grid = g.copy(bpm = newBpm)) }
            val end = notes.maxOfOrNull { it.end } ?: 0.0
            engine?.duration = end
            commit(notes)
        } else {
            _state.update { it.copy(grid = g.copy(bpm = newBpm, offset = TempoDetector.phase(history.current.ifEmpty { listOf(Note(0, 60, 0.0, 1.0, 1, 1f)) }, 60 / newBpm))) }
            scheduleSave()
        }
    }

    fun setMeter(beatsPerBar: Int) {
        _state.update { it.copy(grid = it.grid.copy(beatsPerBar = beatsPerBar.coerceIn(2, 12))) }
        scheduleSave()
    }

    fun redetectTempo() {
        val g = TempoDetector.detect(history.current, _state.value.grid.beatsPerBar)
        _state.update { it.copy(grid = g) }
        scheduleSave()
        msg("Tempo ${Math.round(g.bpm)} BPM")
    }

    fun quantize(division: Int) {
        commit(Quantizer.quantize(history.current, _state.value.grid, division))
        _state.value.project?.let { p -> _state.update { it.copy(project = p.copy(quantizeDivision = division)) } }
        msg("Quantised to 1/$division notes")
    }

    fun setSplitPoint(pitch: Int) {
        val p = _state.value.project ?: return
        _state.update { it.copy(project = p.copy(splitPoint = pitch)) }
        commit(NoteOps.setHandBySplit(history.current, pitch))
        viewModelScope.launch { container.repository.updateMeta(p.copy(splitPoint = pitch)) }
    }

    fun rename(name: String) {
        val p = _state.value.project ?: return
        val n = name.trim().ifEmpty { return }
        _state.update { it.copy(project = p.copy(name = n)) }
        viewModelScope.launch { container.repository.updateMeta(p.copy(name = n)) }
    }

    // Fix-it actions for the focused analyser note.
    private fun focused(): Note? {
        val s = _state.value
        val id = s.focusedNoteId ?: s.derived.steps.getOrNull(s.stepIndex)?.top?.id ?: return null
        return history.current.firstOrNull { it.id == id }
    }

    fun fixPitch(delta: Int) {
        val n = focused() ?: return
        commit(rehand(NoteOps.move(history.current, n.id, 0.0, delta), n.id))
        history.current.firstOrNull { it.id == n.id }?.let { engine?.preview(listOf(it)) }
    }

    fun fixSplit() {
        val n = focused() ?: return
        commit(NoteOps.split(history.current, n.id, n.start + n.duration / 2, ids))
    }

    fun fixMergeNext() {
        val n = focused() ?: return
        val before = history.current.size
        val merged = NoteOps.mergeWithNext(history.current, n.id)
        if (merged.size == before) msg("No following note in the same hand to merge with") else commit(merged)
    }

    fun fixDelete() { focused()?.let { deleteNote(it.id) } }

    fun fixToggleVerified() {
        val n = focused() ?: return
        commit(NoteOps.replace(history.current, n.copy(verified = !n.verified, confidence = if (!n.verified) maxOf(n.confidence, 1f) else n.confidence)))
    }

    // ---------------------------------------------------------------- playback

    fun togglePlay() { stopPractice(); engine?.toggle() }
    fun seek(t: Double) { engine?.seek(t); _position.value = t }
    fun stop() { engine?.pause(); seek(_state.value.loop?.start ?: 0.0) }

    fun setMode(m: PlayMode) {
        if (m != PlayMode.NOTES && !_state.value.hasAudio) { msg("The original audio isn't available for this project"); return }
        engine?.mode = m
        _state.update { it.copy(mode = m) }
    }

    fun setSpeed(x: Double) {
        engine?.speed = x
        _state.update { it.copy(speed = x) }
    }

    /** A/B loop: first tap marks A, second marks B and starts looping, third clears. */
    fun loopButton() {
        val s = _state.value
        val pos = _position.value
        when {
            s.loop != null -> { engine?.loop = null; _state.update { it.copy(loop = null, loopA = null) } }
            s.loopA == null -> { _state.update { it.copy(loopA = pos) }; msg("Loop start set. Tap again at the loop end.") }
            else -> {
                val a = minOf(s.loopA, pos); val b = maxOf(s.loopA, pos)
                if (b - a < 0.25) { msg("Loop is too short"); return }
                engine?.loop = a..b
                _state.update { it.copy(loop = a..b, loopA = null) }
            }
        }
    }

    /** Loops the bar(s) around the selected note or the current step. */
    fun loopSelection() {
        val s = _state.value
        val n = s.selectedId?.let { id -> history.current.firstOrNull { it.id == id } } ?: s.derived.steps.getOrNull(s.stepIndex)?.top ?: return
        val g = s.grid
        val barLen = g.secondsPerBeat * g.beatsPerBar
        val bar = Math.floor((n.start - g.offset) / barLen)
        val a = maxOf(0.0, g.offset + bar * barLen)
        val b = maxOf(a + barLen, g.offset + Math.ceil((n.end - g.offset) / barLen) * barLen)
        engine?.loop = a..b
        _state.update { it.copy(loop = a..b, loopA = null) }
        seek(a)
    }

    // ---------------------------------------------------------------- analyser

    fun setFilter(f: StepFilter) = _state.update { it.copy(filter = f) }

    fun step(direction: Int) {
        val s = _state.value
        val steps = s.derived.steps
        if (steps.isEmpty()) return
        val next = Steps.find(steps, s.stepIndex, direction, s.filter)
        if (next == null) { msg(if (direction > 0) "No more matching notes" else "This is the first matching note"); return }
        goToStep(next)
    }

    fun goToStep(index: Int, play: Boolean = true) {
        val steps = _state.value.derived.steps
        val step = steps.getOrNull(index) ?: return
        engine?.pause()
        engine?.seek(step.start)
        _position.value = step.start
        _state.update { it.copy(stepIndex = index, focusedNoteId = step.top.id, selectedId = step.top.id, spectrum = null, practice = it.practice.copy(status = if (it.practice.enabled) PracticeStatus.LISTENING else PracticeStatus.IDLE, heard = null, cents = null)) }
        if (play) engine?.preview(step.notes)
        if (_state.value.practice.enabled) restartPracticeListening()
    }

    /** Tapping an on-screen key sounds it. */
    fun midiPreview(key: Int) {
        engine?.preview(listOf(Note(0, key, 0.0, 0.6, 90, 1f)))
    }

    fun playCurrentStep() {
        val s = _state.value
        s.derived.steps.getOrNull(s.stepIndex)?.let { engine?.preview(it.notes) }
    }

    fun focusNote(id: Long) {
        _state.update { it.copy(focusedNoteId = id, selectedId = id, spectrum = null) }
        history.current.firstOrNull { it.id == id }?.let { engine?.preview(listOf(it)) }
    }

    fun loadSpectrum() {
        val n = focused() ?: return
        val p = _state.value.project ?: return
        val file = container.repository.audioFile(p)
        if (!file.exists()) { msg("The original audio isn't available"); return }
        _state.update { it.copy(spectrumLoading = true) }
        viewModelScope.launch {
            val data = withContext(Dispatchers.Default) { computeSpectrum(n, file) }
            _state.update { it.copy(spectrum = data, spectrumLoading = false) }
        }
    }

    private fun computeSpectrum(n: Note, file: File): SpectrumData {
        val sr = BasicPitch.SAMPLE_RATE
        val from = ((n.start + 0.02) * sr).toInt()
        val len = (minOf(n.duration, 0.6) * sr).toInt().coerceAtLeast(1024)
        val buf = FloatArray(len)
        PcmFile.read(file, from, buf, 0, len)
        val size = 16384
        val mag = Fft.magnitudeSpectrum(buf, 0, len, size)
        val f0 = Pitch.frequency(n.pitch)
        val maxHz = (f0 * 8).coerceIn(500.0, sr / 2.0)
        val bins = (maxHz / sr * size).toInt().coerceAtMost(mag.size - 1)
        val peak = (1..bins).maxOfOrNull { mag[it] }?.coerceAtLeast(1e-9) ?: 1.0
        val db = FloatArray(bins) { (20 * log10(mag[it + 1] / peak + 1e-9)).toFloat().coerceAtLeast(-80f) }
        // Contour: run the model on a 2 s window starting 0.5 s before the note.
        val contour = runCatching {
            val m = model ?: BasicPitchModel(getApplication()).also { model = it }
            val window = FloatArray(BasicPitch.AUDIO_N_SAMPLES)
            val startSample = (n.start * sr).toInt() - sr / 2
            val total = PcmFile.length(file)
            RandomAccessFile(file, "r").use { raf ->
                val off = if (startSample < 0) -startSample else 0
                PcmFile.read(raf, maxOf(0, startSample), window, off, minOf(window.size - off, total - maxOf(0, startSample)))
            }
            val out = m.run(window)
            val f0Frame = (0.5 * BasicPitch.ANNOTATIONS_FPS).toInt()
            val f1Frame = minOf(BasicPitch.ANNOT_N_FRAMES - 1, f0Frame + maxOf(2, (n.duration * BasicPitch.ANNOTATIONS_FPS).toInt()))
            val c = FloatArray(BasicPitch.CONTOUR_BINS)
            for (f in f0Frame until f1Frame) for (k in c.indices) c[k] += out.contour[f * BasicPitch.CONTOUR_BINS + k]
            for (k in c.indices) c[k] /= (f1Frame - f0Frame)
            c
        }.getOrNull()
        return SpectrumData(n.id, db, maxHz, f0, contour, n.cents)
    }

    // ---------------------------------------------------------------- practice

    fun setPracticeEnabled(on: Boolean) {
        if (!on) { stopPractice(); _state.update { it.copy(practice = it.practice.copy(enabled = false, status = PracticeStatus.IDLE)) }; return }
        engine?.pause()
        _state.update { it.copy(practice = it.practice.copy(enabled = true, status = PracticeStatus.LISTENING, heard = null, cents = null, message = null)) }
        playCurrentStep()
        restartPracticeListening()
    }

    fun setPracticeSource(src: PracticeSource) {
        _state.update { it.copy(practice = it.practice.copy(source = src)) }
        if (_state.value.practice.enabled) restartPracticeListening()
    }

    private fun stopPractice() { practiceJob?.cancel(); practiceJob = null }

    private fun restartPracticeListening() {
        stopPractice()
        val s = _state.value
        val step = s.derived.steps.getOrNull(s.stepIndex) ?: return
        val targets = step.notes.map { it.pitch }.toSet()
        practiceJob = viewModelScope.launch {
            when (s.practice.source) {
                PracticeSource.MIDI -> {
                    if (midiInput.connected.value == null) {
                        _state.update { it.copy(practice = it.practice.copy(message = "Connect a USB or Bluetooth MIDI keyboard first")) }
                    }
                    midiInput.held.collect { held ->
                        if (held.isEmpty()) return@collect
                        val wrong = held - targets
                        when {
                            held.containsAll(targets) && wrong.isEmpty() -> onCorrect(held.joinToString(" ") { Pitch.name(it) }, 0)
                            wrong.isNotEmpty() -> {
                                val w = wrong.first()
                                val nearest = targets.minBy { abs(it - w) }
                                setPractice(PracticeStatus.WRONG, Pitch.name(w), (w - nearest) * 100)
                            }
                        }
                    }
                }
                PracticeSource.MIC -> {
                    // Let the reference note finish so the microphone doesn't hear the phone itself.
                    delay(minOf(1500L, (step.notes.maxOf { it.duration } * 1000).toLong() + 400))
                    var streak = 0
                    var lastMidi = -1
                    MicPitchListener.readings().collect { r ->
                        if (r == null || r.probability < 0.75) { streak = 0; return@collect }
                        val m = Pitch.nearestMidi(r.frequency)
                        val cents = Pitch.cents(r.frequency, m).toInt()
                        streak = if (m == lastMidi) streak + 1 else 1
                        lastMidi = m
                        if (streak < 3) return@collect
                        // A microphone hears one pitch; for chords the top or any chord note counts.
                        if (m in targets) onCorrect(Pitch.name(m), cents)
                        else if (streak >= 4) {
                            val nearest = targets.minBy { abs(it - m) }
                            setPractice(PracticeStatus.WRONG, Pitch.name(m), (m - nearest) * 100 + cents)
                        }
                    }
                }
            }
        }
    }

    private fun setPractice(status: PracticeStatus, heard: String?, cents: Int?) =
        _state.update { it.copy(practice = it.practice.copy(status = status, heard = heard, cents = cents, message = null)) }

    private suspend fun onCorrect(heard: String, cents: Int) {
        if (_state.value.practice.status == PracticeStatus.CORRECT) return
        setPractice(PracticeStatus.CORRECT, heard, cents)
        delay(800)
        val s = _state.value
        val next = Steps.find(s.derived.steps, s.stepIndex, 1, s.filter)
        if (next != null) goToStep(next) else {
            _state.update { it.copy(practice = it.practice.copy(status = PracticeStatus.IDLE, message = "Finished! You played every note.")) }
            stopPractice()
        }
    }

    // ---------------------------------------------------------------- export

    private fun exportInput(): Exporter.Input {
        val s = _state.value
        return Exporter.Input(s.project?.name ?: "PianoScribe", history.current, s.grid, s.derived.key, settings.value.saPitchClass)
    }

    private fun exportable(): Boolean {
        if (history.current.isNotEmpty()) return true
        msg("There are no notes to export yet")
        return false
    }

    fun share(format: ExportFormat) {
        if (!exportable()) return
        viewModelScope.launch {
            runCatching { exporter.share(exporter.export(format, exportInput()), format) }
                .onFailure { msg("Export failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** One-tap save into Downloads/PianoScribe. On Android 8-9 calls [onUnsupported] so the caller can fall back to "Save as". */
    fun saveToDownloads(format: ExportFormat, onUnsupported: () -> Unit) {
        if (!exportable()) return
        viewModelScope.launch {
            runCatching { exporter.saveToDownloads(exporter.export(format, exportInput()), format) }
                .onSuccess { where -> if (where == null) onUnsupported() else msg("Saved to $where") }
                .onFailure { msg("Save failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun open(format: ExportFormat) {
        if (!exportable()) return
        viewModelScope.launch {
            runCatching { exporter.open(exporter.export(format, exportInput()), format) }
                .onSuccess { opened -> if (!opened) msg("No app on this phone can open ${format.ext.uppercase()} files. Use Save or Share instead.") }
                .onFailure { msg("Export failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun saveTo(format: ExportFormat, uri: Uri) {
        if (!exportable()) return
        viewModelScope.launch {
            runCatching { exporter.saveTo(exporter.export(format, exportInput()), uri) }
                .onSuccess { msg("Saved ${format.ext.uppercase()} file") }
                .onFailure { msg("Save failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun setNotation(n: Notation) = container.settings.update { it.copy(notation = n) }

    override fun onCleared() {
        stopPractice()
        engine?.release()
        model?.close()
        // viewModelScope is already cancelled here, so flush pending edits synchronously.
        if (dirty) kotlinx.coroutines.runBlocking { runCatching { saveNow() } }
    }
}
