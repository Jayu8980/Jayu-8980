package com.autotube.ai.ui.projects

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.core.model.Project
import com.autotube.ai.ui.components.AppCard
import com.autotube.ai.ui.components.ConfirmDialog
import com.autotube.ai.ui.components.EmptyState
import com.autotube.ai.ui.components.Format
import com.autotube.ai.ui.components.LocalImage
import com.autotube.ai.ui.components.StatusPill
import com.autotube.ai.ui.components.ThinProgressBar
import com.autotube.ai.ui.components.color
import com.autotube.ai.ui.components.summaryLine
import com.autotube.ai.ui.theme.Dimens
import kotlinx.coroutines.launch

/** List of every project; tap opens the screen that fits its status. */
@Composable
fun ProjectsScreen(
    onOpenProject: (Project) -> Unit,
    onCreate: () -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Project?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { scope.launch { snackbar.showSnackbar(it) } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.projects.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = "No videos yet",
                    message = "Type a topic on the Create tab and AutoTube writes, voices, edits and exports a video for you.",
                    actionLabel = "Create your first video",
                    onAction = onCreate,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
            ) {
                item {
                    Column(Modifier.padding(bottom = 4.dp)) {
                        Text("Projects", style = MaterialTheme.typography.headlineMedium)
                        Text("${state.projects.size} videos", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.projects, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        onOpen = { onOpenProject(project) },
                        onDelete = { pendingDelete = project },
                    )
                }
            }
        }
    }

    pendingDelete?.let { project ->
        ConfirmDialog(
            title = "Delete this video?",
            message = "\"${project.displayTitle}\" and all its files in the app are removed. The copy saved in your gallery is kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                viewModel.delete(project)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ProjectRow(project: Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocalImage(
                path = project.thumbnailPath ?: project.previewImagePath,
                contentDescription = null,
                placeholderIcon = Icons.Rounded.Movie,
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(project.summaryLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(project.status)
                    Spacer(Modifier.width(8.dp))
                    Text(Format.date(project.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (project.status.isActive) {
                    Spacer(Modifier.height(2.dp))
                    ThinProgressBar(
                        progress = if (project.status == com.autotube.ai.core.model.ProjectStatus.UPLOADING) project.uploadProgress else project.overallProgress,
                        color = project.status.color,
                        height = 4.dp,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
