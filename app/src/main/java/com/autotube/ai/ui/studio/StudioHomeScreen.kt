package com.autotube.ai.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.studio.CanvasRatio
import com.autotube.ai.studio.StudioProject
import com.autotube.ai.ui.components.AppCard
import com.autotube.ai.ui.components.ChoiceChips
import com.autotube.ai.ui.components.ConfirmDialog
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.components.GradientButton
import com.autotube.ai.ui.components.LocalImage
import com.autotube.ai.ui.components.SectionTitle
import com.autotube.ai.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Studio tab: start a new edit from gallery media or reopen a saved project. */
@Composable
fun StudioHomeScreen(
    onOpenEditor: (String) -> Unit,
    viewModel: StudioHomeViewModel = hiltViewModel(),
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var blankRatio by rememberSaveable { mutableStateOf(CanvasRatio.R9_16) }
    var renaming by remember { mutableStateOf<StudioProject?>(null) }
    var deleting by remember { mutableStateOf<StudioProject?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is StudioHomeEvent.OpenEditor -> onOpenEditor(event.id)
                is StudioHomeEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        viewModel.createFrom(uris)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
        ) {
            item {
                Column {
                    Text("Studio", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Edit your own videos: trim, split, speed, filters, text, stickers, music, voiceover and transitions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                GradientButton(
                    text = "New project",
                    icon = Icons.Rounded.AddPhotoAlternate,
                    loading = importing != null,
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                )
            }
            item {
                AppCard {
                    Text("Or start with an empty canvas", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    ChoiceChips(CanvasRatio.entries, blankRatio, { it.label }, { blankRatio = it })
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.createEmpty(blankRatio) }) { Text("Blank ${blankRatio.label} project") }
                }
            }
            if (projects.isNotEmpty()) {
                item { SectionTitle("Your projects", icon = Icons.Rounded.ContentCut, subtitle = "${projects.size} saved, auto-saved while you edit") }
            }
            items(projects, key = { it.id }) { project ->
                ProjectRow(
                    project = project,
                    onOpen = { onOpenEditor(project.id) },
                    onRename = { renaming = project },
                    onDuplicate = { viewModel.duplicate(project.id) },
                    onDelete = { deleting = project },
                )
            }
        }
    }

    importing?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importing media") },
            text = {
                Column {
                    Text("Copying your clips into the project...")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }

    renaming?.let { project ->
        RenameDialog(
            initial = project.name,
            onDismiss = { renaming = null },
            onConfirm = {
                viewModel.rename(project.id, it)
                renaming = null
            },
        )
    }

    deleting?.let { project ->
        ConfirmDialog(
            title = "Delete project?",
            message = "\"${project.name}\" and its imported media will be removed from the app. Videos already saved to your gallery stay.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.delete(project.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ProjectRow(
    project: StudioProject,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val first = project.clips.firstOrNull()
    AppCard(onClick = onOpen, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocalImage(
                path = first?.path,
                contentDescription = null,
                isVideo = first?.isImage == false,
                placeholderIcon = Icons.Rounded.Movie,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Format.clock(project.durationMs)} · ${project.clips.size} clip(s) · ${project.ratio.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(Format.date(project.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_PICK = 50

/** Opens an existing video file (e.g. an AutoTube render) as a new Studio project. */
@Composable
fun StudioImportScreen(
    path: String,
    name: String,
    onOpenEditor: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: StudioHomeViewModel = hiltViewModel(),
) {
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) { viewModel.importFile(path, name) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is StudioHomeEvent.OpenEditor -> onOpenEditor(event.id)
                is StudioHomeEvent.Message -> error = event.text
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val message = error
        if (message == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Opening in Studio...")
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message)
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}
