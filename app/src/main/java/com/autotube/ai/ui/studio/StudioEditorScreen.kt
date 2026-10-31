package com.autotube.ai.ui.studio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.studio.AudioKind
import com.autotube.ai.studio.StudioProject
import com.autotube.ai.studio.TextLayer
import com.autotube.ai.ui.components.ChoiceChips
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.components.OnLifecycleEvent
import com.autotube.ai.ui.components.UiIntents
import com.autotube.ai.ui.theme.AutoTubeColors
import kotlinx.coroutines.launch

private class ToolItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** The manual editor: preview, transport, timeline and context toolbar / tool panels. */
@Composable
fun StudioEditorScreen(
    onBack: () -> Unit,
    viewModel: StudioEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var textDialog by remember { mutableStateOf<TextDialogState?>(null) }
    var renaming by remember { mutableStateOf(false) }

    OnLifecycleEvent(Lifecycle.Event.ON_STOP) {
        viewModel.pause()
        viewModel.stopRecording()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                is EditorEvent.EditText -> textDialog = TextDialogState(event.layer)
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { viewModel.addMedia(it) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.addAudio(it, AudioKind.MUSIC) } }
    val videoAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.addAudio(it, AudioKind.EXTRACTED) } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.openPanel(Panel.RECORD) else scope.launch { snackbar.showSnackbar("Microphone access is needed to record a voiceover.") }
    }
    val pickMedia = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    val pickAudio = { audioPicker.launch("audio/*") }
    val openRecord = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.openPanel(Panel.RECORD)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler {
        when {
            state.recording -> viewModel.stopRecording()
            state.panel != null -> viewModel.openPanel(null)
            state.selection != Selection.None -> viewModel.select(Selection.None)
            else -> onBack()
        }
    }

    val project = state.project
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AutoTubeColors.Background,
    ) { padding ->
        if (project == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.missing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("This project could not be opened.")
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            TopBar(
                name = project.name,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onBack = onBack,
                onRename = { renaming = true },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onExport = viewModel::openExport,
            )
            val selectedTextId = (state.selection as? Selection.Text)?.id
            StudioPreview(
                player = viewModel.player,
                project = project,
                playheadMs = state.playheadMs,
                selectedTextId = selectedTextId,
                onSelectText = { id -> viewModel.select(if (id == null) Selection.None else Selection.Text(id)) },
                onTogglePlay = viewModel::togglePlay,
                onEditText = { id -> viewModel.select(Selection.Text(id)); viewModel.requestEditText() },
                onTextGestureStart = viewModel::beginGesture,
                onTextTransform = { id, dx, dy, zoom ->
                    viewModel.updateText(id) { it.copy(x = it.x + dx, y = it.y + dy, size = it.size * zoom) }
                },
                onTextGestureEnd = viewModel::endGesture,
                onAddMedia = pickMedia,
                modifier = Modifier.weight(1f),
            )
            Transport(project, state.playheadMs, state.playing, viewModel::togglePlay)
            state.previewNote?.let { note ->
                Row(
                    Modifier.fillMaxWidth().background(AutoTubeColors.SurfaceHigh).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = viewModel::dismissPreviewNote) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss") }
                }
            }
            val actions = remember(viewModel) {
                TimelineActions(
                    onScrub = viewModel::seekTo,
                    onSelect = viewModel::select,
                    onGestureStart = viewModel::beginGesture,
                    onGestureEnd = viewModel::endGesture,
                    onTrimClip = viewModel::trimClip,
                    onPlaceText = { id, s, e -> viewModel.updateText(id) { it.copy(startMs = s, endMs = e) } },
                    onPlaceAudio = viewModel::placeAudio,
                    onTransition = { id ->
                        viewModel.select(Selection.Clip(id))
                        viewModel.openPanel(Panel.TRANSITION)
                    },
                    onAddMedia = pickMedia,
                    onAddText = viewModel::requestNewText,
                    onAddAudio = pickAudio,
                )
            }
            StudioTimeline(
                project = project,
                playheadMs = state.playheadMs,
                selection = state.selection,
                actions = actions,
                modifier = Modifier.height(if (state.panel != null) 150.dp else 200.dp),
            )
            BottomArea(state, project, viewModel, pickMedia, pickAudio, { videoAudioPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, openRecord)
        }
    }

    state.importing?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importing") },
            text = { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) },
        )
    }

    textDialog?.let { dialog ->
        TextEntryDialog(
            initial = dialog.layer?.text.orEmpty(),
            isNew = dialog.layer == null,
            onDismiss = { textDialog = null },
            onConfirm = { text ->
                viewModel.commitText(dialog.layer, text)
                textDialog = null
            },
        )
    }

    if (renaming && project != null) {
        RenameDialog(project.name, onDismiss = { renaming = false }, onConfirm = { viewModel.rename(it); renaming = false })
    }

    if (project != null) {
        ExportDialog(
            state = state.export,
            defaultShortSide = project.exportShortSide,
            onStart = viewModel::startExport,
            onCancel = viewModel::cancelExport,
            onClose = viewModel::closeExport,
            onShare = { path -> UiIntents.shareVideo(context, path, project.name) },
            onOpen = { uri -> UiIntents.openInGallery(context, uri) },
        )
    }
}

private class TextDialogState(val layer: TextLayer?)

@Composable
private fun TopBar(
    name: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onRename),
        )
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo") }
        Button(onClick = onExport, modifier = Modifier.padding(end = 8.dp)) {
            Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Export")
        }
    }
}

@Composable
private fun Transport(project: StudioProject, playheadMs: Long, playing: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${Format.clock(playheadMs)} / ${Format.clock(project.durationMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(AutoTubeColors.SurfaceHighest).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
        }
        Text(
            "${project.ratio.label} · ${project.clips.size} clips",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun BottomArea(
    state: EditorUiState,
    project: StudioProject,
    vm: StudioEditorViewModel,
    pickMedia: () -> Unit,
    pickAudio: () -> Unit,
    pickVideoAudio: () -> Unit,
    openRecord: () -> Unit,
) {
    val done = { vm.openPanel(null) }
    val sel = state.selection
    val clip = (sel as? Selection.Clip)?.let { project.clip(it.id) }
    val text = (sel as? Selection.Text)?.let { project.text(it.id) }
    val audio = (sel as? Selection.Audio)?.let { project.audioLayer(it.id) }

    when (state.panel) {
        Panel.CANVAS -> return CanvasPanel(project, vm::setRatio, vm::setProjectFit, done)
        Panel.STICKERS -> return StickersPanel(vm::addSticker, done)
        Panel.RECORD -> return RecordPanel(state.recording, state.recordingElapsedMs, vm::startRecording, vm::stopRecording) {
            if (state.recording) vm.stopRecording()
            done()
        }
        else -> Unit
    }
    if (clip != null) {
        when (state.panel) {
            Panel.SPEED -> return SpeedPanel(clip, { s -> vm.updateClip(clip.id) { it.copy(speed = s) } }, done)
            Panel.IMAGE_DURATION -> return ImageDurationPanel(clip, { ms -> vm.trimClip(clip.id, 0L, ms) }, done)
            Panel.VOLUME -> return VolumePanel(clip.volume, !clip.isImage && clip.hasAudio, { v -> vm.updateClip(clip.id) { it.copy(volume = v) } }, done)
            Panel.FILTERS -> return FiltersPanel(
                clip,
                { f, s -> vm.updateClip(clip.id) { it.copy(filter = f, filterStrength = s) } },
                { vm.updateAllClips { it.copy(filter = clip.filter, filterStrength = clip.filterStrength) } },
                done,
            )
            Panel.ADJUST -> return AdjustPanel(clip, { a -> vm.updateClip(clip.id) { it.copy(adjust = a) } }, { vm.updateAllClips { it.copy(adjust = clip.adjust) } }, done)
            Panel.TRANSFORM -> return TransformPanel(clip, project.fit, { c -> vm.updateClip(clip.id) { c } }, done)
            Panel.TRANSITION -> return TransitionPanel(
                clip,
                isLast = project.clips.lastOrNull()?.id == clip.id,
                onChange = { t, ms -> vm.updateClip(clip.id) { it.copy(transition = t, transitionMs = ms) } },
                onApplyAll = { vm.updateAllClips { it.copy(transition = clip.transition, transitionMs = clip.transitionMs) } },
                onDone = done,
            )
            else -> Unit
        }
    }
    if (text != null) {
        when (state.panel) {
            Panel.TEXT_STYLE -> return TextStylePanel(text, { t -> vm.updateText(text.id) { t } }, done)
            Panel.TEXT_ANIM -> return TextAnimPanel(text, { a -> vm.updateText(text.id) { it.copy(anim = a) } }, done)
            else -> Unit
        }
    }
    if (audio != null) {
        when (state.panel) {
            Panel.AUDIO_VOLUME -> return VolumePanel(audio.volume, true, { v -> vm.updateAudio(audio.id) { it.copy(volume = v) } }, done, title = "${audio.kind.label} volume")
            Panel.AUDIO_FADE -> return AudioFadePanel(audio, { a -> vm.updateAudio(audio.id) { a } }, done)
            else -> Unit
        }
    }

    val tools = when {
        clip != null -> buildList {
            add(ToolItem("Split", Icons.Rounded.ContentCut, vm::split))
            if (clip.isImage) {
                add(ToolItem("Duration", Icons.Rounded.Timer) { vm.openPanel(Panel.IMAGE_DURATION) })
            } else {
                add(ToolItem("Speed", Icons.Rounded.Speed) { vm.openPanel(Panel.SPEED) })
                add(ToolItem("Volume", Icons.Rounded.VolumeUp) { vm.openPanel(Panel.VOLUME) })
            }
            add(ToolItem("Filters", Icons.Rounded.PhotoFilter) { vm.openPanel(Panel.FILTERS) })
            add(ToolItem("Adjust", Icons.Rounded.Tune) { vm.openPanel(Panel.ADJUST) })
            add(ToolItem("Transform", Icons.Rounded.Crop) { vm.openPanel(Panel.TRANSFORM) })
            add(ToolItem("Transition", Icons.Rounded.SwapHoriz) { vm.openPanel(Panel.TRANSITION) })
            add(ToolItem("Freeze", Icons.Rounded.AcUnit, vm::freezeFrame))
            if (!clip.isImage) add(ToolItem("Extract audio", Icons.Rounded.GraphicEq, vm::extractAudio))
            add(ToolItem("Delete left", Icons.Rounded.FirstPage) { vm.trimToPlayhead(keepRight = true) })
            add(ToolItem("Delete right", Icons.Rounded.LastPage) { vm.trimToPlayhead(keepRight = false) })
            add(ToolItem("Duplicate", Icons.Rounded.ContentCopy, vm::duplicateSelected))
            add(ToolItem("Move left", Icons.Rounded.ChevronLeft) { vm.moveSelectedClip(-1) })
            add(ToolItem("Move right", Icons.Rounded.ChevronRight) { vm.moveSelectedClip(1) })
            add(ToolItem("Delete", Icons.Rounded.Delete, vm::deleteSelected))
        }
        text != null -> buildList {
            if (!text.sticker) add(ToolItem("Edit", Icons.Rounded.Edit, vm::requestEditText))
            add(ToolItem("Style", Icons.Rounded.Palette) { vm.openPanel(Panel.TEXT_STYLE) })
            add(ToolItem("Animation", Icons.Rounded.Animation) { vm.openPanel(Panel.TEXT_ANIM) })
            add(ToolItem("Split", Icons.Rounded.ContentCut, vm::split))
            add(ToolItem("Duplicate", Icons.Rounded.ContentCopy, vm::duplicateSelected))
            add(ToolItem("Delete", Icons.Rounded.Delete, vm::deleteSelected))
        }
        audio != null -> buildList {
            add(ToolItem("Volume", Icons.Rounded.VolumeUp) { vm.openPanel(Panel.AUDIO_VOLUME) })
            add(ToolItem("Fade", Icons.Rounded.Gradient) { vm.openPanel(Panel.AUDIO_FADE) })
            add(ToolItem("Split", Icons.Rounded.ContentCut, vm::split))
            add(ToolItem("Duplicate", Icons.Rounded.ContentCopy, vm::duplicateSelected))
            add(ToolItem("Delete", Icons.Rounded.Delete, vm::deleteSelected))
        }
        else -> listOf(
            ToolItem("Add media", Icons.Rounded.AddPhotoAlternate, pickMedia),
            ToolItem("Edit", Icons.Rounded.ContentCut) {
                val i = project.clipIndexAt(state.playheadMs)
                if (i >= 0) vm.select(Selection.Clip(project.clips[i].id))
            },
            ToolItem("Music", Icons.Rounded.LibraryMusic, pickAudio),
            ToolItem("Video sound", Icons.Rounded.Movie, pickVideoAudio),
            ToolItem("Record", Icons.Rounded.Mic, openRecord),
            ToolItem("Text", Icons.Rounded.TextFields, vm::requestNewText),
            ToolItem("Stickers", Icons.Rounded.EmojiEmotions) { vm.openPanel(Panel.STICKERS) },
            ToolItem("Canvas", Icons.Rounded.AspectRatio) { vm.openPanel(Panel.CANVAS) },
        )
    }
    Toolbar(tools, showClose = sel != Selection.None) { vm.select(Selection.None) }
}

@Composable
private fun Toolbar(tools: List<ToolItem>, showClose: Boolean, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(AutoTubeColors.Surface).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showClose) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close tools") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            items(tools, key = { it.label }) { tool ->
                Column(
                    Modifier
                        .widthIn(min = 64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = tool.onClick)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tool.icon, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.height(2.dp))
                    Text(tool.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TextEntryDialog(initial: String, isNew: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add text" else "Edit text") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(300) },
                placeholder = { Text("Type something") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank() || !isNew) { Text(if (isNew) "Add" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val Resolutions = listOf(720, 1080, 1440, 2160)

private fun resolutionLabel(side: Int) = when (side) {
    720 -> "720p"
    1080 -> "1080p HD"
    1440 -> "2K"
    else -> "4K"
}

@Composable
private fun ExportDialog(
    state: ExportState,
    defaultShortSide: Int,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onShare: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    when (state) {
        ExportState.Idle -> Unit
        ExportState.Choosing -> {
            var side by remember { mutableIntStateOf(defaultShortSide) }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Export video") },
                text = {
                    Column {
                        Text("Resolution", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        ChoiceChips(Resolutions, side, ::resolutionLabel, { side = it })
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "H.264 MP4, saved to your gallery (Movies/AutoTube AI). 4K needs a recent phone; if it fails the app retries at a safer setting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { Button(onClick = { onStart(side) }) { Text("Export") } },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            )
        }
        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting ${Format.percent(state.progress)}") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Keep the app open until it finishes.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Video exported") },
            text = {
                Text(
                    if (state.galleryUri != null) "Saved to your gallery. Share it or keep editing." else "Exported. Saving to the gallery failed, but you can still share it.",
                )
            },
            confirmButton = { Button(onClick = { onShare(state.path) }) { Text("Share") } },
            dismissButton = {
                Row {
                    state.galleryUri?.let { uri -> TextButton(onClick = { onOpen(uri) }) { Text("Open") } }
                    TextButton(onClick = onClose) { Text("Done") }
                }
            },
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Export failed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
    }
}
