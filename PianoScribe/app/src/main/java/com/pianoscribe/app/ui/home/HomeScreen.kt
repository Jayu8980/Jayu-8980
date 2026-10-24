package com.pianoscribe.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pianoscribe.app.AppContainer
import com.pianoscribe.app.data.ProjectEntity
import com.pianoscribe.app.ui.components.formatDuration
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    container: AppContainer,
    onImport: (Uri) -> Unit,
    onRecord: () -> Unit,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val projects by container.repository.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val shared by container.pendingSharedUri.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onImport(uri) }
    var renaming by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleting by remember { mutableStateOf<ProjectEntity?>(null) }

    LaunchedEffect(shared) {
        shared?.let { container.pendingSharedUri.value = null; onImport(it) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("PianoScribe", fontWeight = FontWeight.Bold) },
            actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
        )
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Turn music into piano notes, a piano roll and a MIDI file. Everything runs on this phone, offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ElevatedButton(
                            onClick = { picker.launch(arrayOf("audio/*", "application/ogg", "video/mp4")) },
                            modifier = Modifier.weight(1f).height(96.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FileOpen, null, Modifier.size(32.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("Import Audio", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        FilledTonalButton(onClick = onRecord, modifier = Modifier.weight(1f).height(96.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Mic, null, Modifier.size(32.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("Record", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Text("MP3, WAV, M4A, AAC, OGG and FLAC. Best results with solo piano or one instrument.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Recent projects", style = MaterialTheme.typography.titleMedium)
                    if (projects.isEmpty()) {
                        Text("No projects yet. Import a song or record one to get started.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(projects, key = { it.id }) { p ->
                ProjectRow(p, onOpen = { onOpen(p.id) }, onRename = { renaming = p }, onDelete = { deleting = p })
            }
        }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { container.repository.updateMeta(p.copy(name = name.ifBlank { p.name })) }; renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("The transcription and its audio are removed from this phone. Exported files are kept.") },
            confirmButton = { TextButton(onClick = { scope.launch { container.repository.delete(p.id) }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectRow(p: ProjectEntity, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.updatedAt))
                Text(
                    "${formatDuration(p.durationSec)} · ${p.noteCount} notes · ${Math.round(p.bpm)} BPM" + (p.keyName?.let { " · $it" } ?: "") + "\n$date",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
