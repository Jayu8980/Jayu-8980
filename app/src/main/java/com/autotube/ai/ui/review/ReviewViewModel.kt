package com.autotube.ai.ui.review

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.QcReport
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.SeoPackage
import com.autotube.ai.core.model.UploadMode
import com.autotube.ai.core.model.UploadOptions
import com.autotube.ai.domain.AuthResult
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.WorkScheduler
import com.autotube.ai.domain.YouTubeAuth
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editable SEO fields as the user types them. */
data class SeoDraft(
    val title: String = "",
    val description: String = "",
    val tags: String = "",
    val hashtags: String = "",
) {
    val tagList: List<String>
        get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val hashtagList: List<String>
        get() = hashtags.split(Regex("[\\s,]+"))
            .map { it.trim().trimStart('#') }
            .filter { it.isNotEmpty() }
            .map { "#$it" }
            .distinct()

    val titleTooLong: Boolean get() = title.length > MAX_TITLE
    val tooManyTags: Boolean get() = tagList.size > MAX_TAGS

    companion object {
        const val MAX_TITLE = 100
        const val MAX_TAGS = 15

        fun from(seo: SeoPackage?, fallbackTitle: String) = SeoDraft(
            title = seo?.title ?: fallbackTitle.take(MAX_TITLE),
            description = seo?.description.orEmpty(),
            tags = seo?.tags.orEmpty().joinToString(", "),
            hashtags = seo?.hashtags.orEmpty().joinToString(" "),
        )
    }
}

data class SceneRow(val scene: Scene, val thumbPath: String?, val thumbIsVideo: Boolean)

data class ReviewDrafts(
    val seo: SeoDraft = SeoDraft(),
    val mode: UploadMode = UploadMode.PRIVATE_REVIEW,
    val scheduleAtMillis: Long? = null,
    val initializedFromSeo: Boolean = false,
    val initialized: Boolean = false,
    val edited: Boolean = false,
)

data class ReviewUiState(
    val loading: Boolean = true,
    val project: Project? = null,
    val seo: SeoDraft = SeoDraft(),
    val uploadMode: UploadMode = UploadMode.PRIVATE_REVIEW,
    val scheduleAtMillis: Long? = null,
    val scheduleError: String? = null,
    val scenes: List<SceneRow> = emptyList(),
    val latestQc: QcReport? = null,
    val busy: Boolean = false,
    val uploadRequested: Boolean = false,
) {
    val isUploading: Boolean get() = project?.status == ProjectStatus.UPLOADING
    val isUploaded: Boolean get() = project?.status == ProjectStatus.UPLOADED
    val waitingForUpload: Boolean
        get() = uploadRequested && project?.status != ProjectStatus.UPLOADING &&
            project?.status != ProjectStatus.UPLOADED && project?.status != ProjectStatus.FAILED
    val canUpload: Boolean
        get() = project?.outputVideoPath != null && !busy && !isUploading && !isUploaded && !waitingForUpload &&
            seo.title.isNotBlank() && !seo.titleTooLong && scheduleError == null
}

sealed interface ReviewEvent {
    data class LaunchConsent(val pendingIntent: PendingIntent) : ReviewEvent
    data class OpenProgress(val projectId: Long) : ReviewEvent
    data class Message(val text: String) : ReviewEvent
}

/** Review & upload: SEO edits, upload options, scene regeneration, Google authorization and upload. */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val workScheduler: WorkScheduler,
    private val youTubeAuth: YouTubeAuth,
) : ViewModel() {

    val projectId: Long = savedStateHandle.get<Long>(Routes.ARG_PROJECT_ID) ?: 0L

    private val drafts = MutableStateFlow(ReviewDrafts())
    private val busy = MutableStateFlow(false)
    private val uploadRequested = MutableStateFlow(false)
    private val _events = Channel<ReviewEvent>(Channel.BUFFERED)
    val events: Flow<ReviewEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ReviewUiState> = combine(
        projectRepository.observeProject(projectId),
        drafts,
        busy,
        uploadRequested,
    ) { project, d, isBusy, requested ->
        if (project == null) {
            ReviewUiState(loading = false)
        } else {
            ReviewUiState(
                loading = false,
                project = project,
                seo = d.seo,
                uploadMode = d.mode,
                scheduleAtMillis = d.scheduleAtMillis,
                scheduleError = scheduleError(d.mode, d.scheduleAtMillis),
                scenes = project.script?.scenes.orEmpty().map { scene ->
                    val asset = project.artifacts.visuals.firstOrNull { it.sceneNumber == scene.sceneNumber }
                    val path = asset?.previewPath ?: asset?.localPath
                    SceneRow(scene, path, asset != null && asset.previewPath == null && asset.isVideo)
                },
                latestQc = project.qcReports.maxByOrNull { it.pass },
                busy = isBusy,
                uploadRequested = requested,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    init {
        viewModelScope.launch {
            projectRepository.observeProject(projectId).collect { project ->
                if (project != null) initDrafts(project)
            }
        }
    }

    private fun initDrafts(project: Project) {
        drafts.update { d ->
            when {
                !d.initialized -> {
                    val options = project.uploadOptions
                    ReviewDrafts(
                        seo = SeoDraft.from(project.seo, project.displayTitle),
                        mode = options?.mode ?: settingsRepository.current().defaultUploadMode,
                        scheduleAtMillis = options?.publishAtMillis,
                        initializedFromSeo = project.seo != null,
                        initialized = true,
                    )
                }
                // SEO arrived later and the user has not typed anything yet.
                !d.initializedFromSeo && project.seo != null && !d.edited ->
                    d.copy(seo = SeoDraft.from(project.seo, project.displayTitle), initializedFromSeo = true)
                else -> d
            }
        }
    }

    fun onTitleChange(value: String) = editSeo { it.copy(title = value.replace('\n', ' ')) }
    fun onDescriptionChange(value: String) = editSeo { it.copy(description = value.take(5_000)) }
    fun onTagsChange(value: String) = editSeo { it.copy(tags = value) }
    fun onHashtagsChange(value: String) = editSeo { it.copy(hashtags = value) }

    private fun editSeo(transform: (SeoDraft) -> SeoDraft) {
        drafts.update { it.copy(seo = transform(it.seo), edited = true) }
    }

    fun onModeChange(mode: UploadMode) {
        drafts.update { d ->
            val at = if (mode == UploadMode.SCHEDULE && (d.scheduleAtMillis == null || d.scheduleAtMillis < minScheduleMillis())) {
                defaultScheduleMillis()
            } else {
                d.scheduleAtMillis
            }
            d.copy(mode = mode, scheduleAtMillis = at)
        }
    }

    fun onScheduleChange(millis: Long) {
        drafts.update { it.copy(scheduleAtMillis = millis) }
    }

    fun saveEdits() {
        viewModelScope.launch {
            busy.value = true
            try {
                persistEdits()
                _events.send(ReviewEvent.Message("Saved."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ReviewEvent.Message("Could not save: ${e.message ?: "unknown error"}"))
            } finally {
                busy.value = false
            }
        }
    }

    /** Saves SEO + upload options, then authorizes with Google and queues the upload. */
    fun approveAndUpload() {
        val d = drafts.value
        val validation = when {
            d.seo.title.isBlank() -> "Add a title first."
            d.seo.titleTooLong -> "The title must be ${SeoDraft.MAX_TITLE} characters or less."
            else -> scheduleError(d.mode, d.scheduleAtMillis)
        }
        if (validation != null) {
            _events.trySend(ReviewEvent.Message(validation))
            return
        }
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                persistEdits(prepareForUpload = true)
                when (val result = youTubeAuth.authorize()) {
                    is AuthResult.Authorized -> startUpload()
                    is AuthResult.NeedsConsent -> _events.send(ReviewEvent.LaunchConsent(result.pendingIntent))
                    is AuthResult.Failed -> _events.send(ReviewEvent.Message(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ReviewEvent.Message("Could not start the upload: ${e.message ?: "unknown error"}"))
            } finally {
                busy.value = false
            }
        }
    }

    /** Result of the Google consent screen launched for [ReviewEvent.LaunchConsent]. */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            _events.trySend(ReviewEvent.Message("Google sign-in was cancelled. The video was not uploaded."))
            return
        }
        viewModelScope.launch {
            busy.value = true
            try {
                when (val result = youTubeAuth.handleConsentResult(data)) {
                    is AuthResult.Authorized -> startUpload()
                    is AuthResult.NeedsConsent -> _events.send(ReviewEvent.Message("Google still needs your permission. Please try again."))
                    is AuthResult.Failed -> _events.send(ReviewEvent.Message(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ReviewEvent.Message("Google sign-in failed: ${e.message ?: "unknown error"}"))
            } finally {
                busy.value = false
            }
        }
    }

    fun regenerateScene(sceneNumber: Int) {
        restartWork("Regenerating scene $sceneNumber…") { workScheduler.regenerateScene(projectId, sceneNumber) }
    }

    fun rerender() {
        restartWork("Re-rendering the video…") { workScheduler.rerender(projectId) }
    }

    private fun restartWork(message: String, schedule: () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                persistEdits()
                projectRepository.updateProject(projectId) {
                    it.copy(status = ProjectStatus.QUEUED, error = null, statusMessage = message)
                }
                schedule()
                _events.send(ReviewEvent.OpenProgress(projectId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ReviewEvent.Message("Could not start: ${e.message ?: "unknown error"}"))
            } finally {
                busy.value = false
            }
        }
    }

    private fun startUpload() {
        workScheduler.upload(projectId)
        uploadRequested.value = true
        _events.trySend(ReviewEvent.Message("Upload queued. It continues in the background."))
    }

    private suspend fun persistEdits(prepareForUpload: Boolean = false) {
        val d = drafts.value
        val settings = settingsRepository.current()
        projectRepository.updateProject(projectId) { project ->
            val base = project.seo
            val seo = SeoPackage(
                title = d.seo.title.trim().take(SeoDraft.MAX_TITLE),
                description = d.seo.description.trim(),
                tags = d.seo.tagList.take(SeoDraft.MAX_TAGS),
                hashtags = d.seo.hashtagList,
                chapters = base?.chapters.orEmpty(),
                thumbnailText = base?.thumbnailText.orEmpty(),
            )
            val options = UploadOptions(
                mode = d.mode,
                publishAtMillis = if (d.mode == UploadMode.SCHEDULE) d.scheduleAtMillis else null,
                madeForKids = settings.madeForKids,
                categoryId = settings.youtubeCategoryId,
            )
            val updated = project.copy(seo = seo, uploadOptions = options)
            if (prepareForUpload && updated.status == ProjectStatus.FAILED) {
                updated.copy(status = ProjectStatus.AWAITING_REVIEW, error = null)
            } else {
                updated
            }
        }
        drafts.update { it.copy(edited = false, initializedFromSeo = true) }
    }

    private fun scheduleError(mode: UploadMode, at: Long?): String? = when {
        mode != UploadMode.SCHEDULE -> null
        at == null -> "Pick a date and time."
        at < minScheduleMillis() -> "Pick a time at least 15 minutes from now."
        else -> null
    }

    private fun minScheduleMillis() = System.currentTimeMillis() + MIN_SCHEDULE_LEAD_MS

    private fun defaultScheduleMillis(): Long {
        val hour = 60 * 60 * 1000L
        val inOneHour = System.currentTimeMillis() + hour
        return inOneHour - inOneHour % (15 * 60 * 1000L) + 15 * 60 * 1000L
    }

    companion object {
        const val MIN_SCHEDULE_LEAD_MS = 15 * 60 * 1000L
    }
}
