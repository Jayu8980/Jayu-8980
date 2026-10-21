package com.autotube.ai.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.VideoFormat
import com.autotube.ai.ui.AppPermissions
import com.autotube.ai.ui.components.AppCard
import com.autotube.ai.ui.components.Banner
import com.autotube.ai.ui.components.ChoiceChips
import com.autotube.ai.ui.components.FieldLabel
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.components.GradientButton
import com.autotube.ai.ui.components.LocalImage
import com.autotube.ai.ui.components.OnLifecycleEvent
import com.autotube.ai.ui.components.Pill
import com.autotube.ai.ui.components.SectionTitle
import com.autotube.ai.ui.components.SegmentedChoice
import com.autotube.ai.ui.components.SliderRow
import com.autotube.ai.ui.components.StatusPill
import com.autotube.ai.ui.components.ThinProgressBar
import com.autotube.ai.ui.components.UiIntents
import com.autotube.ai.ui.components.nativeLabel
import com.autotube.ai.ui.components.summaryLine
import com.autotube.ai.ui.theme.AutoTubeColors
import com.autotube.ai.ui.theme.Dimens
import kotlinx.coroutines.launch

private val ShortsOptions = listOf(15, 30, 45, 60)
private val LongFormChips = listOf(3, 5, 8, 10, 15)

/** Create screen: topic, format, length, language and the big "Create Video" button. */
@Composable
fun HomeScreen(
    onOpenProgress: (Long) -> Unit,
    onOpenProject: (Project) -> Unit,
    onSeeAllProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    OnLifecycleEvent(Lifecycle.Event.ON_RESUME) { viewModel.refreshDevice() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.OpenProgress -> onOpenProgress(event.projectId)
                is HomeEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    // Android 8/9: saving the finished video to the gallery needs WRITE_EXTERNAL_STORAGE.
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            scope.launch { snackbar.showSnackbar("Without storage access the video stays inside the app (you can still share it).") }
        }
        viewModel.createVideo()
    }
    val onCreate: () -> Unit = {
        if (AppPermissions.needsLegacyStorage && !AppPermissions.hasLegacyStorage(context)) {
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.createVideo()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
        ) {
            Header()
            ProviderChips(state.plan)
            DeviceBanners(
                device = state.device,
                onOpenSettings = onOpenSettings,
                onFixNotifications = { UiIntents.openNotificationSettings(context) },
                onFixBattery = { UiIntents.openBatterySaverSettings(context) },
                onFixStorage = { UiIntents.openStorageSettings(context) },
            )
            TopicCard(
                form = state.form,
                onNicheChange = viewModel::onNicheChange,
                onNotesChange = viewModel::onNotesChange,
                onToggleAdvanced = viewModel::toggleAdvanced,
            )
            FormatCard(
                form = state.form,
                onFormatChange = viewModel::onFormatChange,
                onShortsSec = viewModel::onShortsSecChange,
                onLongFormSec = viewModel::onLongFormSecChange,
                onLanguage = viewModel::onLanguageChange,
            )
            GradientButton(
                text = if (state.creating) "Starting…" else "Create Video",
                onClick = onCreate,
                enabled = state.canCreate,
                loading = state.creating,
                icon = Icons.Rounded.AutoAwesome,
            )
            if (state.recent.isNotEmpty()) {
                RecentProjects(state.recent, onOpenProject, onSeeAllProjects)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text("AutoTube AI", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Turn a topic into a finished, edited video — right on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderChips(plan: ProviderPlan) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (plan.offline) Pill("Offline mode", AutoTubeColors.Teal, icon = Icons.Rounded.CloudOff)
        Pill("Script: ${plan.script}", AutoTubeColors.Blue, icon = Icons.Rounded.Psychology)
        Pill("Voice: ${plan.voice}", AutoTubeColors.Purple, icon = Icons.Rounded.RecordVoiceOver)
        Pill("Captions: ${plan.captions}", AutoTubeColors.Green, icon = Icons.Rounded.Subtitles)
        Pill("Visuals: ${plan.visuals}", AutoTubeColors.Orange, icon = Icons.Rounded.Image)
        Pill(plan.music, AutoTubeColors.Amber, icon = Icons.Rounded.MusicNote)
    }
}

@Composable
private fun DeviceBanners(
    device: DeviceWarnings,
    onOpenSettings: () -> Unit,
    onFixNotifications: () -> Unit,
    onFixBattery: () -> Unit,
    onFixStorage: () -> Unit,
) {
    val any = device.lowStorage || device.batterySaver || device.notificationsDisabled || device.offline
    if (!any) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (device.lowStorage) {
            Banner(
                "Low storage: ${device.freeMb} MB free, ${device.requiredMb} MB recommended for rendering.",
                icon = Icons.Rounded.SdStorage,
                actionLabel = "Free up",
                onAction = onFixStorage,
            )
        }
        if (device.batterySaver) {
            Banner(
                "Battery saver is on: rendering may be slower or paused.",
                icon = Icons.Rounded.BatterySaver,
                actionLabel = "Settings",
                onAction = onFixBattery,
            )
        }
        if (device.notificationsDisabled) {
            Banner(
                "Notifications are off, so you won't hear when your video is ready.",
                icon = Icons.Rounded.NotificationsOff,
                actionLabel = "Turn on",
                onAction = onFixNotifications,
            )
        }
        if (device.offline) {
            Banner(
                "No internet: online providers are skipped and on-device fallbacks are used.",
                icon = Icons.Rounded.WifiOff,
                actionLabel = "Options",
                onAction = onOpenSettings,
            )
        }
    }
}

@Composable
private fun TopicCard(
    form: HomeForm,
    onNicheChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
) {
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("What's your video about?", icon = Icons.Rounded.AutoAwesome)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = form.niche,
            onValueChange = onNicheChange,
            label = { Text("Topic or niche") },
            placeholder = { Text("e.g. Space mysteries, money tips for students") },
            singleLine = false,
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggleAdvanced)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(if (form.showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = form.showAdvanced) {
            OutlinedTextField(
                value = form.notes,
                onValueChange = onNotesChange,
                label = { Text("Notes for the writer (optional)") },
                placeholder = { Text("Tone, audience, facts to include…") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FormatCard(
    form: HomeForm,
    onFormatChange: (VideoFormat) -> Unit,
    onShortsSec: (Int) -> Unit,
    onLongFormSec: (Int) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
) {
    AppCard(Modifier.fillMaxWidth()) {
        SectionTitle("Format", icon = Icons.Rounded.GraphicEq)
        Spacer(Modifier.height(16.dp))
        SegmentedChoice(
            options = listOf(VideoFormat.LONG_FORM, VideoFormat.SHORTS),
            selected = form.format,
            optionLabel = { it.label },
            onSelect = onFormatChange,
            optionIcon = { if (it == VideoFormat.SHORTS) Icons.Rounded.Smartphone else Icons.Rounded.Tv },
        )
        FieldLabel("Target length")
        if (form.format == VideoFormat.SHORTS) {
            ChoiceChips(
                options = ShortsOptions,
                selected = form.shortsSec,
                optionLabel = { "$it s" },
                onSelect = onShortsSec,
            )
        } else {
            val minutes = (form.longFormSec / 60).coerceIn(1, 20)
            SliderRow(
                title = "Length",
                value = minutes.toFloat(),
                valueRange = 1f..20f,
                steps = 18,
                valueLabel = { "${it.toInt()} min" },
                onCommit = { onLongFormSec(it.toInt() * 60) },
            )
            ChoiceChips(
                options = LongFormChips,
                selected = minutes.takeIf { it in LongFormChips },
                optionLabel = { "$it min" },
                onSelect = { onLongFormSec(it * 60) },
            )
        }
        FieldLabel("Language")
        ChoiceChips(
            options = AppLanguage.entries.toList(),
            selected = form.language,
            optionLabel = { it.nativeLabel },
            onSelect = onLanguage,
        )
    }
}

@Composable
private fun RecentProjects(projects: List<Project>, onOpen: (Project) -> Unit, onSeeAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap)) {
        SectionTitle(
            "Recent videos",
            icon = Icons.Rounded.VideoLibrary,
            action = { TextButton(onClick = onSeeAll) { Text("See all") } },
        )
        projects.forEach { project ->
            AppCard(Modifier.fillMaxWidth(), onClick = { onOpen(project) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalImage(
                        path = project.thumbnailPath ?: project.previewImagePath,
                        contentDescription = null,
                        modifier = Modifier
                            .width(96.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(project.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(project.summaryLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(project.status)
                            Spacer(Modifier.width(8.dp))
                            Text(Format.date(project.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (project.status.isActive) {
                            ThinProgressBar(project.overallProgress, modifier = Modifier.padding(top = 4.dp).size(width = 160.dp, height = 4.dp), height = 4.dp)
                        }
                    }
                }
            }
        }
    }
}
