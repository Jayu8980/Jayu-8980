package com.autotube.ai.ui.studio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import com.autotube.ai.studio.AudioKind
import com.autotube.ai.studio.AudioLayer
import com.autotube.ai.studio.CanvasRatio
import com.autotube.ai.studio.StudioClip
import com.autotube.ai.studio.StudioCompositionBuilder
import com.autotube.ai.studio.StudioEdits
import com.autotube.ai.studio.StudioExporter
import com.autotube.ai.studio.StudioProject
import com.autotube.ai.studio.StudioRepository
import com.autotube.ai.studio.TextLayer
import com.autotube.ai.studio.VoiceRecorder
import com.autotube.ai.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What is selected on the timeline. */
sealed interface Selection {
    data object None : Selection
    data class Clip(val id: String) : Selection
    data class Text(val id: String) : Selection
    data class Audio(val id: String) : Selection
}

/** Tool panels shown above the toolbar. */
enum class Panel {
    CANVAS, SPEED, VOLUME, FILTERS, ADJUST, TRANSFORM, TRANSITION, IMAGE_DURATION,
    TEXT_STYLE, TEXT_ANIM, STICKERS, AUDIO_VOLUME, AUDIO_FADE, RECORD,
}

sealed interface ExportState {
    data object Idle : ExportState
    data object Choosing : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val path: String, val galleryUri: String?) : ExportState
    data class Failed(val message: String) : ExportState
}

data class EditorUiState(
    val project: StudioProject? = null,
    val loading: Boolean = true,
    val missing: Boolean = false,
    val playheadMs: Long = 0L,
    val playing: Boolean = false,
    val selection: Selection = Selection.None,
    val panel: Panel? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Import progress 0..1, null when idle. */
    val importing: Float? = null,
    val export: ExportState = ExportState.Idle,
    val recording: Boolean = false,
    val recordingStartMs: Long = 0L,
    val recordingElapsedMs: Long = 0L,
    /** Set when the preview had to drop features this device cannot play live. */
    val previewNote: String? = null,
)

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent
    data class EditText(val layer: TextLayer?) : EditorEvent
}

@HiltViewModel
class StudioEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val repository: StudioRepository,
    private val exporter: StudioExporter,
) : ViewModel() {

    private val projectId: String = checkNotNull(savedStateHandle[Routes.ARG_STUDIO_ID])

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    /** Live preview player; the screen attaches it to a PlayerView. */
    val player: CompositionPlayer = CompositionPlayer.Builder(context).build()

    private val undo = ArrayDeque<StudioProject>()
    private val redo = ArrayDeque<StudioProject>()
    /** Snapshot taken when a continuous gesture (drag, trim) starts. */
    private var gestureStart: StudioProject? = null

    private var lastPreviewKey: Any? = null
    private var previewLevel = 0
    private var previewJob: Job? = null
    private var saveJob: Job? = null
    private var tickJob: Job? = null
    private var exportJob: Job? = null
    private var recordJob: Job? = null
    private val recorder = VoiceRecorder(context)

    private val project: StudioProject? get() = _state.value.project

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(playing = isPlaying) }
                if (isPlaying) startTicker() else syncPlayhead()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _state.update { it.copy(playing = false, playheadMs = project?.durationMs ?: 0L) }
                }
            }

            override fun onPlayerError(error: PlaybackException) = onPreviewError()
        })
        viewModelScope.launch {
            val p = repository.load(projectId)
            if (p == null) {
                _state.update { it.copy(loading = false, missing = true) }
            } else {
                _state.update { it.copy(loading = false, project = p) }
                refreshPreview(immediate = true)
            }
        }
    }

    // ------------------------------------------------------------------ project changes

    /** Applies an undoable edit. */
    fun edit(change: (StudioProject) -> StudioProject) {
        val current = project ?: return
        val next = change(current)
        if (next == current) return
        if (gestureStart == null) {
            undo.addLast(current)
            if (undo.size > MAX_HISTORY) undo.removeFirst()
            redo.clear()
        }
        setProject(next)
    }

    /** Starts a continuous gesture: following [edit]s merge into one undo step. */
    fun beginGesture() {
        if (gestureStart == null) gestureStart = project
    }

    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != project) {
            undo.addLast(start)
            if (undo.size > MAX_HISTORY) undo.removeFirst()
            redo.clear()
            _state.update { it.copy(canUndo = true, canRedo = false) }
        }
    }

    fun undo() {
        val current = project ?: return
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(current)
        setProject(prev, validateSelection = true)
    }

    fun redo() {
        val current = project ?: return
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(current)
        setProject(next, validateSelection = true)
    }

    private fun setProject(p: StudioProject, validateSelection: Boolean = true) {
        _state.update { s ->
            var sel = s.selection
            if (validateSelection) {
                sel = when (sel) {
                    is Selection.Clip -> if (p.clip(sel.id) != null) sel else Selection.None
                    is Selection.Text -> if (p.text(sel.id) != null) sel else Selection.None
                    is Selection.Audio -> if (p.audioLayer(sel.id) != null) sel else Selection.None
                    Selection.None -> sel
                }
            }
            s.copy(
                project = p,
                selection = sel,
                panel = if (sel == Selection.None && s.panel?.needsSelection == true) null else s.panel,
                playheadMs = s.playheadMs.coerceIn(0L, p.durationMs),
                canUndo = undo.isNotEmpty() || gestureStart != null,
                canRedo = redo.isNotEmpty(),
            )
        }
        scheduleSave()
        refreshPreview()
    }

    private val Panel.needsSelection: Boolean
        get() = this != Panel.CANVAS && this != Panel.STICKERS && this != Panel.RECORD

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            project?.let { repository.save(it) }
        }
    }

    // ------------------------------------------------------------------ preview

    /** Rebuilds the preview composition when something that affects the picture or sound changed. */
    private fun refreshPreview(immediate: Boolean = false) {
        val p = project ?: return
        // Text is drawn by Compose, so text edits never rebuild the player.
        val key = listOf(p.clips, p.audio, p.ratio, p.fit, previewLevel)
        if (key == lastPreviewKey) return
        lastPreviewKey = key
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(PREVIEW_DEBOUNCE_MS)
            if (p.clips.isEmpty()) {
                player.stop()
                player.clearMediaItemsSafely()
                return@launch
            }
            val (w, h) = p.ratio.sizeFor(PREVIEW_SHORT_SIDE)
            val options = when (previewLevel) {
                0 -> StudioCompositionBuilder.Options(w, h, forPreview = true)
                1 -> StudioCompositionBuilder.Options(w, h, forPreview = true, audioLayers = false)
                else -> StudioCompositionBuilder.Options(w, h, forPreview = true, audioLayers = false, speed = false)
            }
            val built = try {
                withContext(Dispatchers.IO) { StudioCompositionBuilder(repository.cacheDir(p.id)).build(p, options) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val composition = built ?: return@launch
            val wasPlaying = player.isPlaying
            val at = _state.value.playheadMs.coerceIn(0L, p.durationMs)
            try {
                player.setComposition(composition)
                player.prepare()
                player.seekTo(at)
                if (wasPlaying) player.play()
            } catch (e: Exception) {
                onPreviewError()
            }
        }
    }

    private fun CompositionPlayer.clearMediaItemsSafely() {
        runCatching { clearMediaItems() }
    }

    private fun onPreviewError() {
        if (previewLevel >= 2) {
            _state.update { it.copy(previewNote = "Live preview is not available on this device. Export still works.") }
            return
        }
        previewLevel++
        _state.update {
            it.copy(previewNote = "Preview simplified on this device (${if (previewLevel == 1) "extra audio" else "speed"} not previewed). Export includes everything.")
        }
        lastPreviewKey = null
        refreshPreview(immediate = true)
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                syncPlayhead()
                if (_state.value.recording) {
                    _state.update { it.copy(recordingElapsedMs = recorder.elapsedMs()) }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun syncPlayhead() {
        val p = project ?: return
        _state.update { it.copy(playheadMs = player.currentPosition.coerceIn(0L, p.durationMs)) }
    }

    fun togglePlay() {
        val p = project ?: return
        if (p.clips.isEmpty()) return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (_state.value.playheadMs >= p.durationMs - 50) seekTo(0L)
            player.play()
        }
    }

    /** Scrubbing from the timeline. */
    fun seekTo(ms: Long) {
        val p = project ?: return
        val t = ms.coerceIn(0L, p.durationMs)
        if (player.isPlaying && !_state.value.recording) player.pause()
        _state.update { it.copy(playheadMs = t) }
        runCatching { player.seekTo(t) }
    }

    fun pause() {
        if (player.isPlaying) player.pause()
    }

    // ------------------------------------------------------------------ selection & panels

    fun select(selection: Selection) {
        _state.update { s ->
            val keepPanel = s.panel != null && selection != Selection.None && sameKind(s.selection, selection)
            s.copy(selection = selection, panel = if (keepPanel) s.panel else null)
        }
    }

    private fun sameKind(a: Selection, b: Selection) = a::class == b::class

    fun openPanel(panel: Panel?) {
        _state.update { it.copy(panel = panel) }
    }

    fun dismissPreviewNote() {
        _state.update { it.copy(previewNote = null) }
    }

    // ------------------------------------------------------------------ import

    fun addMedia(uris: List<Uri>) {
        val p = project ?: return
        if (uris.isEmpty()) return
        pause()
        viewModelScope.launch {
            _state.update { it.copy(importing = 0f) }
            val clips = repository.importVisuals(p.id, uris) { f -> _state.update { it.copy(importing = f) } }
            _state.update { it.copy(importing = null) }
            if (clips.isEmpty()) {
                message("Those files could not be read.")
                return@launch
            }
            if (clips.size < uris.size) message("${uris.size - clips.size} item(s) could not be read and were skipped.")
            val wasEmpty = project?.clips.isNullOrEmpty()
            val index = StudioEdits.insertIndexAt(project ?: return@launch, _state.value.playheadMs)
            edit { StudioEdits.insertClips(it, clips, index) }
            if (wasEmpty) autoRatio(clips.first())
            select(Selection.Clip(clips.first().id))
        }
    }

    /** First clip of a new project picks the canvas ratio (like CapCut's "Original"). */
    private fun autoRatio(first: StudioClip) {
        if (first.width <= 0 || first.height <= 0) return
        val aspect = first.width.toFloat() / first.height
        val best = CanvasRatio.entries.minBy { kotlin.math.abs(it.aspect - aspect) }
        val current = project ?: return
        if (current.ratio != best) setProject(current.copy(ratio = best))
    }

    fun addAudio(uri: Uri, kind: AudioKind) {
        val p = project ?: return
        pause()
        viewModelScope.launch {
            _state.update { it.copy(importing = 0f) }
            val start = if (kind == AudioKind.MUSIC && p.audio.none { it.kind == AudioKind.MUSIC }) 0L else _state.value.playheadMs
            val layer = repository.importAudio(p.id, uri, kind, start)
            _state.update { it.copy(importing = null) }
            if (layer == null) {
                message("That audio file could not be read.")
                return@launch
            }
            edit { StudioEdits.addAudio(it, layer) }
            select(Selection.Audio(layer.id))
        }
    }

    // ------------------------------------------------------------------ clip tools

    fun split() {
        val p = project ?: return
        val t = _state.value.playheadMs
        when (val sel = _state.value.selection) {
            is Selection.Text -> {
                val (np, id) = StudioEdits.splitText(p, sel.id, t)
                if (id == null) message("Move the playhead inside the text to split it.") else edit { np }
            }
            is Selection.Audio -> {
                val (np, id) = StudioEdits.splitAudio(p, sel.id, t)
                if (id == null) message("Move the playhead inside the audio to split it.") else edit { np }
            }
            else -> {
                val (np, id) = StudioEdits.splitClipAt(p, t)
                if (id == null) {
                    message("Move the playhead away from the clip edge to split.")
                } else {
                    edit { np }
                    select(Selection.Clip(id))
                }
            }
        }
    }

    fun deleteSelected() {
        when (val sel = _state.value.selection) {
            is Selection.Clip -> edit { StudioEdits.deleteClip(it, sel.id) }
            is Selection.Text -> edit { StudioEdits.deleteText(it, sel.id) }
            is Selection.Audio -> edit { StudioEdits.deleteAudio(it, sel.id) }
            Selection.None -> return
        }
        select(Selection.None)
    }

    fun duplicateSelected() {
        val p = project ?: return
        when (val sel = _state.value.selection) {
            is Selection.Clip -> StudioEdits.duplicateClip(p, sel.id).let { (np, id) -> edit { np }; id?.let { select(Selection.Clip(it)) } }
            is Selection.Text -> StudioEdits.duplicateText(p, sel.id).let { (np, id) -> edit { np }; id?.let { select(Selection.Text(it)) } }
            is Selection.Audio -> StudioEdits.duplicateAudio(p, sel.id).let { (np, id) -> edit { np }; id?.let { select(Selection.Audio(it)) } }
            Selection.None -> Unit
        }
    }

    fun moveSelectedClip(delta: Int) {
        val sel = _state.value.selection as? Selection.Clip ?: return
        edit { StudioEdits.moveClip(it, sel.id, delta) }
    }

    fun updateClip(id: String, change: (StudioClip) -> StudioClip) = edit { StudioEdits.updateClip(it, id, change) }

    fun updateAllClips(change: (StudioClip) -> StudioClip) {
        edit { StudioEdits.updateAllClips(it, change) }
        message("Applied to all clips")
    }

    fun trimClip(id: String, startMs: Long, endMs: Long) = edit { StudioEdits.trimClip(it, id, startMs, endMs) }

    fun trimToPlayhead(keepRight: Boolean) = edit { StudioEdits.trimToPlayhead(it, _state.value.playheadMs, keepRight) }

    fun freezeFrame() {
        val p = project ?: return
        val i = p.clipIndexAt(_state.value.playheadMs)
        if (i < 0) return
        val clip = p.clips[i]
        val local = _state.value.playheadMs - p.clipStarts()[i]
        pause()
        viewModelScope.launch {
            val frame = repository.freezeFrame(p.id, clip, clip.sourceTimeAt(local)) ?: run {
                message("Could not grab that frame.")
                return@launch
            }
            edit { cur ->
                val (splitP, rightId) = StudioEdits.splitClipAt(cur, _state.value.playheadMs)
                val base = if (rightId != null) splitP else cur
                val leftIndex = base.clips.indexOfFirst { it.id == clip.id }
                val insertAt = if (rightId != null || local >= clip.durationMs / 2) leftIndex + 1 else leftIndex
                StudioEdits.insertClips(base, listOf(frame), insertAt)
            }
            select(Selection.Clip(frame.id))
            message("Freeze frame added")
        }
    }

    /** Moves a video clip's sound to its own audio layer and mutes the clip. */
    fun extractAudio() {
        val p = project ?: return
        val sel = _state.value.selection as? Selection.Clip ?: return
        val i = p.clips.indexOfFirst { it.id == sel.id }
        val clip = p.clips.getOrNull(i) ?: return
        if (clip.isImage || !clip.hasAudio) {
            message("This clip has no sound.")
            return
        }
        val layer = AudioLayer(
            path = clip.path,
            name = "Extracted audio",
            kind = AudioKind.EXTRACTED,
            startMs = p.clipStarts()[i],
            trimStartMs = clip.trimStartMs,
            durationMs = clip.trimmedMs,
            sourceDurationMs = clip.sourceDurationMs,
            volume = clip.volume.coerceAtLeast(0.01f),
        )
        edit { StudioEdits.addAudio(StudioEdits.updateClip(it, clip.id) { c -> c.copy(volume = 0f) }, layer) }
        select(Selection.Audio(layer.id))
    }

    fun setRatio(ratio: CanvasRatio) = edit { it.copy(ratio = ratio) }

    fun setProjectFit(fit: com.autotube.ai.studio.FitMode) = edit { it.copy(fit = fit) }

    // ------------------------------------------------------------------ text

    fun requestNewText() {
        pause()
        _events.tryEmit(EditorEvent.EditText(null))
    }

    fun requestEditText() {
        val sel = _state.value.selection as? Selection.Text ?: return
        _events.tryEmit(EditorEvent.EditText(project?.text(sel.id)))
    }

    /** From the text dialog: [existing] null adds a new layer at the playhead. */
    fun commitText(existing: TextLayer?, text: String) {
        val value = text.trim()
        if (existing != null) {
            if (value.isEmpty()) {
                edit { StudioEdits.deleteText(it, existing.id) }
                select(Selection.None)
            } else {
                updateText(existing.id) { it.copy(text = value) }
            }
            return
        }
        if (value.isEmpty()) return
        addTextLayer(TextLayer(text = value, startMs = 0L, endMs = 0L))
    }

    fun addSticker(emoji: String) {
        addTextLayer(TextLayer(text = emoji, startMs = 0L, endMs = 0L, size = 0.12f, sticker = true, anim = com.autotube.ai.studio.TextAnim.POP))
    }

    private fun addTextLayer(template: TextLayer) {
        val p = project ?: return
        val start = _state.value.playheadMs.coerceAtMost((p.durationMs - StudioEdits.MIN_MS).coerceAtLeast(0L))
        val end = (start + StudioEdits.DEFAULT_TEXT_MS).let { if (p.durationMs > start + StudioEdits.MIN_MS) minOf(it, p.durationMs) else it }
        val layer = template.copy(startMs = start, endMs = end)
        edit { StudioEdits.addText(it, layer) }
        select(Selection.Text(layer.id))
    }

    fun updateText(id: String, change: (TextLayer) -> TextLayer) = edit { StudioEdits.updateText(it, id, change) }

    // ------------------------------------------------------------------ audio

    fun updateAudio(id: String, change: (AudioLayer) -> AudioLayer) = edit { StudioEdits.updateAudio(it, id, change) }

    fun placeAudio(id: String, startMs: Long, trimStartMs: Long, durationMs: Long) =
        edit { StudioEdits.placeAudio(it, id, startMs, trimStartMs, durationMs) }

    fun startRecording() {
        val p = project ?: return
        if (recorder.isRecording) return
        val start = _state.value.playheadMs
        try {
            recorder.start(repository.newRecordingFile(p.id))
        } catch (e: Exception) {
            message("Could not start the microphone.")
            return
        }
        _state.update { it.copy(recording = true, recordingStartMs = start, recordingElapsedMs = 0L) }
        player.volume = 0f
        if (p.clips.isNotEmpty() && start < p.durationMs - 100) player.play()
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
            while (isActive && recorder.isRecording) {
                _state.update { it.copy(recordingElapsedMs = recorder.elapsedMs()) }
                delay(TICK_MS)
            }
        }
    }

    fun stopRecording() {
        if (!recorder.isRecording) return
        val start = _state.value.recordingStartMs
        val file = recorder.stop()
        recordJob?.cancel()
        player.pause()
        player.volume = 1f
        _state.update { it.copy(recording = false) }
        if (file == null) {
            message("Recording was too short.")
            return
        }
        viewModelScope.launch {
            val layer = withContext(Dispatchers.IO) { repository.audioLayerFor(file, "Voiceover", AudioKind.VOICEOVER, start) }
            if (layer == null) {
                message("Recording could not be read.")
                return@launch
            }
            edit { StudioEdits.addAudio(it, layer) }
            select(Selection.Audio(layer.id))
        }
    }

    // ------------------------------------------------------------------ export

    fun openExport() {
        pause()
        if (project?.clips.isNullOrEmpty()) {
            message("Add a video or photo first.")
            return
        }
        _state.update { it.copy(export = ExportState.Choosing) }
    }

    fun startExport(shortSide: Int) {
        val p = project ?: return
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _state.update { it.copy(export = ExportState.Running(0f)) }
            try {
                val result = exporter.export(p, shortSide) { f -> _state.update { s -> if (s.export is ExportState.Running) s.copy(export = ExportState.Running(f)) else s } }
                val saved = repository.save(p.copy(exportShortSide = shortSide, lastExportPath = result.file.absolutePath))
                _state.update { it.copy(project = saved, export = ExportState.Done(result.file.absolutePath, result.galleryUri?.toString())) }
            } catch (e: CancellationException) {
                _state.update { it.copy(export = ExportState.Idle) }
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(export = ExportState.Failed(e.message ?: "Export failed.")) }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _state.update { it.copy(export = ExportState.Idle) }
    }

    fun closeExport() {
        _state.update { it.copy(export = ExportState.Idle) }
    }

    fun rename(name: String) {
        val value = name.trim()
        if (value.isEmpty()) return
        edit { it.copy(name = value) }
    }

    private fun message(text: String) {
        _events.tryEmit(EditorEvent.Message(text))
    }

    override fun onCleared() {
        recorder.cancel()
        project?.let { repository.saveAsync(it) }
        player.release()
        super.onCleared()
    }

    private companion object {
        const val MAX_HISTORY = 100
        const val SAVE_DEBOUNCE_MS = 600L
        const val PREVIEW_DEBOUNCE_MS = 250L
        const val TICK_MS = 33L
        const val PREVIEW_SHORT_SIDE = 540
    }
}
