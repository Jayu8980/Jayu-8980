package com.autotube.ai.ui.progress

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.StepStatus
import com.autotube.ai.core.model.TopicCandidate
import com.autotube.ai.core.model.UiStage
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
import com.autotube.ai.ui.components.ProgressRing
import com.autotube.ai.ui.components.ScoreBadge
import com.autotube.ai.ui.components.SectionTitle
import com.autotube.ai.ui.components.StatusPill
import com.autotube.ai.ui.components.ThinProgressBar
import com.autotube.ai.ui.theme.AutoTubeColors
import com.autotube.ai.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Live progress: stage stepper, preview, log, topic picker, cancel/resume. */
@Composable
fun ProgressScreen(
    onBack: () -> Unit,
    onOpenReview: (Long) -> Unit,
    onOpenError: (Long) -> Unit,
    viewModel: ProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProgressEvent.OpenError -> onOpenError(event.projectId)
                is ProgressEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
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
                title = {
                    Column {
                        Text(project?.displayTitle ?: "Creating video", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (project != null) {
                            Text(project.request.format.label + " · " + Format.durationSec(project.request.targetDurationSec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            project == null -> EmptyState(
                icon = Icons.Rounded.Description,
                title = "Project not found",
                message = "This video was deleted.",
                modifier = Modifier.padding(padding),
                actionLabel = "Go back",
                onAction = onBack,
            )
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OverviewCard(
                    project = project,
                    overall = state.overallProgress,
                    onCancel = { confirmCancel = true },
                )
                project.warnings.forEach { Banner(it) }
                StatusActions(
                    project = project,
                    busy = state.busy,
                    onReview = { onOpenReview(project.id) },
                    onError = { onOpenError(project.id) },
                    onResume = viewModel::resume,
                )
                if (project.status == ProjectStatus.AWAITING_TOPIC) {
                    TopicPicker(state.topics, state.busy, viewModel::chooseTopic)
                }
                PreviewCard(state)
                Stepper(state.stages)
                LogCard(state.logVisible, state.logTail, viewModel::toggleLog)
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = "Cancel this video?",
            message = "Background work stops now. Finished steps are kept, so you can resume later.",
            confirmLabel = "Cancel video",
            destructive = true,
            dismissLabel = "Keep going",
            onConfirm = {
                confirmCancel = false
                viewModel.cancel()
            },
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun OverviewCard(project: Project, overall: Float, onCancel: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(progress = overall, size = 92.dp) {
                Text(Format.percent(overall), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(project.status)
                val step = project.currentStep
                if (step != null && project.status.isActive) {
                    Text(step.title, style = MaterialTheme.typography.titleMedium)
                }
                val message = project.statusMessage.ifBlank {
                    when (project.status) {
                        ProjectStatus.QUEUED -> "Waiting to start…"
                        ProjectStatus.RUNNING -> "Working…"
                        ProjectStatus.AWAITING_TOPIC -> "Pick the topic you like best."
                        ProjectStatus.AWAITING_REVIEW -> "Your video is ready."
                        ProjectStatus.UPLOADING -> "Uploading to YouTube…"
                        ProjectStatus.UPLOADED -> "Uploaded to YouTube."
                        ProjectStatus.FAILED -> "Something went wrong."
                        ProjectStatus.CANCELLED -> "Cancelled."
                    }
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (project.status.isActive) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun StatusActions(
    project: Project,
    busy: Boolean,
    onReview: () -> Unit,
    onError: () -> Unit,
    onResume: () -> Unit,
) {
    when (project.status) {
        ProjectStatus.AWAITING_REVIEW, ProjectStatus.UPLOADED -> GradientButton(
            text = "Review video",
            onClick = onReview,
            icon = Icons.Rounded.PlayCircle,
        )
        ProjectStatus.FAILED -> Banner(
            project.error?.title ?: "This video could not be finished.",
            tone = BannerTone.ERROR,
            actionLabel = "Details",
            onAction = onError,
        )
        ProjectStatus.CANCELLED -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Banner("Cancelled. Finished steps were kept.", tone = BannerTone.INFO)
            GradientButton(text = "Resume", onClick = onResume, loading = busy, icon = Icons.Rounded.Replay)
        }
        ProjectStatus.QUEUED, ProjectStatus.RUNNING, ProjectStatus.UPLOADING, ProjectStatus.AWAITING_TOPIC -> Unit
    }
}

@Composable
private fun TopicPicker(topics: List<TopicCandidate>, busy: Boolean, onChoose: (TopicCandidate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap)) {
        SectionTitle("Pick a topic", icon = Icons.Rounded.Lightbulb, subtitle = "Ranked by how well similar videos are doing")
        if (topics.isEmpty()) {
            Banner("No topic ideas were found. Cancel and try a different niche.", tone = BannerTone.INFO)
        }
        topics.forEachIndexed { index, topic ->
            AppCard(
                Modifier.fillMaxWidth(),
                onClick = { if (!busy) onChoose(topic) },
                containerColor = if (index == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (index == 0) Pill("Best pick", AutoTubeColors.Amber)
                        Text(topic.title, style = MaterialTheme.typography.titleMedium)
                        if (topic.angle.isNotBlank()) {
                            Text(topic.angle, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (topic.reason.isNotBlank()) {
                            Text(topic.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    ScoreBadge(topic.score.coerceIn(0, 100), 100)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (busy) "Starting…" else "Use this topic →",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(state: ProgressUiState) {
    val project = state.project ?: return
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        val ratio = if (project.request.format.isVertical) 4f / 5f else 16f / 9f
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .align(Alignment.CenterHorizontally),
        ) {
            LocalImage(
                path = state.previewImagePath,
                isVideo = state.previewIsVideo,
                contentDescription = "Live preview",
                modifier = Modifier.fillMaxSize(),
            )
            Pill(
                "Live preview",
                AutoTubeColors.Red,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
        }
        val scene = state.scenePreview
        Column(Modifier.padding(Dimens.CardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (scene != null) {
                Text("Scene ${scene.sceneNumber} of ${scene.totalScenes}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (scene.onScreenText.isNotBlank()) {
                    Text(scene.onScreenText, style = MaterialTheme.typography.titleSmall)
                }
                Text(scene.narration, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
            } else {
                Text(
                    "The script and visuals will appear here as they are made.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (project.status.isActive) IndeterminateBar(Modifier.padding(top = 6.dp), height = 3.dp)
        }
    }
}

private fun UiStage.icon(): ImageVector = when (this) {
    UiStage.RESEARCH -> Icons.Rounded.TravelExplore
    UiStage.SCRIPT -> Icons.Rounded.EditNote
    UiStage.LENGTH_CHECK -> Icons.Rounded.Timer
    UiStage.VOICE -> Icons.Rounded.RecordVoiceOver
    UiStage.MUSIC -> Icons.Rounded.MusicNote
    UiStage.EDITING -> Icons.Rounded.ContentCut
    UiStage.UPLOAD -> Icons.Rounded.CloudUpload
}

private fun StepStatus.color(): Color = when (this) {
    StepStatus.PENDING -> AutoTubeColors.Grey
    StepStatus.RUNNING -> AutoTubeColors.Red
    StepStatus.DONE -> AutoTubeColors.Green
    StepStatus.SKIPPED -> AutoTubeColors.Teal
    StepStatus.WAITING -> AutoTubeColors.Amber
    StepStatus.FAILED -> AutoTubeColors.Error
}

private fun StepStatus.label(): String = when (this) {
    StepStatus.PENDING -> "Pending"
    StepStatus.RUNNING -> "Running"
    StepStatus.DONE -> "Done"
    StepStatus.SKIPPED -> "Skipped"
    StepStatus.WAITING -> "Waiting for you"
    StepStatus.FAILED -> "Failed"
}

@Composable
private fun Stepper(stages: List<StageUi>) {
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("Steps")
        Spacer(Modifier.height(12.dp))
        stages.forEachIndexed { index, stage ->
            StageRow(stage, isLast = index == stages.lastIndex)
        }
    }
}

@Composable
private fun StageRow(stage: StageUi, isLast: Boolean) {
    var expanded by rememberSaveable(stage.stage) { mutableStateOf(false) }
    val showSubs = expanded || stage.status == StepStatus.RUNNING || stage.status == StepStatus.FAILED
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(40.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusIcon(stage.status, stage.stage.icon())
            if (!isLast) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(
                            if (stage.status == StepStatus.DONE || stage.status == StepStatus.SKIPPED) AutoTubeColors.Green.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 18.dp)
                .animateContentSize(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stage.stage.title, style = MaterialTheme.typography.titleSmall)
                    Text(stage.status.label(), style = MaterialTheme.typography.labelSmall, color = stage.status.color())
                }
                if (stage.status == StepStatus.RUNNING) {
                    Text(Format.percent(stage.progress), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Icon(if (showSubs) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (stage.status == StepStatus.RUNNING || stage.status == StepStatus.WAITING) {
                ThinProgressBar(stage.progress, color = stage.status.color(), modifier = Modifier.padding(vertical = 4.dp))
            }
            if (stage.message.isNotBlank()) {
                Text(stage.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            AnimatedVisibility(visible = showSubs && stage.subSteps.size > 1) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stage.subSteps.forEach { sub ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(sub.state.status.color()))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(sub.step.title, style = MaterialTheme.typography.bodyMedium)
                                if (sub.state.message.isNotBlank()) {
                                    Text(sub.state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (sub.state.status == StepStatus.RUNNING) {
                                Text(Format.percent(sub.state.progress), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text(sub.state.status.label(), style = MaterialTheme.typography.labelSmall, color = sub.state.status.color())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: StepStatus, stageIcon: ImageVector) {
    val color = status.color()
    val transition = rememberInfiniteTransition(label = "running")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (status == StepStatus.PENDING) 0.12f else 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            StepStatus.DONE -> Icon(Icons.Rounded.Check, contentDescription = "Done", tint = color, modifier = Modifier.size(20.dp))
            StepStatus.SKIPPED -> Icon(Icons.Rounded.SkipNext, contentDescription = "Skipped", tint = color, modifier = Modifier.size(20.dp))
            StepStatus.FAILED -> Icon(Icons.Rounded.PriorityHigh, contentDescription = "Failed", tint = color, modifier = Modifier.size(20.dp))
            StepStatus.WAITING -> Icon(Icons.Rounded.HourglassTop, contentDescription = "Waiting", tint = color, modifier = Modifier.size(18.dp))
            StepStatus.RUNNING -> {
                CircularProgressIndicator(modifier = Modifier.size(36.dp).rotate(angle), color = color, strokeWidth = 2.dp)
                Icon(stageIcon, contentDescription = "Running", tint = color, modifier = Modifier.size(18.dp))
            }
            StepStatus.PENDING -> Icon(stageIcon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LogCard(visible: Boolean, tail: String, onToggle: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("Live log", icon = Icons.Rounded.Description, modifier = Modifier.weight(1f))
            Icon(if (visible) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(visible = visible) {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(10.dp),
            ) {
                val scroll = rememberScrollState()
                LaunchedEffect(tail) { scroll.scrollTo(scroll.maxValue) }
                SelectionContainer {
                    Text(
                        tail.ifBlank { "Loading…" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.verticalScroll(scroll),
                    )
                }
            }
        }
    }
}
