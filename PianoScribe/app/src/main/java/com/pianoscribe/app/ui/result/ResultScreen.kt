package com.pianoscribe.app.ui.result

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.export.ExportFormat
import com.pianoscribe.app.ui.components.LabeledSlider
import com.pianoscribe.core.theory.Pitch

private enum class ResultTab(val title: String) { ROLL("Piano Roll"), FALLING("Falling Notes"), SHEET("Sheet"), NOTES("Notes"), ANALYSER("Analyser") }

private enum class Dialog { NONE, EXPORT, TRANSPOSE, TEMPO, QUANTIZE, SPLIT, RENAME }

@Composable
fun ResultScreen(container: AppContainer, projectId: Long, onBack: () -> Unit, onSettings: () -> Unit) {
    val vm: ResultViewModel = viewModel(key = "result-$projectId") { ResultViewModel(container.app, container, projectId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val rollViewport = remember { RollViewport() }
    var pendingSave by remember { mutableStateOf<ExportFormat?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingSave
        if (uri != null && f != null) vm.saveTo(f, uri)
        pendingSave = null
    }

    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    LaunchedEffect(s.derived.pitchHi, s.loading) {
        if (!s.loading) rollViewport.topPitch = (s.derived.pitchHi + 4).toFloat().coerceAtMost(109f)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(s.project?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = s.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = s.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    if (tab == ResultTab.ROLL.ordinal) IconButton(onClick = vm::toggleEditMode) {
                        Icon(if (s.editMode) Icons.Default.Edit else Icons.Default.EditOff, if (s.editMode) "Editing on" else "Editing off",
                            tint = if (s.editMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { dialog = Dialog.EXPORT }) { Icon(Icons.Default.IosShare, "Export") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Transpose…") }, onClick = { menu = false; dialog = Dialog.TRANSPOSE })
                            DropdownMenuItem(text = { Text("Tempo & meter…") }, onClick = { menu = false; dialog = Dialog.TEMPO })
                            DropdownMenuItem(text = { Text("Quantise (piano only)…") }, onClick = { menu = false; dialog = Dialog.QUANTIZE })
                            DropdownMenuItem(text = { Text("Hand split point…") }, onClick = { menu = false; dialog = Dialog.SPLIT })
                            DropdownMenuItem(text = { Text("Loop selected bar") }, onClick = { menu = false; vm.loopSelection() })
                            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; dialog = Dialog.RENAME })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!s.loading && s.error == null) TransportBar(vm, s, position, playing)
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(s.error!!) }
                else -> {
                    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                        ResultTab.entries.forEach { t -> Tab(selected = tab == t.ordinal, onClick = { tab = t.ordinal }, text = { Text(t.title) }) }
                    }
                    val d = s.derived
                    val flats = d.key?.preferFlats ?: false
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (ResultTab.entries[tab]) {
                            ResultTab.ROLL -> Column(Modifier.fillMaxSize()) {
                                InfoStrip(s, position)
                                PianoRoll(
                                    notes = d.notes, grid = s.grid, duration = d.duration, position = position, playing = playing,
                                    selectedId = s.selectedId, editable = s.editMode, viewport = rollViewport, key = d.key,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    onSelect = vm::select, onSeek = vm::seek, onMove = vm::moveNote, onResize = vm::resizeNote,
                                    onDelete = vm::deleteNote, onAdd = vm::addNote, onKey = vm::midiPreview,
                                )
                                if (s.editMode) Text(
                                    "Edit mode: drag a note to move it, drag its end to resize, long-press to delete, double-tap empty space to add.",
                                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            ResultTab.FALLING -> FallingNotes(d.notes, position, d.duration, d.pitchLo, d.pitchHi, d.fingers, Modifier.fillMaxSize(), onSeek = vm::seek)
                            ResultTab.SHEET -> SheetView(d.notes, s.grid, position, flats, Modifier.fillMaxSize(), onSeek = vm::seek)
                            ResultTab.NOTES -> NotesList(
                                d.notes, s.grid, settings.notation, settings.saPitchClass, flats, s.selectedId,
                                onNotation = vm::setNotation, onSelect = { n -> vm.select(n.id); vm.seek(n.start) }, modifier = Modifier.fillMaxSize(),
                            )
                            ResultTab.ANALYSER -> AnalyserView(vm, s, settings, position, playing)
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        Dialog.NONE -> {}
        Dialog.EXPORT -> ExportDialog(
            onDismiss = { dialog = Dialog.NONE },
            onShare = { f -> dialog = Dialog.NONE; vm.share(f) },
            onSave = { f -> dialog = Dialog.NONE; pendingSave = f; saveLauncher.launch(fileName(s.project?.name, f)) },
        )
        Dialog.TRANSPOSE -> {
            var semis by remember { mutableIntStateOf(0) }
            AlertDialog(
                onDismissRequest = { dialog = Dialog.NONE },
                title = { Text("Transpose") },
                text = { LabeledSlider("Semitones", semis.toFloat(), (if (semis > 0) "+" else "") + semis, -12f..12f, { semis = Math.round(it) }, steps = 23) },
                confirmButton = { TextButton(onClick = { vm.transpose(semis); dialog = Dialog.NONE }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Cancel") } },
            )
        }
        Dialog.TEMPO -> {
            var bpm by remember { mutableStateOf(String.format(java.util.Locale.US, "%.1f", s.grid.bpm)) }
            var stretch by remember { mutableStateOf(false) }
            var beats by remember { mutableIntStateOf(s.grid.beatsPerBar) }
            AlertDialog(
                onDismissRequest = { dialog = Dialog.NONE },
                title = { Text("Tempo & meter") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(bpm, { bpm = it }, label = { Text("BPM") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(stretch, { stretch = it })
                            Text("Change the music's speed (stretch notes). Off = only fix the beat grid.")
                        }
                        LabeledSlider("Beats per bar", beats.toFloat(), "$beats/4", 2f..7f, { beats = Math.round(it) }, steps = 4)
                        OutlinedButton(onClick = { vm.redetectTempo(); dialog = Dialog.NONE }) { Text("Auto-detect tempo again") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        bpm.replace(',', '.').toDoubleOrNull()?.let { if (Math.abs(it - s.grid.bpm) > 0.01) vm.setTempo(it, stretch) }
                        if (beats != s.grid.beatsPerBar) vm.setMeter(beats)
                        dialog = Dialog.NONE
                    }) { Text("Apply") }
                },
                dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Cancel") } },
            )
        }
        Dialog.QUANTIZE -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text("Quantise to the beat grid") },
            text = { Text("Snaps note starts and ends to the nearest 1/8 or 1/16 note at ${Math.round(s.grid.bpm)} BPM. You can undo this.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.quantize(8); dialog = Dialog.NONE }) { Text("1/8") }
                    TextButton(onClick = { vm.quantize(16); dialog = Dialog.NONE }) { Text("1/16") }
                }
            },
            dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Cancel") } },
        )
        Dialog.SPLIT -> {
            var split by remember { mutableIntStateOf(s.project?.splitPoint ?: 60) }
            AlertDialog(
                onDismissRequest = { dialog = Dialog.NONE },
                title = { Text("Right / left hand split") },
                text = { LabeledSlider("Notes at or above go to the right hand", split.toFloat(), Pitch.name(split), 36f..84f, { split = Math.round(it) }, steps = 47) },
                confirmButton = { TextButton(onClick = { vm.setSplitPoint(split); dialog = Dialog.NONE }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Cancel") } },
            )
        }
        Dialog.RENAME -> {
            var name by remember { mutableStateOf(s.project?.name ?: "") }
            AlertDialog(
                onDismissRequest = { dialog = Dialog.NONE },
                title = { Text("Rename") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.rename(name); dialog = Dialog.NONE }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = Dialog.NONE }) { Text("Cancel") } },
            )
        }
    }
}

private fun fileName(title: String?, f: ExportFormat): String {
    val base = (title ?: "PianoScribe").replace(Regex("[^A-Za-z0-9 _\\-]"), "_").trim().ifEmpty { "PianoScribe" }
    val suffix = when (f) { ExportFormat.ANALYSIS_CSV, ExportFormat.ANALYSIS_PDF -> "-analysis"; ExportFormat.NOTES_PDF, ExportFormat.NOTES_TXT -> "-notes"; else -> "" }
    return "$base$suffix.${f.ext}"
}

@Composable
private fun InfoStrip(s: ResultViewModel.UiState, position: Double) {
    val d = s.derived
    val bar = com.pianoscribe.core.theory.NoteValue.barBeat(position, s.grid)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${d.notes.size} notes · ${Math.round(s.grid.bpm)} BPM ${s.grid.beatsPerBar}/${s.grid.beatUnit}" + (d.key?.let { " · ${it.name}" } ?: ""),
            style = MaterialTheme.typography.bodySmall)
        Text(bar.label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExportDialog(onDismiss: () -> Unit, onShare: (ExportFormat) -> Unit, onSave: (ExportFormat) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Files are created on this phone. Share them or save them to a folder.", style = MaterialTheme.typography.bodySmall)
                ExportFormat.entries.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onShare(f) }) { Text("Share") }
                        TextButton(onClick = { onSave(f) }) { Text("Save") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
