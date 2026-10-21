package com.autotube.ai.ui.error

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotube.ai.core.model.PipelineError
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.domain.ProjectLogger
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.WorkScheduler
import com.autotube.ai.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Log dialog state: null = closed, text null = loading. */
data class LogState(val text: String?)

data class ErrorUiState(
    val loading: Boolean = true,
    val project: Project? = null,
    val error: PipelineError? = null,
    val suggestSettings: Boolean = false,
    val retrying: Boolean = false,
    val log: LogState? = null,
)

sealed interface ErrorEvent {
    data class OpenProgress(val projectId: Long) : ErrorEvent
    data class OpenReview(val projectId: Long) : ErrorEvent
    data class ShareLog(val subject: String, val text: String) : ErrorEvent
    data class Message(val text: String) : ErrorEvent
}

/** Friendly error screen: retry, settings shortcut, technical details and the project log. */
@HiltViewModel
class ErrorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val workScheduler: WorkScheduler,
    private val projectLogger: ProjectLogger,
) : ViewModel() {

    val projectId: Long = savedStateHandle.get<Long>(Routes.ARG_PROJECT_ID) ?: 0L

    private val retrying = MutableStateFlow(false)
    private val log = MutableStateFlow<LogState?>(null)
    private val _events = Channel<ErrorEvent>(Channel.BUFFERED)
    val events: Flow<ErrorEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ErrorUiState> = combine(
        projectRepository.observeProject(projectId),
        retrying,
        log,
    ) { project, isRetrying, logState ->
        val error = project?.error
        ErrorUiState(
            loading = false,
            project = project,
            error = error,
            suggestSettings = error != null && isAboutSettings(error),
            retrying = isRetrying,
            log = logState,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ErrorUiState())

    /** Upload failures go back to Review (re-authorize); everything else resumes the pipeline. */
    fun retry() {
        if (retrying.value) return
        retrying.value = true
        viewModelScope.launch {
            try {
                val project = projectRepository.getProject(projectId)
                if (project == null) {
                    _events.send(ErrorEvent.Message("This video no longer exists."))
                    return@launch
                }
                when {
                    project.status.isActive -> _events.send(ErrorEvent.OpenProgress(projectId))
                    project.error?.step == PipelineStep.UPLOAD && project.outputVideoPath != null -> {
                        projectRepository.updateProject(projectId) {
                            it.copy(status = ProjectStatus.AWAITING_REVIEW, error = null, statusMessage = "Ready to upload again")
                        }
                        _events.send(ErrorEvent.OpenReview(projectId))
                    }
                    else -> {
                        projectRepository.updateProject(projectId) {
                            it.copy(status = ProjectStatus.QUEUED, error = null, statusMessage = "Retrying…")
                        }
                        workScheduler.startPipeline(projectId)
                        _events.send(ErrorEvent.OpenProgress(projectId))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ErrorEvent.Message("Could not retry: ${e.message ?: "unknown error"}"))
            } finally {
                retrying.value = false
            }
        }
    }

    fun showLog() {
        log.value = LogState(null)
        viewModelScope.launch { log.value = LogState(readLog(FULL_LOG_CHARS)) }
    }

    fun hideLog() {
        log.value = null
    }

    fun shareLog() {
        viewModelScope.launch {
            val text = log.value?.text ?: readLog(FULL_LOG_CHARS)
            val title = uiState.value.project?.displayTitle ?: "AutoTube project $projectId"
            // Keep the share intent well below the binder transaction limit.
            _events.send(ErrorEvent.ShareLog("AutoTube AI log: $title", text.takeLast(SHARE_LOG_CHARS)))
        }
    }

    private suspend fun readLog(maxChars: Int): String = try {
        projectLogger.read(projectId, maxChars)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Could not read the log: ${e.message}"
    }

    private companion object {
        const val FULL_LOG_CHARS = 200_000
        const val SHARE_LOG_CHARS = 90_000
    }
}

/** True when the fix is in Settings (API keys, Google sign-in, provider configuration). */
internal fun isAboutSettings(error: PipelineError): Boolean {
    val text = "${error.title} ${error.message}".lowercase()
    val hints = listOf("api key", "key needed", "settings", "sign in", "sign-in", "signed in", "google account", "rejected the api", "no credit", "not configured", "wi-fi only")
    return hints.any { it in text }
}
