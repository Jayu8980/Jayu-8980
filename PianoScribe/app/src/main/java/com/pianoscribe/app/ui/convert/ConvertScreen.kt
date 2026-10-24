package com.pianoscribe.app.ui.convert

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.data.ProjectEntity
import com.pianoscribe.app.transcribe.TranscriptionEngine
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ConvertViewModel(app: Application, private val container: AppContainer, start: Int, end: Int) : AndroidViewModel(app) {
    data class UiState(val step: String = "Preparing", val progress: Float = 0f, val error: String? = null, val doneId: Long? = null, val elapsed: Double = 0.0)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val job: Job

    init {
        job = viewModelScope.launch {
            val t0 = System.nanoTime()
            val draft = container.draft.value
            if (draft == null) { _state.value = UiState(error = "The audio is no longer available. Please import it again."); return@launch }
            val ctx = getApplication<Application>()
            val trimmed = File(ctx.cacheDir, "convert.f32")
            try {
                _state.value = UiState("Trimming audio", 0.01f)
                withContext(Dispatchers.IO) { PcmFile.copyRange(draft.file, start, end.coerceAtMost(draft.lengthSamples), trimmed) }
                val settings = container.settings.value
                val result = TranscriptionEngine(ctx).transcribe(trimmed, settings) { step, p ->
                    _state.value = UiState(step, p, elapsed = (System.nanoTime() - t0) / 1e9)
                }
                _state.value = UiState("Saving project", 1f)
                val now = System.currentTimeMillis()
                val length = PcmFile.length(trimmed)
                val id = container.repository.create(
                    ProjectEntity(
                        name = draft.name, createdAt = now, updatedAt = now, audioPath = "", durationSec = length.toDouble() / BasicPitch.SAMPLE_RATE,
                        bpm = result.grid.bpm, beatsPerBar = result.grid.beatsPerBar, beatUnit = result.grid.beatUnit, gridOffset = result.grid.offset,
                        splitPoint = settings.splitPoint, quantizeDivision = if (settings.pianoOnly) settings.quantizeDivision else 0,
                        keyName = result.key?.name, noteCount = result.notes.size,
                        source = draft.source + (if (result.songMode) " · full song" else "") + result.cleanup.joinToString("") { " · $it" },
                    ),
                )
                val rel = "projects/$id/audio.f32"
                withContext(Dispatchers.IO) {
                    val dst = File(ctx.filesDir, rel).apply { parentFile?.mkdirs() }
                    if (!trimmed.renameTo(dst)) { trimmed.copyTo(dst, overwrite = true); trimmed.delete() }
                }
                val p = container.repository.load(id)!!.entity.copy(audioPath = rel)
                container.repository.save(p, result.notes)
                val secs = (System.nanoTime() - t0) / 1e9
                val mode = buildString {
                    if (result.songMode) append(" · full-song mode: drums removed")
                    if (result.retriedSensitive) append(" · used more sensitive settings")
                }
                _state.value = UiState(String.format(Locale.US, "Done in %.0f s", secs) + mode, 1f, doneId = id, elapsed = secs)
            } catch (e: CancellationException) {
                trimmed.delete()
                throw e
            } catch (e: OutOfMemoryError) {
                _state.value = UiState(error = "Not enough memory for this song. Trim it into shorter parts and convert each one.")
            } catch (e: Exception) {
                trimmed.delete()
                _state.value = UiState(error = e.message ?: "Conversion failed")
            }
        }
    }

    fun cancel() = job.cancel()
}

@Composable
fun ConvertScreen(container: AppContainer, start: Int, end: Int, onCancel: () -> Unit, onDone: (Long) -> Unit) {
    val vm: ConvertViewModel = viewModel { ConvertViewModel(container.app, container, start, end) }
    val s by vm.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(s.doneId) { s.doneId?.let(onDone) }

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (s.error != null) {
                Text("Conversion stopped", style = MaterialTheme.typography.headlineSmall)
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onCancel) { Text("Back") }
                return@Column
            }
            Text("Converting to piano notes", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth())
            Text("${(s.progress * 100).toInt()}%  ·  ${s.step}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "1. Check audio (songs: remove drums)   2. Listen with the Basic Pitch model   3. Extract & clean up notes   4. Tuning, tempo, hands & key",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Everything runs on this phone. No internet is used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { vm.cancel(); onCancel() }) { Text("Cancel") }
        }
    }
}
