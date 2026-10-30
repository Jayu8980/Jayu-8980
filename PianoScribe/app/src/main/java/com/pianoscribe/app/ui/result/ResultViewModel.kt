package com.pianoscribe.app.ui.result

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.compare.CompareEngine
import com.pianoscribe.app.compare.CompareReport
import com.pianoscribe.app.compare.FixResult
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
import com.pianoscribe.core.perform.EasyVersion
import com.pianoscribe.core.arrange.Arranger
import com.pianoscribe.core.arrange.SongRenderer
import com.pianoscribe.core.arrange.Style
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.Performer
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.rhythm.Quantizer
import com.pianoscribe.core.rhythm.TempoDetector
import com.pianoscribe.core.synth.Sf2Preset
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

/** Which hand is shown, played and exported, for practising one hand at a time. */
enum class Hands(val label: String) { BOTH("Both hands"), RIGHT("Right hand"), LEFT("Left hand") }

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

/** State of the Compare tab. */
/** The "Make a song" dialog: the chosen style and the song made from the melody. */
data class SongUi(
    val style: Style = Style.POP,
    val making: Boolean = false,
    val progress: Float = 0f,
    /** The chords per bar, once a song is made ("C – G – Am – F"). */
    val progression: String? = null,
    val playing: Boolean = false,
    val error: String? = null,
)

/** How hard the shown version is: the simplified versions, or every note as transcribed. */
enum class Difficulty(val label: String, val help: String) {
    BEGINNER("Beginner", "The tune alone, one note at a time, for the right hand, in an easy range."),
    INTERMEDIATE("Intermediate", "The tune as played, and a simpler left hand: bass and one chord note per beat."),
    ADVANCED("Advanced", "Every note as transcribed."),
}

data class CompareUi(
    val running: Boolean = false,
    val step: String = "",
    val progress: Float = 0f,
    val report: CompareReport? = null,
    /** The notes the report describes; when the notes change the report is out of date. */
    val reportNotes: List<Note>? = null,
    val lastFix: FixResult? = null,
    val error: String? = null,
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
        val compare: CompareUi = CompareUi(),
        /** Which version is shown, played and exported (Advanced = every note as transcribed). */
        val difficulty: Difficulty = Difficulty.ADVANCED,
        /** Semitones transposed in this session (shown in the player bar). */
        val transposed: Int = 0,
        val metronome: Boolean = false,
        val hands: Hands = Hands.BOTH,
        /** A key signature chosen by the user (null: detected from the notes). */
        val keyOverride: KeyDetector.Key? = null,
    ) {
        /** A simplified version is shown: the notes as transcribed can't be edited from it. */
        val easy: Boolean get() = difficulty != Difficulty.ADVANCED
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    // A chosen key signature is kept per project.
    private val keyPrefs = app.getSharedPreferences("keys", android.content.Context.MODE_PRIVATE)

    private val _song = MutableStateFlow(SongUi())
    val song: StateFlow<SongUi> = _song.asStateFlow()
    private var songJob: Job? = null
    private var songWav: File? = null
    private var songMid: File? = null
    private var songPlayer: android.media.MediaPlayer? = null

    private val _position = MutableStateFlow(0.0)
    val position: StateFlow<Double> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    val settings = container.settings.state
    val midiInput = container.midiInput

    private var history = EditHistory<List<Note>>(emptyList())
    private val ids = NoteIdGenerator()
    private var engine: PlaybackEngine? = null
    private var compareEngine: CompareEngine? = null
    private var compareJob: Job? = null
    private var preset: Sf2Preset? = null
    private var performJob: Job? = null
    /** The notes as performed on the current instrument (what playback and "performed" exports use). */
    @Volatile private var performance: List<PlayedNote> = emptyList()
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
            val instrument = settings.value.instrument
            preset = container.soundFonts.preset(instrument)
            container.soundFonts.lastError?.let { msg(it) }
            val e = PlaybackEngine(preset, audio, instrument).apply {
                duration = p.entity.durationSec
                setInstrument(instrument, preset, settings.value.humanFeel)
            }
            engine = e
            _state.update { it.copy(loading = false, project = p.entity, grid = p.grid, hasAudio = audio != null, usingSoundFont = preset != null, keyOverride = savedKey()) }
            derive(p.notes)
            perform()
            launch { e.state.collect { s -> _position.value = s.position; _playing.value = s.playing } }
        }
        viewModelScope.launch {
            midiInput.events.collect { ev ->
                if (ev.on) engine?.liveNoteOn(ev.key, ev.velocity) else engine?.liveNoteOff(ev.key)
            }
        }
    }

    // ---------------------------------------------------------------- derived data

    /** The notes shown, played and exported at the chosen difficulty ([notes] as edited for Advanced). */
    private fun shownNotes(notes: List<Note> = history.current): List<Note> {
        val level = when (_state.value.difficulty) {
            Difficulty.BEGINNER -> EasyVersion.make(notes, _state.value.grid)
            Difficulty.INTERMEDIATE -> EasyVersion.intermediate(notes, _state.value.grid, KeyDetector.detect(notes))
            Difficulty.ADVANCED -> notes
        }
        return when (_state.value.hands) {
            Hands.BOTH -> level
            Hands.RIGHT -> level.filter { it.hand == Hand.RIGHT }
            Hands.LEFT -> level.filter { it.hand == Hand.LEFT }
        }
    }

    private fun savedKey(): KeyDetector.Key? = keyPrefs.getString("p$projectId", null)?.split(":")?.let {
        runCatching { KeyDetector.Key(it[0].toInt(), it[1] == "m", 1.0) }.getOrNull()
    }

    /** Practise one hand: only its notes are shown, played and exported. */
    fun setHands(h: Hands) {
        if (h == _state.value.hands) return
        _state.update { it.copy(hands = h, selectedId = null, focusedNoteId = null) }
        derive(history.current)
        perform()
    }

    /**
     * Makes a piano cover (the tune in the right hand, a left-hand [pattern] on the song's chords)
     * as a new project, and hands its id to [onDone].
     */
    fun makePianoCover(pattern: com.pianoscribe.core.arrange.PianoCover.Pattern, onDone: (Long) -> Unit) {
        val p = _state.value.project ?: return
        if (!exportable()) return
        viewModelScope.launch {
            val s = _state.value
            val cover = withContext(Dispatchers.Default) {
                com.pianoscribe.core.arrange.PianoCover.make(history.current, s.grid, s.derived.key, pattern)
            }
            if (cover.isEmpty()) { msg("No notes to make a cover from"); return@launch }
            val now = System.currentTimeMillis()
            val repo = container.repository
            val id = repo.create(p.copy(
                id = 0, name = "${p.name} (piano cover)", createdAt = now, updatedAt = now, audioPath = "",
                durationSec = maxOf(p.durationSec, cover.maxOf { it.end }), quantizeDivision = 0,
                noteCount = cover.size, source = "Piano cover: ${pattern.label.lowercase()}",
            ))
            repo.save(repo.load(id)!!.entity, cover)
            onDone(id)
        }
    }

    /** Sets the key signature (spelling, score, exports); null goes back to the detected key. */
    fun setKey(key: KeyDetector.Key?) {
        if (key == null) keyPrefs.edit().remove("p$projectId").apply()
        else keyPrefs.edit().putString("p$projectId", "${key.tonicPc}:${if (key.minor) "m" else "M"}").apply()
        _state.update { it.copy(keyOverride = key) }
        derive(history.current)
        scheduleSave()
    }

    private fun derive(edited: List<Note>) {
        deriveJob?.cancel()
        deriveJob = viewModelScope.launch {
            val d = withContext(Dispatchers.Default) {
                val notes = shownNotes(edited)
                // The key comes from all the notes, also in the easy version.
                val key = _state.value.keyOverride ?: KeyDetector.detect(edited)
                Derived(
                    notes = notes,
                    steps = Steps.build(notes),
                    fingers = Fingering.suggest(notes),
                    key = key,
                    sections = KeyDetector.sections(edited),
                    pitchLo = notes.minOfOrNull { it.pitch } ?: 48,
                    pitchHi = notes.maxOfOrNull { it.pitch } ?: 84,
                    duration = maxOf(_state.value.project?.durationSec ?: 0.0, notes.maxOfOrNull { it.end } ?: 0.0, 1.0),
                )
            }
            val notes = d.notes
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
        perform()
        derive(notes)
        scheduleSave()
    }

    /**
     * Rebuilds the performed part from the current notes, instrument and human feel. The final
     * slow-down is left out in A/B mode so the part stays in time with the original recording.
     */
    private fun perform(debounceMs: Long = 0) {
        performJob?.cancel()
        performJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val s = _state.value
            val cfg = settings.value
            val edited = history.current
            val part = withContext(Dispatchers.Default) {
                Performer.perform(shownNotes(edited), s.grid, cfg.instrument, Performer.Options(cfg.humanFeel, seed = projectId, tempoChanges = s.mode != PlayMode.BOTH))
            }
            performance = part
            engine?.setNotes(part)
        }
    }

    fun setInstrument(instrument: Instrument) {
        container.settings.update { it.copy(instrument = instrument) }
        viewModelScope.launch {
            val p = container.soundFonts.preset(instrument)
            preset = p
            engine?.setInstrument(instrument, p, settings.value.humanFeel)
            _state.update { it.copy(usingSoundFont = p != null) }
            perform()
            if (instrument.monophonic) msg("${instrument.label} plays one note at a time, so it plays the melody.")
            else if (instrument.maxNotes < 32) msg("${instrument.label} plays up to ${instrument.maxNotes} notes at once.")
        }
    }

    fun setHumanFeel(feel: Float) {
        val f = feel.coerceIn(0f, 1f)
        container.settings.update { it.copy(humanFeel = f) }
        engine?.setInstrument(settings.value.instrument, preset, f)
        perform(debounceMs = 150)
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
        val updated = p.copy(bpm = g.bpm, beatsPerBar = g.beatsPerBar, beatUnit = g.beatUnit, gridOffset = g.offset, keyName = (_state.value.keyOverride ?: KeyDetector.detect(notes))?.name)
        container.repository.save(updated, notes)
        _state.update { it.copy(project = updated) }
    }

    private fun msg(text: String) = _state.update { it.copy(message = text) }
    fun messageShown() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- editing

    fun undo() { if (history.canUndo) applyNotes(history.undo()) }
    fun redo() { if (history.canRedo) applyNotes(history.redo()) }
    fun toggleEditMode() { if (editable()) _state.update { it.copy(editMode = !it.editMode) } }

    /** Shows, plays and exports the [d] version of the song. */
    fun setDifficulty(d: Difficulty) {
        if (d == _state.value.difficulty) return
        _state.update { it.copy(difficulty = d, editMode = false, selectedId = null, focusedNoteId = null) }
        derive(history.current)
        perform()
        if (d != Difficulty.ADVANCED) msg("${d.label}: ${d.help}")
    }

    /** Edits change the full transcription, so they wait for the Advanced (every note) version. */
    private fun editable(): Boolean {
        if (!_state.value.easy) return true
        msg("Switch to Advanced to edit the notes")
        return false
    }

    fun setMetronome(on: Boolean) {
        _state.update { it.copy(metronome = on) }
        engine?.metronome = if (on) _state.value.grid else null
    }

    /** The grid changed: replay, and rebuild the simplified versions, which follow the beat. */
    private fun gridChanged() {
        if (_state.value.metronome) engine?.metronome = _state.value.grid
        perform()
        if (_state.value.easy) derive(history.current)
        scheduleSave()
    }

    fun select(id: Long?) {
        _state.update { it.copy(selectedId = id) }
        if (id != null) {
            val n = _state.value.derived.notes.firstOrNull { it.id == id } ?: return
            if (!_playing.value) engine?.preview(listOf(n))
            val idx = _state.value.derived.steps.indexOfFirst { s -> s.notes.any { it.id == id } }
            if (idx >= 0) _state.update { it.copy(stepIndex = idx, focusedNoteId = id) }
        }
    }

    fun moveNote(id: Long, dt: Double, dPitch: Int) {
        if (dt == 0.0 && dPitch == 0 || !editable()) return
        val moved = NoteOps.move(history.current, id, dt, dPitch)
        commit(rehand(moved, id))
        history.current.firstOrNull { it.id == id }?.let { engine?.preview(listOf(it)) }
    }

    fun resizeNote(id: Long, newDuration: Double) { if (editable()) commit(NoteOps.resize(history.current, id, newDuration)) }

    fun deleteNote(id: Long) {
        if (!editable()) return
        commit(NoteOps.delete(history.current, id))
        if (_state.value.selectedId == id) _state.update { it.copy(selectedId = null) }
        msg("Note deleted")
    }

    fun addNote(pitch: Int, start: Double) {
        if (!editable()) return
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
        _state.update { it.copy(transposed = it.transposed + semitones) }
        msg("Transposed ${if (semitones > 0) "+" else ""}$semitones semitones")
    }

    /** Sets a new tempo. With [stretch], note timings change so the music plays at the new tempo. */
    fun setTempo(bpm: Double, stretch: Boolean) {
        val g = _state.value.grid
        val newBpm = bpm.coerceIn(20.0, 300.0)
        if (stretch) {
            val notes = NoteOps.changeTempo(history.current, g.bpm, newBpm, g.offset)
            _state.update { it.copy(grid = g.copy(bpm = newBpm)) }
            if (_state.value.metronome) engine?.metronome = _state.value.grid
            val end = notes.maxOfOrNull { it.end } ?: 0.0
            engine?.duration = end
            commit(notes)
        } else {
            _state.update { it.copy(grid = g.copy(bpm = newBpm, offset = TempoDetector.phase(history.current.ifEmpty { listOf(Note(0, 60, 0.0, 1.0, 1, 1f)) }, 60 / newBpm))) }
            gridChanged()
        }
    }

    fun setMeter(beatsPerBar: Int) {
        _state.update { it.copy(grid = it.grid.copy(beatsPerBar = beatsPerBar.coerceIn(2, 12))) }
        gridChanged()
    }

    fun redetectTempo() {
        val g = TempoDetector.detect(history.current, _state.value.grid.beatsPerBar)
        _state.update { it.copy(grid = g) }
        gridChanged()
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
    private fun focused(): Note? = if (editable()) focusedNote() else null

    /** The focused note as transcribed (in the easy version too, where it may be shown moved). */
    private fun focusedNote(): Note? {
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

    fun togglePlay() { stopPractice(); stopSong(); engine?.toggle() }
    fun seek(t: Double) { engine?.seek(t); _position.value = t }
    fun stop() { engine?.pause(); seek(_state.value.loop?.start ?: 0.0) }

    fun setMode(m: PlayMode) {
        if (m != PlayMode.NOTES && !_state.value.hasAudio) { msg("The original audio isn't available for this project"); return }
        val wasBoth = _state.value.mode == PlayMode.BOTH
        engine?.mode = m
        _state.update { it.copy(mode = m) }
        if (wasBoth != (m == PlayMode.BOTH)) perform()
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
        _state.value.derived.notes.firstOrNull { it.id == id }?.let { engine?.preview(listOf(it)) }
    }

    fun loadSpectrum() {
        val n = focusedNote() ?: return
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

    // ---------------------------------------------------------------- compare with the recording

    /** True when the current notes are not the ones the last comparison looked at. */
    val compareIsStale: Boolean get() = _state.value.compare.reportNotes.let { it != null && it !== history.current }

    private fun compareEngine(): CompareEngine? {
        compareEngine?.let { return it }
        val p = _state.value.project ?: return null
        val file = container.repository.audioFile(p)
        if (p.audioPath.isEmpty() || !file.exists()) return null
        return CompareEngine(getApplication(), file, p.source.contains("full song")) { container.soundFonts.preset(it) }.also { compareEngine = it }
    }

    private fun setCompare(transform: (CompareUi) -> CompareUi) = _state.update { it.copy(compare = transform(it.compare)) }

    fun runCompare() {
        if (!editable()) return
        val ce = compareEngine() ?: run { msg("The original recording isn't available for this project"); return }
        if (history.current.isEmpty()) { msg("There are no notes to compare yet"); return }
        engine?.pause()
        compareJob?.cancel()
        val notes = history.current
        compareJob = viewModelScope.launch {
            setCompare { it.copy(running = true, step = "Starting", progress = 0f, error = null, lastFix = null) }
            try {
                val report = ce.compare(notes) { step, p -> setCompare { it.copy(step = step, progress = p) } }
                setCompare { it.copy(running = false, report = report, reportNotes = notes) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                setCompare { it.copy(running = false) }
                throw e
            } catch (e: Throwable) {
                setCompare { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun runAutoFix() {
        if (!editable()) return
        val ce = compareEngine() ?: return
        val c = _state.value.compare
        val before = c.report ?: return
        if (c.reportNotes !== history.current) { runCompare(); msg("The notes changed, so they were compared again first. Tap auto-fix once more."); return }
        engine?.pause()
        compareJob?.cancel()
        val notes = history.current
        val split = _state.value.project?.splitPoint ?: Note.MIDDLE_C
        compareJob = viewModelScope.launch {
            setCompare { it.copy(running = true, step = "Fixing notes", progress = 0f, error = null) }
            try {
                val result = ce.autoFix(notes, before, ids, split) { step, p -> setCompare { it.copy(step = step, progress = p) } }
                if (result.accepted) {
                    commit(result.notes)
                    setCompare { it.copy(running = false, report = result.after, reportNotes = history.current, lastFix = result) }
                    msg("Fixed: ${result.changes.removed} removed, ${result.changes.added} added, ${result.changes.merged} joined. Undo to go back.")
                } else {
                    setCompare { it.copy(running = false, lastFix = result) }
                    msg("No change made the MIDI sound closer to the recording, so nothing was changed.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                setCompare { it.copy(running = false) }
                throw e
            } catch (e: Throwable) {
                setCompare { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun cancelCompare() { compareJob?.cancel() }

    // ---------------------------------------------------------------- export

    private fun exportInput(): Exporter.Input {
        val s = _state.value
        val cfg = settings.value
        val notes = shownNotes()
        // Exports always include the final slow-down; only A/B playback leaves it out.
        val part = if (s.mode == PlayMode.BOTH) {
            Performer.perform(notes, s.grid, cfg.instrument, Performer.Options(cfg.humanFeel, seed = projectId))
        } else performance
        return Exporter.Input(
            exportName(), notes, s.grid, s.derived.key, cfg.saPitchClass,
            performance = part, instrument = cfg.instrument, preset = preset, expression = cfg.humanFeel,
        )
    }

    /** The project name, marked when the easy version is what gets exported. */
    fun exportName(): String {
        val s = _state.value
        val name = s.project?.name ?: "PianoScribe"
        val level = if (s.easy) " (${s.difficulty.label.lowercase()})" else ""
        val hands = if (s.hands != Hands.BOTH) " (${s.hands.label.lowercase()})" else ""
        return name + level + hands
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

    // ---------------------------------------------------------------- make a song

    fun setSongStyle(style: Style) {
        if (style == _song.value.style) return
        stopSong()
        _song.update { SongUi(style = style) }
    }

    /**
     * Makes a song from the tune of the notes (one note at a time, as played): chords that fit it,
     * bass and drums in the chosen style, rendered to WAV for listening and kept as MIDI.
     */
    fun makeSong() {
        stopSong()
        engine?.pause()
        songJob?.cancel()
        val style = _song.value.style
        val notes = history.current
        val s = _state.value
        val instrument = settings.value.instrument
        songJob = viewModelScope.launch {
            _song.update { it.copy(making = true, progress = 0f, progression = null, error = null) }
            try {
                val song = withContext(Dispatchers.Default) {
                    Arranger.arrange(EasyVersion.singleLine(notes), s.grid, s.derived.key, style, instrument)
                }
                if (song.bars.isEmpty()) error("There is no melody to make a song from")
                val presets = (listOf(Instrument.PIANO, style.chordInstrument, instrument)).distinct().associateWith { container.soundFonts.preset(it) }
                val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                val base = Exporter.fileName(exportName(), ExportFormat.MIDI).removeSuffix(".mid") + "-song-" + style.name.lowercase()
                val wav = File(dir, "$base.wav")
                val mid = File(dir, "$base.mid")
                withContext(Dispatchers.IO) {
                    wav.outputStream().buffered().use { out ->
                        SongRenderer.renderWav(song, { presets[it] }, Exporter.WAV_RATE, out) { p -> _song.update { it.copy(progress = p) } }
                    }
                    mid.writeBytes(SongRenderer.midi(song, "${exportName()} (${style.label})"))
                }
                songWav = wav; songMid = mid
                _song.update { it.copy(making = false, progress = 1f, progression = song.progression()) }
                playSong()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _song.update { it.copy(making = false) }
                throw e
            } catch (e: Throwable) {
                _song.update { it.copy(making = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun playSong() {
        val f = songWav ?: return
        stopSong()
        engine?.pause()
        runCatching {
            songPlayer = android.media.MediaPlayer().apply {
                setDataSource(f.path)
                setOnCompletionListener { _song.update { s -> s.copy(playing = false) } }
                prepare()
                start()
            }
            _song.update { it.copy(playing = true) }
        }.onFailure { msg("Couldn't play the song: ${it.message}") }
    }

    fun stopSong() {
        songPlayer?.runCatching { stop(); release() }
        songPlayer = null
        _song.update { it.copy(playing = false) }
    }

    /** Saves the song's audio (WAV) or its MIDI (melody, chords, bass, drums tracks) to Downloads. */
    fun saveSong(midi: Boolean, onUnsupported: () -> Unit) {
        val f = (if (midi) songMid else songWav) ?: return
        val format = if (midi) ExportFormat.MIDI else ExportFormat.WAV
        viewModelScope.launch {
            runCatching { exporter.saveToDownloads(f, format) }
                .onSuccess { where -> if (where == null) onUnsupported() else msg("Saved to $where") }
                .onFailure { msg("Save failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun shareSong(midi: Boolean) {
        val f = (if (midi) songMid else songWav) ?: return
        runCatching { exporter.share(f, if (midi) ExportFormat.MIDI else ExportFormat.WAV) }
            .onFailure { msg("Share failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    override fun onCleared() {
        stopSong()
        stopPractice()
        engine?.release()
        model?.close()
        // viewModelScope is already cancelled here, so flush pending edits synchronously.
        if (dirty) kotlinx.coroutines.runBlocking { runCatching { saveNow() } }
    }
}
