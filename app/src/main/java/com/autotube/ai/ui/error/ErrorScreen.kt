package com.autotube.ai.ui.error

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.ui.components.AppCard
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.components.GradientButton
import com.autotube.ai.ui.components.LogDialog
import com.autotube.ai.ui.components.Pill
import com.autotube.ai.ui.components.UiIntents
import com.autotube.ai.ui.theme.AutoTubeColors
import com.autotube.ai.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Friendly explanation of a failed project with retry, settings shortcut and log. */
@Composable
fun ErrorScreen(
    onBack: () -> Unit,
    onOpenProgress: (Long) -> Unit,
    onOpenReview: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ErrorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var detailsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ErrorEvent.OpenProgress -> onOpenProgress(event.projectId)
                is ErrorEvent.OpenReview -> onOpenReview(event.projectId)
                is ErrorEvent.ShareLog -> UiIntents.shareText(context, event.subject, event.text)
                is ErrorEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Something went wrong") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val project = state.project
        val error = state.error
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(120.dp).clip(CircleShape).background(AutoTubeColors.Red.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SentimentDissatisfied, contentDescription = null, tint = AutoTubeColors.Red, modifier = Modifier.size(64.dp))
            }
            Text(
                error?.title ?: if (project?.status == ProjectStatus.CANCELLED) "This video was cancelled" else "This video stopped",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                error?.message ?: if (project == null) "This video no longer exists." else "It stopped before it finished. Retry to continue from where it left off.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            error?.step?.let { Pill("Failed at: ${it.title}", AutoTubeColors.Amber) }
            if (project != null) {
                Text(
                    project.displayTitle + (error?.let { " · " + Format.date(it.at) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (error != null && !error.retryable) {
                    Text(
                        "Retrying will probably fail again until the cause above is fixed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AutoTubeColors.Amber,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(4.dp))
                GradientButton(text = "Retry", onClick = viewModel::retry, loading = state.retrying, icon = Icons.Rounded.Replay)
                if (state.suggestSettings) {
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Settings")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = viewModel::showLog, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("View log")
                    }
                    FilledTonalButton(onClick = viewModel::shareLog, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share log")
                    }
                }
            }
            if (error != null && error.technical.isNotBlank()) {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { detailsOpen = !detailsOpen }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Technical details", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (detailsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                    }
                    AnimatedVisibility(visible = detailsOpen) {
                        SelectionContainer(Modifier.padding(top = 10.dp)) {
                            Text(
                                error.technical,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }

    state.log?.let { log ->
        LogDialog(text = log.text, onDismiss = viewModel::hideLog, onShare = viewModel::shareLog)
    }
}
