package com.autotube.ai.ui.review

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.QcReport
import com.autotube.ai.core.model.QcSeverity
import com.autotube.ai.core.model.UploadMode
import com.autotube.ai.ui.components.AppCard
import com.autotube.ai.ui.components.Banner
import com.autotube.ai.ui.components.BannerTone
import com.autotube.ai.ui.components.ConfirmDialog
import com.autotube.ai.ui.components.EmptyState
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.components.GradientButton
import com.autotube.ai.ui.components.IndeterminateBar
import com.autotube.ai.ui.components.LocalImage
import com.autotube.ai.ui.components.Pill
import com.autotube.ai.ui.components.RadioRow
import com.autotube.ai.ui.components.ScoreBadge
import com.autotube.ai.ui.components.SectionTitle
import com.autotube.ai.ui.components.ThinProgressBar
import com.autotube.ai.ui.components.UiIntents
import com.autotube.ai.ui.components.color
import com.autotube.ai.ui.components.label
import com.autotube.ai.ui.components.summaryLine
import com.autotube.ai.ui.theme.AutoTubeColors
import com.autotube.ai.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Review the finished video, edit SEO, choose upload options and upload to YouTube. */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onOpenProgress: (Long) -> Unit,
    onOpenError: (Long) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var regenerateScene by remember { mutableStateOf<Int?>(null) }
    var confirmRerender by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(result.resultCode, result.data)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ReviewEvent.LaunchConsent -> {
                    try {
                        consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent).build())
                    } catch (e: Exception) {
                        scope.launch { snackbar.showSnackbar("Could not open Google sign-in.") }
                    }
                }
                is ReviewEvent.OpenProgress -> onOpenProgress(event.projectId)
                is ReviewEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    val project = state.project
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Review & upload") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val path = project?.outputVideoPath
                    val title = project?.displayTitle.orEmpty()
                    if (path != null) {
                        IconButton(onClick = { UiIntents.shareVideo(context, path, title) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            project == null -> EmptyState(
                icon = Icons.Rounded.Movie,
                title = "Project not found",
                message = "This video was deleted.",
                modifier = Modifier.padding(padding),
                actionLabel = "Go back",
                onAction = onBack,
            )
            project.outputVideoPath == null -> EmptyState(
                icon = Icons.Rounded.Movie,
                title = "Not ready yet",
                message = "The video is still being made.",
                modifier = Modifier.padding(padding),
                actionLabel = "See progress",
                onAction = { onOpenProgress(project.id) },
            )
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VideoPreview(project.outputVideoPath, project.request.format)
                Column {
                    Text(project.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(project.summaryLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FileActions(
                    project = project,
                    busy = state.busy,
                    onGallery = { uri -> UiIntents.openInGallery(context, uri) },
                    onShare = { path -> UiIntents.shareVideo(context, path, project.displayTitle) },
                    onRerender = { confirmRerender = true },
                )
                project.warnings.forEach { Banner(it) }
                UploadStatus(
                    state = state,
                    onOpenYouTube = { id -> UiIntents.openUrl(context, "https://youtu.be/$id") },
                    onOpenError = { onOpenError(project.id) },
                )
                SeoCard(state, viewModel)
                ThumbnailCard(project)
                state.latestQc?.let { QcCard(it) }
                ScenesCard(state.scenes, enabled = !state.busy && !state.isUploading, onRegenerate = { regenerateScene = it })
                if (!state.isUploaded) {
                    UploadOptionsCard(
                        state = state,
                        onMode = viewModel::onModeChange,
                        onPickDate = { showDatePicker = true },
                        onPickTime = { showTimePicker = true },
                    )
                    GradientButton(
                        text = when {
                            state.isUploading -> "Uploading…"
                            state.waitingForUpload -> "Upload queued"
                            else -> "Approve & Upload"
                        },
                        onClick = viewModel::approveAndUpload,
                        enabled = state.canUpload,
                        loading = state.busy,
                        icon = Icons.Rounded.CloudUpload,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    regenerateScene?.let { scene ->
        ConfirmDialog(
            title = "Regenerate scene $scene?",
            message = "AutoTube rewrites this scene, records a new voiceover, finds new visuals and re-renders the video.",
            confirmLabel = "Regenerate",
            onConfirm = {
                regenerateScene = null
                viewModel.regenerateScene(scene)
            },
            onDismiss = { regenerateScene = null },
        )
    }
    if (confirmRerender) {
        ConfirmDialog(
            title = "Re-render the video?",
            message = "The video is exported again with the current edit. This can take a few minutes.",
            confirmLabel = "Re-render",
            onConfirm = {
                confirmRerender = false
                viewModel.rerender()
            },
            onDismiss = { confirmRerender = false },
        )
    }
    val scheduleAt = state.scheduleAtMillis ?: (System.currentTimeMillis() + 60 * 60 * 1000L)
    if (showDatePicker) {
        ScheduleDatePickerDialog(
            currentMillis = scheduleAt,
            onConfirm = {
                showDatePicker = false
                viewModel.onScheduleChange(it)
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        ScheduleTimePickerDialog(
            currentMillis = scheduleAt,
            is24Hour = DateFormat.is24HourFormat(context),
            onConfirm = {
                showTimePicker = false
                viewModel.onScheduleChange(it)
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun FileActions(
    project: Project,
    busy: Boolean,
    onGallery: (String) -> Unit,
    onShare: (String) -> Unit,
    onRerender: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val gallery = project.galleryUri
        if (gallery != null) {
            FilledTonalButton(onClick = { onGallery(gallery) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Gallery", maxLines = 1)
            }
        }
        val path = project.outputVideoPath
        if (path != null) {
            FilledTonalButton(onClick = { onShare(path) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share", maxLines = 1)
            }
        }
        OutlinedButton(
            onClick = onRerender,
            enabled = !busy && project.status != ProjectStatus.UPLOADING,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Re-render", maxLines = 1)
        }
    }
}

@Composable
private fun UploadStatus(state: ReviewUiState, onOpenYouTube: (String) -> Unit, onOpenError: () -> Unit) {
    val project = state.project ?: return
    when {
        project.status == ProjectStatus.UPLOADED -> AppCard(Modifier.fillMaxWidth(), containerColor = AutoTubeColors.Green.copy(alpha = 0.12f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = AutoTubeColors.Green)
                Spacer(Modifier.width(10.dp))
                Text("Uploaded to YouTube", style = MaterialTheme.typography.titleMedium)
            }
            val id = project.youtubeVideoId
            if (id != null) {
                Spacer(Modifier.height(8.dp))
                Text("https://youtu.be/$id", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { onOpenYouTube(id) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open on YouTube")
                }
            }
        }
        project.status == ProjectStatus.UPLOADING -> AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Uploading to YouTube", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(Format.percent(project.uploadProgress), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            ThinProgressBar(project.uploadProgress)
            Spacer(Modifier.height(6.dp))
            Text("You can leave the app; the upload continues in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.waitingForUpload -> AppCard(Modifier.fillMaxWidth()) {
            Text("Upload queued", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Starting as soon as the network allows (Wi-Fi only setting is respected).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            IndeterminateBar()
        }
        project.status == ProjectStatus.FAILED -> Banner(
            project.error?.let { "${it.title}: ${it.message}" } ?: "The last attempt failed.",
            tone = BannerTone.ERROR,
            actionLabel = "Details",
            onAction = onOpenError,
        )
    }
}

@Composable
private fun SeoCard(state: ReviewUiState, viewModel: ReviewViewModel) {
    val seo = state.seo
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("Title & description", icon = Icons.Rounded.Tag, action = {
            TextButton(onClick = viewModel::saveEdits, enabled = !state.busy) { Text("Save") }
        })
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = seo.title,
            onValueChange = viewModel::onTitleChange,
            label = { Text("Title") },
            isError = seo.titleTooLong || seo.title.isBlank(),
            supportingText = { Text("${seo.title.length}/${SeoDraft.MAX_TITLE}") },
            singleLine = false,
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = seo.description,
            onValueChange = viewModel::onDescriptionChange,
            label = { Text("Description") },
            minLines = 4,
            maxLines = 12,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = seo.tags,
            onValueChange = viewModel::onTagsChange,
            label = { Text("Tags (comma separated)") },
            isError = seo.tooManyTags,
            supportingText = {
                Text(if (seo.tooManyTags) "${seo.tagList.size}/${SeoDraft.MAX_TAGS} — only the first ${SeoDraft.MAX_TAGS} are used" else "${seo.tagList.size}/${SeoDraft.MAX_TAGS}")
            },
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = seo.hashtags,
            onValueChange = viewModel::onHashtagsChange,
            label = { Text("Hashtags") },
            placeholder = { Text("#shorts #space") },
            supportingText = { Text("${seo.hashtagList.size} hashtags") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThumbnailCard(project: Project) {
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("Thumbnail", icon = Icons.Rounded.Image)
        Spacer(Modifier.height(12.dp))
        LocalImage(
            path = project.thumbnailPath,
            contentDescription = "Thumbnail",
            modifier = Modifier
                .fillMaxWidth(if (project.request.format.isVertical) 0.5f else 1f)
                .aspectRatio(if (project.request.format.isVertical) 9f / 16f else 16f / 9f)
                .clip(RoundedCornerShape(14.dp)),
        )
    }
}

@Composable
private fun QcCard(report: QcReport) {
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("Quality check", icon = Icons.Rounded.Insights, subtitle = "Pass ${report.pass}")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(report.humanFeelScore.coerceIn(0, 10), 10)
                Spacer(Modifier.width(8.dp))
                Text("Human feel", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(report.pacingScore.coerceIn(0, 10), 10)
                Spacer(Modifier.width(8.dp))
                Text("Pacing", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (report.notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(report.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (report.issues.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("No issues found.", style = MaterialTheme.typography.bodyMedium, color = AutoTubeColors.Green)
        } else {
            Spacer(Modifier.height(10.dp))
            report.issues.forEach { issue ->
                val color = when (issue.severity) {
                    QcSeverity.INFO -> AutoTubeColors.Blue
                    QcSeverity.WARNING -> AutoTubeColors.Amber
                    QcSeverity.ERROR -> AutoTubeColors.Red
                }
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Pill(issue.severity.name.lowercase().replaceFirstChar { it.uppercase() }, color)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildString {
                            append(issue.message)
                            issue.sceneNumber?.let { append(" (scene $it)") }
                            issue.atMs?.let { append(" at ${Format.clock(it)}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenesCard(scenes: List<SceneRow>, enabled: Boolean, onRegenerate: (Int) -> Unit) {
    if (scenes.isEmpty()) return
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("Scenes", icon = Icons.Rounded.Movie, subtitle = "${scenes.size} scenes")
        scenes.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                LocalImage(
                    path = row.thumbPath,
                    isVideo = row.thumbIsVideo,
                    contentDescription = null,
                    modifier = Modifier.width(88.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${row.scene.sceneNumber}", style = MaterialTheme.typography.titleSmall)
                        Pill(row.scene.role.label, AutoTubeColors.Blue)
                        Pill(row.scene.emotion.label, row.scene.emotion.color)
                    }
                    Text(row.scene.narrationText, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onRegenerate(row.scene.sceneNumber) }, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Regenerate")
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadOptionsCard(
    state: ReviewUiState,
    onMode: (UploadMode) -> Unit,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    val enabled = !state.isUploading && !state.waitingForUpload
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("Upload options", icon = Icons.Rounded.CloudUpload)
        Spacer(Modifier.height(8.dp))
        RadioRow(
            title = "Publish now",
            subtitle = "Public as soon as YouTube finishes processing",
            selected = state.uploadMode == UploadMode.PUBLISH_NOW,
            onClick = { onMode(UploadMode.PUBLISH_NOW) },
            enabled = enabled,
        )
        RadioRow(
            title = "Schedule",
            subtitle = "Uploaded as private and published at the chosen time",
            selected = state.uploadMode == UploadMode.SCHEDULE,
            onClick = { onMode(UploadMode.SCHEDULE) },
            enabled = enabled,
        )
        if (state.uploadMode == UploadMode.SCHEDULE) {
            val at = state.scheduleAtMillis
            Column(Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (at != null) Format.dateTimeLong(at) else "No time picked", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickDate, enabled = enabled) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (at != null) Format.day(at) else "Date")
                    }
                    OutlinedButton(onClick = onPickTime, enabled = enabled) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (at != null) Format.time(at) else "Time")
                    }
                }
                state.scheduleError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        RadioRow(
            title = "Private for review",
            subtitle = "Only you can see it; publish later in YouTube Studio",
            selected = state.uploadMode == UploadMode.PRIVATE_REVIEW,
            onClick = { onMode(UploadMode.PRIVATE_REVIEW) },
            enabled = enabled,
        )
    }
}
