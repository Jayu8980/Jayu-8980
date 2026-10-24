package com.pianoscribe.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.data.Notation
import com.pianoscribe.app.data.ThemeMode
import com.pianoscribe.app.ui.components.LabeledSlider
import com.pianoscribe.core.theory.Pitch
import java.util.Locale

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onLicenses: () -> Unit) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    val store = container.settings
    val ctx = LocalContext.current
    val sf2Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            store.update { it.copy(customSoundFont = uri.toString()) }
            container.soundFonts.invalidate()
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val w = Modifier.widthIn(max = 720.dp).fillMaxWidth()
            Section("Transcription", w) {
                LabeledSlider("Onset threshold", s.onsetThreshold, String.format(Locale.US, "%.2f", s.onsetThreshold), 0.05f..0.95f,
                    { v -> store.update { it.copy(onsetThreshold = v) } }, help = "Lower finds more note starts (and more false ones).")
                LabeledSlider("Note threshold", s.frameThreshold, String.format(Locale.US, "%.2f", s.frameThreshold), 0.05f..0.95f,
                    { v -> store.update { it.copy(frameThreshold = v) } }, help = "Lower keeps quieter and longer notes.")
                LabeledSlider("Minimum note length", s.minNoteMs, "${Math.round(s.minNoteMs)} ms", 20f..500f,
                    { v -> store.update { it.copy(minNoteMs = v) } })
                Text("Pitch range: ${Pitch.name(s.minPitch)} – ${Pitch.name(s.maxPitch)}", style = MaterialTheme.typography.bodyLarge)
                RangeSlider(
                    value = s.minPitch.toFloat()..s.maxPitch.toFloat(),
                    onValueChange = { r -> store.update { it.copy(minPitch = Math.round(r.start), maxPitch = maxOf(Math.round(r.start) + 1, Math.round(r.endInclusive))) } },
                    valueRange = 21f..108f, steps = 86,
                )
                Text("Piano keys A0 (MIDI 21) to C8 (MIDI 108).", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Piano only mode", style = MaterialTheme.typography.bodyLarge)
                        Text("Quantise to the beat grid using the detected tempo.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(s.pianoOnly, { v -> store.update { it.copy(pianoOnly = v) } })
                }
                if (s.pianoOnly) SingleChoiceSegmentedButtonRow {
                    SegmentedButton(s.quantizeDivision == 8, { store.update { it.copy(quantizeDivision = 8) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("1/8 notes") }
                    SegmentedButton(s.quantizeDivision == 16, { store.update { it.copy(quantizeDivision = 16) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("1/16 notes") }
                }
                LabeledSlider("Hand split point", s.splitPoint.toFloat(), Pitch.name(s.splitPoint), 36f..84f,
                    { v -> store.update { it.copy(splitPoint = Math.round(v)) } }, steps = 47, help = "Notes at or above go to the right hand. Default: middle C (C4).")
                LabeledSlider("Beats per bar", s.beatsPerBar.toFloat(), "${s.beatsPerBar}/4", 2f..7f, { v -> store.update { it.copy(beatsPerBar = Math.round(v)) } }, steps = 4)
                TextButton(onClick = store::reset) { Text("Reset transcription defaults") }
            }
            Section("Notation", w) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(s.notation == Notation.WESTERN, { store.update { it.copy(notation = Notation.WESTERN) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Western (C D E)") }
                    SegmentedButton(s.notation == Notation.SARGAM, { store.update { it.copy(notation = Notation.SARGAM) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Sargam (Sa Re Ga)") }
                }
                Text("Sa (tonic) for Sargam: ${Pitch.pitchClassName(s.saPitchClass)}", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0 until 12).forEach { pc ->
                        FilterChip(selected = s.saPitchClass == pc, onClick = { store.update { it.copy(saPitchClass = pc) } }, label = { Text(Pitch.pitchClassName(pc)) })
                    }
                }
            }
            Section("Appearance", w) {
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.theme == m, { store.update { it.copy(theme = m) } }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                            Text(when (m) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use wallpaper colours (Android 12+)", Modifier.weight(1f))
                    Switch(s.dynamicColor, { v -> store.update { it.copy(dynamicColor = v) } })
                }
            }
            Section("Piano sound", w) {
                Text(if (s.customSoundFont == null) "Built-in PianoScribe Grand SoundFont" else "Custom SoundFont: " + (android.net.Uri.parse(s.customSoundFont).lastPathSegment ?: "selected file"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sf2Picker.launch(arrayOf("*/*")) }) { Text("Choose .sf2 file") }
                    if (s.customSoundFont != null) TextButton(onClick = { store.update { it.copy(customSoundFont = null) }; container.soundFonts.invalidate() }) { Text("Use built-in") }
                }
                Text("Any General MIDI SoundFont works; bank 0 program 0 (Acoustic Grand Piano) is used. Takes effect when you reopen a project.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Section("About", w) {
                Text("PianoScribe works fully offline: no internet permission, no accounts, no cloud. Audio and projects stay on this device.")
                Text("Accuracy is best for solo piano or a single instrument; full-band songs give approximate results.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onLicenses) { Text("Licenses & credits") }
            }
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            content()
        }
    }
}
