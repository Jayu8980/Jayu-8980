package com.pianoscribe.app.ui.prepare

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.audio.AudioDecoder
import com.pianoscribe.app.audio.Draft
import com.pianoscribe.app.audio.PcmFile
import com.pianoscribe.app.playback.PlayMode
import com.pianoscribe.app.playback.PlaybackEngine
import com.pianoscribe.app.ui.components.AudioTypeSelector
import com.pianoscribe.app.ui.components.Waveform
import com.pianoscribe.app.ui.components.formatTime
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PrepareViewModel(app: Application, private val container: AppContainer, uri: String?) : AndroidViewModel(app) {
    data class UiState(
        val decoding: Boolean = true,
        val progress: Float = 0f,
        val error: String? = null,
        val draft: Draft? = null,
        val waveform: FloatArray = FloatArray(0),
        val trimStart: Float = 0f,
        val trimEnd: Float = 0f,
        val playing: Boolean = false,
        val position: Double = 0.0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var player: PlaybackEngine? = null

    init {
        viewModelScope.launch {
            try {
                val draft = if (uri != null) decode(Uri.parse(uri)) else container.draft.value ?: error("Nothing to convert")
                container.draft.value = draft
                val wf = withContext(Dispatchers.IO) { PcmFile.waveform(draft.file, 400) }
                _state.value = _state.value.copy(decoding = false, draft = draft, waveform = wf, trimStart = 0f, trimEnd = draft.durationSec.toFloat())
                player = PlaybackEngine(null, draft.file).apply { mode = PlayMode.ORIGINAL; duration = draft.durationSec }
                player!!.state.collect { s -> _state.value = _state.value.copy(playing = s.playing, position = s.position) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(decoding = false, error = e.message ?: "Couldn't read this file")
            }
        }
    }

    private suspend fun decode(uri: Uri): Draft {
        val ctx = getApplication<Application>()
        val file = File(ctx.cacheDir, "draft.f32")
        val r = AudioDecoder(ctx).decode(uri, file) { p -> _state.value = _state.value.copy(progress = p) }
        return Draft(file, AudioDecoder.displayName(ctx, uri), "Imported file", r.samples)
    }

    fun setTrim(start: Float, end: Float) {
        val minLen = 0.5f
        val s = start.coerceAtMost(end - minLen).coerceAtLeast(0f)
        _state.value = _state.value.copy(trimStart = s, trimEnd = end.coerceAtLeast(s + minLen))
        player?.loop = s.toDouble()..end.toDouble()
    }

    /** Suggests trim points by skipping leading and trailing near-silence. */
    fun autoTrim() {
        val wf = _state.value.waveform
        val d = _state.value.draft ?: return
        if (wf.isEmpty()) return
        val first = wf.indexOfFirst { it > 0.03f }.coerceAtLeast(0)
        val last = wf.indexOfLast { it > 0.03f }.let { if (it < 0) wf.size - 1 else it }
        val dur = d.durationSec.toFloat()
        setTrim(maxOf(0f, first * dur / wf.size - 0.1f), minOf(dur, (last + 1) * dur / wf.size + 0.2f))
    }

    fun togglePreview() {
        val p = player ?: return
        val s = _state.value
        p.loop = s.trimStart.toDouble()..s.trimEnd.toDouble()
        if (!s.playing && (s.position < s.trimStart || s.position > s.trimEnd)) p.seek(s.trimStart.toDouble())
        p.toggle()
    }

    fun stopPreview() = player?.pause()

    fun range(): Pair<Int, Int> {
        val s = _state.value
        return (s.trimStart * BasicPitch.SAMPLE_RATE).toInt() to minOf((s.trimEnd * BasicPitch.SAMPLE_RATE).toInt(), s.draft?.lengthSamples ?: 0)
    }

    override fun onCleared() {
        player?.release()
    }
}

@Composable
fun PrepareScreen(container: AppContainer, uri: String?, onBack: () -> Unit, onConvert: (Int, Int) -> Unit) {
    val vm: PrepareViewModel = viewModel { PrepareViewModel(container.app, container, uri) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(s.draft?.name ?: "Prepare audio", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val width = Modifier.widthIn(max = 800.dp).fillMaxWidth()
            when {
                s.error != null -> {
                    Text("Can't use this audio", style = MaterialTheme.typography.titleLarge)
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                    Text("Supported: MP3, WAV, M4A/AAC, OGG and FLAC files that this phone can decode.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onBack) { Text("Back") }
                }
                s.decoding -> {
                    Text("Decoding audio…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { s.progress }, modifier = width)
                    Text("Converting to mono 22 050 Hz on your phone.", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    val d = s.draft!!
                    val dur = d.durationSec.toFloat()
                    Text("Trim the start and end, then convert.", style = MaterialTheme.typography.bodyLarge)
                    val selColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    val playColor = MaterialTheme.colorScheme.error
                    Box(width.height(120.dp).drawWithContent {
                        val x0 = size.width * s.trimStart / dur
                        val x1 = size.width * s.trimEnd / dur
                        drawRect(selColor, Offset(x0, 0f), Size(x1 - x0, size.height))
                        drawContent()
                        val px = (size.width * s.position / dur).toFloat()
                        if (s.playing) drawLine(playColor, Offset(px, 0f), Offset(px, size.height), 3f)
                    }) {
                        Waveform(s.waveform, MaterialTheme.colorScheme.primary, Modifier.fillMaxSize())
                    }
                    RangeSlider(
                        value = s.trimStart..s.trimEnd,
                        onValueChange = { vm.setTrim(it.start, it.endInclusive) },
                        valueRange = 0f..dur,
                        modifier = width,
                    )
                    Row(width, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Start ${formatTime(s.trimStart.toDouble())}")
                        FilledTonalIconButton(onClick = vm::togglePreview) {
                            Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Preview")
                        }
                        Text("End ${formatTime(s.trimEnd.toDouble())}")
                    }
                    Text("Selected: ${formatTime((s.trimEnd - s.trimStart).toDouble())} of ${formatTime(d.durationSec)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::autoTrim) {
                        Icon(Icons.Default.AutoAwesome, null); Text("  Trim silence")
                    }
                    AudioTypeSelector(settings.audioType, { t -> container.settings.update { it.copy(audioType = t) } }, width)
                    Button(onClick = { vm.stopPreview(); val (a, b) = vm.range(); onConvert(a, b) }, modifier = width.height(56.dp)) {
                        Text("Convert to piano notes", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Tip: accuracy is best for solo piano or a single instrument. For songs, full-song mode gives a much cleaner result, but it is still approximate.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
