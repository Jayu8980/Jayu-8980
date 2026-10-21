package com.autotube.ai.ui.progress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.StepState
import com.autotube.ai.core.model.StepStatus
import com.autotube.ai.core.model.TopicCandidate
import com.autotube.ai.core.model.UiStage
import com.autotube.ai.domain.ProjectLogger
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.WorkScheduler
import com.autotube.ai.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubStepUi(val step: PipelineStep, val state: StepState)

/** One of the 7 user-facing stages with its aggregated status. */
data class StageUi(
    val stage: UiStage,
    val status: StepStatus,
    val progress: Float,
    val message: String,
    val subSteps: List<SubStepUi>,
)

data class ScenePreview(
    val sceneNumber: Int,
    val totalScenes: Int,
    val narration: String,
    val onScreenText: String,
)

data class ProgressUiState(
    val loading: Boolean = true,
    val project: Project? = null,
    val stages: List<StageUi> = emptyList(),
    val overallProgress: Float = 0f,
    val previewImagePath: String? = null,
    val previewIsVideo: Boolean = false,
    val scenePreview: ScenePreview? = null,
    val topics: List<TopicCandidate> = emptyList(),
    val logVisible: Boolean = false,
    val logTail: String = "",
    val busy: Boolean = false,
)

sealed interface ProgressEvent {
    data class OpenError(val projectId: Long) : ProgressEvent
    data class Message(val text: String) : ProgressEvent
}

/** Live view of a running project, topic picker and cancel/resume actions. */
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val workScheduler: WorkScheduler,
    private val projectLogger: ProjectLogger,
) : ViewModel() {

    val projectId: Long = savedStateHandle.get<Long>(Routes.ARG_PROJECT_ID) ?: 0L

    private val logVisible = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val _events = Channel<ProgressEvent>(Channel.BUFFERED)
    val events: Flow<ProgressEvent> = _events.receiveAsFlow()

    private val logTail: Flow<String> = logVisible.flatMapLatest { visible ->
        if (!visible) {
            flowOf("")
        } else {
            flow {
                while (currentCoroutineContext().isActive) {
                    emit(readLogTail())
                    delay(2_000)
                }
            }
        }
    }

    val uiState: StateFlow<ProgressUiState> = combine(
        projectRepository.observeProject(projectId),
        logVisible,
        logTail,
        busy,
    ) { project, visible, tail, isBusy ->
        if (project == null) {
            ProgressUiState(loading = false, logVisible = visible, logTail = tail, busy = isBusy)
        } else {
            buildState(project, visible, tail, isBusy)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())

    init {
        // Auto-open the error screen once when the project fails.
        viewModelScope.launch {
            projectRepository.observeProject(projectId).first { it?.status == ProjectStatus.FAILED }
            if (savedStateHandle.get<Boolean>(KEY_ERROR_SHOWN) != true) {
                savedStateHandle[KEY_ERROR_SHOWN] = true
                _events.send(ProgressEvent.OpenError(projectId))
            }
        }
    }

    fun toggleLog() {
        logVisible.value = !logVisible.value
    }

    fun setLogVisible(visible: Boolean) {
        logVisible.value = visible
    }

    fun chooseTopic(topic: TopicCandidate) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                projectRepository.updateProject(projectId) {
                    it.copy(
                        chosenTopic = topic,
                        status = ProjectStatus.QUEUED,
                        statusMessage = "Writing the script for \"${topic.title}\"",
                    )
                }
                workScheduler.resumeAfterTopic(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ProgressEvent.Message("Could not continue: ${e.message ?: "unknown error"}"))
            } finally {
                busy.value = false
            }
        }
    }

    fun cancel() {
        viewModelScope.launch {
            try {
                workScheduler.cancel(projectId)
                _events.send(ProgressEvent.Message("Video creation cancelled."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ProgressEvent.Message("Could not cancel: ${e.message ?: "unknown error"}"))
            }
        }
    }

    /** Restarts a cancelled project; finished steps are skipped by the pipeline. */
    fun resume() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                projectRepository.updateProject(projectId) {
                    it.copy(status = ProjectStatus.QUEUED, error = null, statusMessage = "Resuming…")
                }
                workScheduler.startPipeline(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ProgressEvent.Message("Could not resume: ${e.message ?: "unknown error"}"))
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun readLogTail(): String = try {
        val text = projectLogger.read(projectId, 24_000)
        text.lines().takeLast(LOG_TAIL_LINES).joinToString("\n")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Could not read the log: ${e.message}"
    }

    private fun buildState(project: Project, logVisible: Boolean, tail: String, isBusy: Boolean): ProgressUiState {
        val stages = buildStages(project)
        val overall = when (project.status) {
            ProjectStatus.AWAITING_REVIEW, ProjectStatus.UPLOADED -> 1f
            else -> project.overallProgress.coerceIn(0f, 1f)
        }

        val scenes = project.script?.scenes.orEmpty()
        var sceneIndex = 0
        if (scenes.isNotEmpty()) {
            val step = project.currentStep
            val p = step?.let { project.step(it).progress } ?: 0f
            sceneIndex = when (step) {
                PipelineStep.VOICE, PipelineStep.CAPTIONS, PipelineStep.VISUALS,
                PipelineStep.RENDER, PipelineStep.QUALITY_CHECK -> (p * scenes.size).toInt().coerceIn(0, scenes.lastIndex)
                else -> 0
            }
        }
        val scene = scenes.getOrNull(sceneIndex)
        val sceneAsset = scene?.let { s -> project.artifacts.visuals.firstOrNull { it.sceneNumber == s.sceneNumber } }
        val (imagePath, isVideo) = when {
            sceneAsset?.previewPath != null -> sceneAsset.previewPath to false
            sceneAsset != null && !sceneAsset.isVideo -> sceneAsset.localPath to false
            project.previewImagePath != null -> project.previewImagePath to false
            project.thumbnailPath != null -> project.thumbnailPath to false
            sceneAsset != null -> sceneAsset.localPath to true
            else -> null to false
        }

        return ProgressUiState(
            loading = false,
            project = project,
            stages = stages,
            overallProgress = overall,
            previewImagePath = imagePath,
            previewIsVideo = isVideo,
            scenePreview = scene?.let {
                ScenePreview(sceneIndex + 1, scenes.size, it.narrationText, it.onScreenText)
            },
            topics = project.topicCandidates.sortedByDescending { it.score }.take(5),
            logVisible = logVisible,
            logTail = tail,
            busy = isBusy,
        )
    }

    private companion object {
        const val KEY_ERROR_SHOWN = "error_screen_shown"
        const val LOG_TAIL_LINES = 80
    }
}

/** Aggregates PipelineStep states into the 7 UiStage rows. */
internal fun buildStages(project: Project): List<StageUi> = UiStage.entries.map { stage ->
    val subs = PipelineStep.entries.filter { it.uiGroup == stage }.map { SubStepUi(it, project.step(it)) }
    val statuses = subs.map { it.state.status }
    val uploadingNow = stage == UiStage.UPLOAD && project.status == ProjectStatus.UPLOADING
    val finished = { s: StepStatus -> s == StepStatus.DONE || s == StepStatus.SKIPPED }
    val status = when {
        uploadingNow -> StepStatus.RUNNING
        statuses.any { it == StepStatus.FAILED } -> StepStatus.FAILED
        statuses.any { it == StepStatus.RUNNING } -> StepStatus.RUNNING
        statuses.any { it == StepStatus.WAITING } -> StepStatus.WAITING
        statuses.isNotEmpty() && statuses.all { it == StepStatus.SKIPPED } -> StepStatus.SKIPPED
        statuses.isNotEmpty() && statuses.all(finished) -> StepStatus.DONE
        statuses.any(finished) -> if (project.status.isActive) StepStatus.RUNNING else StepStatus.PENDING
        else -> StepStatus.PENDING
    }
    val progress = when {
        uploadingNow -> project.uploadProgress.coerceIn(0f, 1f)
        subs.isEmpty() -> 0f
        else -> subs.map { sub ->
            when (sub.state.status) {
                StepStatus.DONE, StepStatus.SKIPPED -> 1f
                StepStatus.RUNNING, StepStatus.WAITING, StepStatus.FAILED -> sub.state.progress.coerceIn(0f, 1f)
                StepStatus.PENDING -> 0f
            }
        }.average().toFloat()
    }
    val message = subs.firstOrNull { it.state.status == StepStatus.RUNNING || it.state.status == StepStatus.FAILED || it.state.status == StepStatus.WAITING }
        ?.state?.message?.takeIf { it.isNotBlank() }
        ?: subs.lastOrNull { finished(it.state.status) && it.state.message.isNotBlank() }?.state?.message
        ?: ""
    StageUi(stage, status, progress, message, subs)
}
