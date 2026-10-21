package com.autotube.ai.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.PipelineError
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.StepState
import com.autotube.ai.core.model.StepStatus
import com.autotube.ai.core.model.UploadOptions
import com.autotube.ai.domain.AuthResult
import com.autotube.ai.domain.DeviceStatus
import com.autotube.ai.domain.ProjectLogger
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.YouTubeAuth
import com.autotube.ai.domain.YouTubeUploader
import com.autotube.ai.pipeline.orchestrator.PipelineErrors
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uploads a finished project to YouTube (resumable upload) as a dataSync foreground job.
 * Transient network problems are retried by WorkManager (exponential backoff set by the scheduler);
 * everything else marks the project FAILED with a readable error.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceStatus: DeviceStatus,
    private val youTubeAuth: YouTubeAuth,
    private val uploader: YouTubeUploader,
    private val projectLogger: ProjectLogger,
    private val notifications: NotificationHelper,
    private val userCancellations: UserCancellations,
) : CoroutineWorker(appContext, params) {

    private val projectId: Long = inputData.getLong(KEY_PROJECT_ID, -1L)

    @Volatile private var title: String = "AutoTube AI"
    @Volatile private var progress: Float? = null
    @Volatile private var lastNotifyAt: Long = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (projectId >= 0) projectRepository.getProject(projectId)?.let { title = it.displayTitle }
        return buildForegroundInfo()
    }

    override suspend fun doWork(): Result {
        if (projectId < 0) return Result.failure()
        val log = projectLogger.forStep(projectId, PipelineStep.UPLOAD)
        val project = projectRepository.getProject(projectId) ?: return Result.failure()
        title = project.displayTitle

        if (project.youtubeVideoId != null && project.status == ProjectStatus.UPLOADED) {
            log.i("Already uploaded as ${project.youtubeVideoId}; nothing to do")
            return Result.success()
        }

        val video = project.outputVideoPath?.let { File(it) }?.takeIf { it.exists() }
        val seo = project.seo
        if (video == null || seo == null) {
            fail(
                PipelineError(
                    step = PipelineStep.UPLOAD,
                    title = "Nothing to upload",
                    message = "The exported video or its title and description are missing. Re-render the video first.",
                    retryable = false,
                ),
                null,
            )
            return Result.success()
        }
        val settings = settingsRepository.current()
        val options = project.uploadOptions ?: UploadOptions(
            mode = settings.defaultUploadMode,
            madeForKids = settings.madeForKids,
            categoryId = settings.youtubeCategoryId,
        )
        val thumbnail = project.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }

        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            log.w("Could not start the upload as a foreground service", e)
        }

        val now = System.currentTimeMillis()
        projectRepository.updateProject(projectId) { p ->
            p.copy(
                status = ProjectStatus.UPLOADING,
                currentStep = PipelineStep.UPLOAD,
                uploadOptions = options,
                uploadProgress = 0f,
                error = null,
                statusMessage = "Uploading to YouTube",
                steps = p.steps + (PipelineStep.UPLOAD to StepState(
                    status = StepStatus.RUNNING,
                    progress = 0f,
                    message = "Uploading to YouTube",
                    attempts = runAttemptCount + 1,
                    startedAt = now,
                )),
            )
        }

        return try {
            deviceStatus.requireNetworkForTransfer(settings.wifiOnly)
            log.i("Upload started (${options.mode}, attempt ${runAttemptCount + 1})")
            val videoId = coroutineScope {
                val pending = MutableStateFlow<Float?>(null)
                val persister = launch {
                    pending.filterNotNull().collect { value ->
                        persistProgress(value)
                        delay(PERSIST_INTERVAL_MS)
                    }
                }
                try {
                    uploader.upload(
                        videoFile = video,
                        thumbnail = thumbnail,
                        seo = seo,
                        options = options,
                        languageCode = project.request.language.code,
                        accessToken = { accessToken() },
                        onProgress = { value ->
                            val clamped = value.coerceIn(0f, 1f)
                            progress = clamped
                            pending.value = clamped
                            maybeUpdateNotification()
                        },
                    )
                } finally {
                    persister.cancel()
                }
            }
            succeed(videoId)
            log.i("Upload finished: https://youtu.be/$videoId")
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { handleStop() }
            throw e
        } catch (e: Exception) {
            val retryable = e !is PipelineException || e.retryable
            if (retryable && isTransient(e) && runAttemptCount < MAX_RETRIES) {
                log.w("Upload interrupted (${e.message?.take(200)}); WorkManager will retry", e)
                projectRepository.updateProject(projectId) { p ->
                    p.copy(
                        status = ProjectStatus.UPLOADING,
                        statusMessage = "Upload interrupted. Retrying soon…",
                        steps = p.steps + (PipelineStep.UPLOAD to p.step(PipelineStep.UPLOAD).copy(
                            status = StepStatus.WAITING,
                            message = "Upload interrupted. Retrying soon…",
                        )),
                    )
                }
                Result.retry()
            } else {
                val error = PipelineErrors.from(e, PipelineStep.UPLOAD)
                fail(error, e)
                Result.success()
            }
        }
    }

    private suspend fun accessToken(): String = when (val result = youTubeAuth.authorize()) {
        is AuthResult.Authorized -> result.accessToken
        is AuthResult.NeedsConsent -> throw signInExpired(null)
        is AuthResult.Failed -> throw signInExpired(result.message)
    }

    private fun signInExpired(detail: String?): PipelineException = PipelineException(
        userTitle = "Google sign-in expired",
        userMessage = "Google sign-in expired. Open the app and sign in again in Settings.",
        retryable = false,
        step = PipelineStep.UPLOAD,
        cause = detail?.let { IllegalStateException(it) },
    )

    private fun isTransient(e: Throwable): Boolean =
        e is java.io.IOException || (e is PipelineException && e.retryable)

    private suspend fun succeed(videoId: String) {
        val now = System.currentTimeMillis()
        val saved: Project? = projectRepository.updateProject(projectId) { p ->
            p.copy(
                status = ProjectStatus.UPLOADED,
                youtubeVideoId = videoId,
                uploadProgress = 1f,
                currentStep = null,
                error = null,
                statusMessage = "Uploaded to YouTube",
                steps = p.steps + (PipelineStep.UPLOAD to p.step(PipelineStep.UPLOAD).copy(
                    status = StepStatus.DONE,
                    progress = 1f,
                    message = "Uploaded: youtu.be/$videoId",
                    finishedAt = now,
                )),
            )
        }
        notifications.notifyUploaded(projectId, saved?.displayTitle ?: title, videoId)
    }

    private suspend fun fail(error: PipelineError, cause: Throwable?) {
        projectLogger.forStep(projectId, PipelineStep.UPLOAD).e("Upload failed: ${error.title} - ${error.message}", cause)
        val now = System.currentTimeMillis()
        projectRepository.updateProject(projectId) { p ->
            p.copy(
                status = ProjectStatus.FAILED,
                error = error,
                currentStep = PipelineStep.UPLOAD,
                statusMessage = error.title,
                steps = p.steps + (PipelineStep.UPLOAD to p.step(PipelineStep.UPLOAD).copy(
                    status = StepStatus.FAILED,
                    message = error.message,
                    finishedAt = now,
                )),
            )
        }
        notifications.notifyFailed(projectId, title, error)
    }

    private suspend fun persistProgress(value: Float) {
        try {
            projectRepository.updateProject(projectId) { p ->
                if (p.status != ProjectStatus.UPLOADING) {
                    p
                } else {
                    val message = "Uploading to YouTube… ${(value * 100).toInt()}%"
                    p.copy(
                        uploadProgress = value,
                        statusMessage = message,
                        steps = p.steps + (PipelineStep.UPLOAD to p.step(PipelineStep.UPLOAD).copy(
                            progress = value,
                            message = message,
                        )),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Upload progress write failed", e)
        }
    }

    /** User cancel -> CANCELLED (done by the scheduler); system stop -> wait for the automatic rerun. */
    private suspend fun handleStop() {
        if (userCancellations.wasCancelled(projectId)) return
        try {
            projectRepository.updateProject(projectId) { p ->
                if (p.status == ProjectStatus.UPLOADING) {
                    p.copy(
                        statusMessage = "Upload paused. It will continue automatically.",
                        steps = p.steps + (PipelineStep.UPLOAD to p.step(PipelineStep.UPLOAD).copy(
                            status = StepStatus.WAITING,
                            message = "Upload paused. It will continue automatically.",
                        )),
                    )
                } else {
                    p
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not mark the upload as paused", e)
        }
    }

    private fun maybeUpdateNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        setForegroundAsync(buildForegroundInfo())
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        val notification = notifications.uploadProgress(
            projectId = projectId,
            title = title,
            progress = progress,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return notifications.uploadForegroundInfo(projectId, notification)
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private const val PERSIST_INTERVAL_MS = 700L
        private const val MAX_RETRIES = 5

        const val KEY_PROJECT_ID = "project_id"

        fun inputData(projectId: Long): Data = workDataOf(KEY_PROJECT_ID to projectId)
    }
}
