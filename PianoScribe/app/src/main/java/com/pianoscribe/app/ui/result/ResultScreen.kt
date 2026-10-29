package com.pianoscribe.app.ui.result

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.pianoscribe.core.arrange.Style
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
import com.pianoscribe.app.export.Exporter
import com.pianoscribe.app.ui.components.LabeledSlider
import com.pianoscribe.core.theory.Pitch

private enum class ResultTab(val title: String) { ROLL("Piano Roll"), FALLING("Falling Notes"), SHEET("Sheet"), GUITAR("Guitar Tab"), NOTES("Notes"), ANALYSER("Analyser"), COMPARE("Compare") }

private enum class Dialog { NONE, MIDI, EXPORT, TRANSPOSE, TEMPO, QUANTIZE, SPLIT, RENAME, SONG }

/**
 * "Save as" that passes the export's real MIME type. The stock CreateDocument contract fixes one type
 * at registration, and a wildcard type makes many document providers refuse to create the file.
 */
private class CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

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
    // Saveable: the system "Save as" screen can outlive this composition.
    var pendingSave by rememberSaveable { mutableStateOf<ExportFormat?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        val f = pendingSave
        if (uri != null && f != null) vm.saveTo(f, uri)
        pendingSave = null
    }
    fun saveAs(f: ExportFormat) {
        pendingSave = f
        saveLauncher.launch(Exporter.fileName(vm.exportName(), f, settings.instrument) to f.mime)
    }
    // Downloads needs Android 10+; older phones get the system "Save as" screen instead.
    fun saveToDownloads(f: ExportFormat) = vm.saveToDownloads(f) { saveAs(f) }

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
                    FilledTonalButton(onClick = { dialog = Dialog.MIDI }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Text(" MIDI")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export (MusicXML, PNG, PDF, CSV…)") },
                                leadingIcon = { Icon(Icons.Default.IosShare, null) },
                                onClick = { menu = false; dialog = Dialog.EXPORT },
                            )
                            DropdownMenuItem(text = { Text("Make a song (chords, bass, drums)…") }, onClick = { menu = false; dialog = Dialog.SONG })
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
            if (!s.loading && s.error == null) TransportBar(vm, s, position, playing, settings.instrument, settings.humanFeel)
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
                    DifficultyStrip(s.difficulty, vm::setDifficulty)
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
                            ResultTab.FALLING -> FallingNotes(d.notes, s.grid, flats, position, d.duration, d.pitchLo, d.pitchHi, d.fingers, Modifier.fillMaxSize(), onSeek = vm::seek)
                            ResultTab.SHEET -> SheetView(
                                d.notes, s.grid, d.key, s.project?.name, position, playing, s.selectedId, Modifier.fillMaxSize(),
                                editable = !s.easy, onSeek = vm::seek, onSelect = vm::select, onPitch = vm::fixPitch, onDelete = vm::fixDelete,
                            )
                            ResultTab.GUITAR -> TabView(d.notes, s.grid, position, Modifier.fillMaxSize(), onSeek = vm::seek)
                            ResultTab.NOTES -> NotesList(
                                d.notes, s.grid, settings.notation, settings.saPitchClass, flats, s.selectedId,
                                onNotation = vm::setNotation, onSelect = { n -> vm.select(n.id); vm.seek(n.start) }, modifier = Modifier.fillMaxSize(),
                            )
                            ResultTab.ANALYSER -> AnalyserView(vm, s, settings, position, playing)
                            ResultTab.COMPARE -> CompareView(vm, s, position, playing)
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        Dialog.NONE -> {}
        Dialog.MIDI -> {
            var performed by rememberSaveable { mutableStateOf(false) }
            val f = if (performed) ExportFormat.MIDI_PERFORMED else ExportFormat.MIDI
            MidiDialog(
                noteCount = s.derived.notes.size,
                fileName = Exporter.fileName(vm.exportName(), f, settings.instrument),
                instrument = settings.instrument.label,
                performed = performed,
                onPerformed = { performed = it },
                onDismiss = { dialog = Dialog.NONE },
                onDownloads = { dialog = Dialog.NONE; saveToDownloads(f) },
                onOpen = { dialog = Dialog.NONE; vm.open(f) },
                onShare = { dialog = Dialog.NONE; vm.share(f) },
                onSaveAs = { dialog = Dialog.NONE; saveAs(f) },
                onWav = { dialog = Dialog.NONE; saveToDownloads(ExportFormat.WAV) },
                onMore = { dialog = Dialog.EXPORT },
            )
        }
        Dialog.EXPORT -> ExportDialog(
            onDismiss = { dialog = Dialog.NONE },
            onDownloads = { f -> dialog = Dialog.NONE; saveToDownloads(f) },
            onShare = { f -> dialog = Dialog.NONE; vm.share(f) },
            onSaveAs = { f -> dialog = Dialog.NONE; saveAs(f) },
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
        Dialog.SONG -> SongDialog(vm, settings.instrument.label) { vm.stopSong(); dialog = Dialog.NONE }
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


/** "Make a song": the tune of the notes with chords, bass and drums in a chosen style. */
@Composable
private fun SongDialog(vm: ResultViewModel, instrument: String, onClose: () -> Unit) {
    val song by vm.song.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Make a song") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The tune of these notes, played on $instrument, with chords, bass and drums that fit it.", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Style.entries.forEachIndexed { i, st ->
                        SegmentedButton(song.style == st, { vm.setSongStyle(st) }, SegmentedButtonDefaults.itemShape(i, Style.entries.size), enabled = !song.making) {
                            Text(st.label, maxLines = 1)
                        }
                    }
                }
                if (song.making) {
                    LinearProgressIndicator(progress = { song.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Making the song… ${(song.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                song.progression?.let { Text("Chords: $it", style = MaterialTheme.typography.bodyMedium) }
                if (song.progression != null && !song.making) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (song.playing) vm.stopSong() else vm.playSong() }) { Text(if (song.playing) "Stop" else "Play") }
                        OutlinedButton(onClick = { vm.saveSong(midi = false) { vm.shareSong(midi = false) } }) { Text("Save audio") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.saveSong(midi = true) { vm.shareSong(midi = true) } }) { Text("Save MIDI") }
                        OutlinedButton(onClick = { vm.shareSong(midi = false) }) { Text("Share") }
                    }
                }
                song.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = vm::makeSong, enabled = !song.making) { Text(if (song.progression == null) "Make song" else "Make again") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Difficulty, above every view of the notes: the simplified versions or every note. */
@Composable
private fun DifficultyStrip(difficulty: Difficulty, onChange: (Difficulty) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Difficulty.entries.forEachIndexed { i, d ->
                SegmentedButton(difficulty == d, { onChange(d) }, SegmentedButtonDefaults.itemShape(i, Difficulty.entries.size)) { Text(d.label, maxLines = 1) }
            }
        }
        if (difficulty != Difficulty.ADVANCED) Text(
            difficulty.help + " Every note is kept: choose Advanced to see them all.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
private fun MidiDialog(
    noteCount: Int,
    fileName: String,
    instrument: String,
    performed: Boolean,
    onPerformed: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onDownloads: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSaveAs: () -> Unit,
    onWav: () -> Unit,
    onMore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Download, null) },
        title = { Text("MIDI file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Performed version", style = MaterialTheme.typography.bodyMedium)
                        Text("Played on $instrument with human feel", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(performed, onPerformed)
                }
                Text(
                    if (performed) "$fileName · right and left hand on separate tracks, $instrument."
                    else "$fileName · $noteCount notes exactly as transcribed · right and left hand on separate tracks, piano.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onDownloads, modifier = Modifier.fillMaxWidth()) { Text("Save to Downloads") }
                OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open in a MIDI app") }
                OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Share…") }
                OutlinedButton(onClick = onSaveAs, modifier = Modifier.fillMaxWidth()) { Text("Save as… (choose folder)") }
                HorizontalDivider()
                FilledTonalButton(onClick = onWav, modifier = Modifier.fillMaxWidth()) { Text("Save audio on $instrument (WAV)") }
                TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("Other formats: MusicXML, PNG, PDF, CSV") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onDownloads: (ExportFormat) -> Unit,
    onShare: (ExportFormat) -> Unit,
    onSaveAs: (ExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Files are created on this phone. \"Save\" puts them in Downloads/PianoScribe.", style = MaterialTheme.typography.bodySmall)
                ExportFormat.entries.forEach { f ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(f.label, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { onDownloads(f) }) { Text("Save") }
                        TextButton(onClick = { onShare(f) }) { Text("Share") }
                        TextButton(onClick = { onSaveAs(f) }) { Text("Save as…") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
