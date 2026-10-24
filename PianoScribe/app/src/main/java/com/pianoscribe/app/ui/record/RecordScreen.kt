package com.pianoscribe.app.ui.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.audio.Draft
import com.pianoscribe.app.audio.Recorder
import com.pianoscribe.app.ui.components.Waveform
import com.pianoscribe.app.ui.components.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordScreen(container: AppContainer, onBack: () -> Unit, onRecorded: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val recorder = remember { Recorder() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val levels by recorder.levels.collectAsStateWithLifecycle()
    val elapsed by recorder.elapsed.collectAsStateWithLifecycle()
    val recording = job?.isActive == true

    DisposableEffect(Unit) { onDispose { recorder.stopRequested = true } }

    fun start() {
        error = null
        val file = File(ctx.cacheDir, "draft.f32")
        job = scope.launch {
            try {
                val n = recorder.record(file)
                if (n < 22050 / 2) { error = "The recording is too short."; return@launch }
                val name = "Recording " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
                container.draft.value = Draft(file, name, "Microphone", n)
                onRecorded()
            } catch (e: Exception) {
                error = e.message ?: "Recording failed"
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Record") }, navigationIcon = { IconButton(onClick = { recorder.stopRequested = true; onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (!granted) {
                Text("PianoScribe needs the microphone to record. Audio never leaves your phone.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                return@Column
            }
            Text(formatDuration(elapsed) + " / " + formatDuration(Recorder.MAX_SECONDS.toDouble()), style = MaterialTheme.typography.displaySmall)
            LinearProgressIndicator(progress = { (elapsed / Recorder.MAX_SECONDS).toFloat() }, modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth())
            Waveform(levels, MaterialTheme.colorScheme.primary, Modifier.widthIn(max = 600.dp).fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(8.dp))
            LargeFloatingActionButton(
                onClick = { if (recording) recorder.stopRequested = true else start() },
                containerColor = if (recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, if (recording) "Stop" else "Record", Modifier.size(36.dp))
            }
            Text(
                if (recording) "Recording… tap stop when the piece ends." else "Place the phone near the instrument and tap record. Maximum 10 minutes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
