package com.autotube.ai.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotube.ai.core.model.Project
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<Project> = emptyList(),
)

/** All projects, newest first, with delete. */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val uiState: StateFlow<ProjectsUiState> = projectRepository.observeProjects()
        .map { list -> ProjectsUiState(loading = false, projects = list.sortedByDescending { it.createdAt }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

    fun delete(project: Project) {
        viewModelScope.launch {
            try {
                if (project.status.isActive) workScheduler.cancel(project.id)
                projectRepository.deleteProject(project.id)
                _messages.send("Deleted \"${project.displayTitle}\".")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("Could not delete: ${e.message ?: "unknown error"}")
            }
        }
    }
}
