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
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.pipeline.orchestrator.PipelineOrchestrator
import com.autotube.ai.pipeline.orchestrator.RunMode
import com.autotube.ai.pipeline.orchestrator.RunOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Runs [PipelineOrchestrator] as long-running foreground work with an ongoing progress
 * notification. Every handled outcome returns [Result.success]: failures are shown in the project
 * and as a notification, never through WorkManager retries.
 */
@HiltWorker
class PipelineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: PipelineOrchestrator,
    private val projectRepository: ProjectRepository,
    private val notifications: NotificationHelper,
    private val userCancellations: UserCancellations,
) : CoroutineWorker(appContext, params) {

    private val projectId: Long = inputData.getLong(KEY_PROJECT_ID, -1L)

    @Volatile private var title: String = DEFAULT_TITLE
    @Volatile private var message: String = "Starting…"
    @Volatile private var overall: Float? = null
    @Volatile private var lastNotifyAt: Long = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (projectId >= 0 && title == DEFAULT_TITLE) {
            projectRepository.getProject(projectId)?.let { project ->
                title = project.displayTitle
                if (project.statusMessage.isNotBlank()) message = project.statusMessage
            }
        }
        return buildForegroundInfo()
    }

    override suspend fun doWork(): Result {
        if (projectId < 0) {
            Log.e(TAG, "PipelineWorker started without a project id")
            return Result.failure()
        }
        val mode = parseMode(inputData) ?: run {
            Log.e(TAG, "Unknown pipeline mode ${inputData.getString(KEY_MODE)}")
            return Result.failure()
        }

        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a foreground start from the background; keep working anyway.
            Log.w(TAG, "Could not start foreground service", e)
        }

        val outcome = try {
            coroutineScope {
                val titleWatcher = launch {
                    projectRepository.observeProject(projectId).collect { project ->
                        if (project != null) title = project.displayTitle
                    }
                }
                try {
                    orchestrator.run(projectId, mode) { progress, text ->
                        overall = progress
                        message = text
                        maybeUpdateNotification()
                    }
                } finally {
                    titleWatcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { handleStop() }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline crashed for project $projectId", e)
            return Result.failure()
        }

        val finalTitle = projectRepository.getProject(projectId)?.displayTitle ?: title
        when (outcome) {
            RunOutcome.AwaitingTopic -> notifications.notifyPickTopic(projectId, finalTitle)
            RunOutcome.AwaitingReview -> notifications.notifyReviewReady(projectId, finalTitle)
            is RunOutcome.Failed -> notifications.notifyFailed(projectId, finalTitle, outcome.error)
            RunOutcome.UploadQueued -> Unit // UploadWorker reports the result.
            RunOutcome.Completed -> Unit
            RunOutcome.Cancelled -> Unit
        }
        return Result.success()
    }

    /**
     * When the system (not the user) stopped this work, WorkManager runs it again later, so the
     * project must not stay CANCELLED: put it back to QUEUED. A RUNNING status belongs to a newer
     * run (REPLACE policy) and is left alone.
     */
    private suspend fun handleStop() {
        if (userCancellations.wasCancelled(projectId)) return
        try {
            projectRepository.updateProject(projectId) { p ->
                if (p.status == ProjectStatus.CANCELLED) {
                    p.copy(status = ProjectStatus.QUEUED, statusMessage = "Paused by the system. It will continue automatically.")
                } else {
                    p
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not re-queue project $projectId", e)
        }
    }

    private fun maybeUpdateNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        try {
            setForegroundAsync(buildForegroundInfo())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not update the progress notification", e)
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        val percent = overall?.let { (it * 100).roundToInt() }
        val text = if (percent != null) "$message · $percent%" else message
        val notification = notifications.pipelineProgress(
            projectId = projectId,
            title = title,
            text = text,
            progress = overall,
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        return notifications.pipelineForegroundInfo(projectId, notification)
    }

    companion object {
        private const val TAG = "PipelineWorker"
        private const val DEFAULT_TITLE = "AutoTube AI"
        private const val NOTIFY_INTERVAL_MS = 1_000L

        const val KEY_PROJECT_ID = "project_id"
        const val KEY_MODE = "mode"
        const val KEY_SCENE_NUMBER = "scene_number"

        const val MODE_FULL = "full"
        const val MODE_RESUME_AFTER_TOPIC = "resume_after_topic"
        const val MODE_REGENERATE_SCENE = "regenerate_scene"
        const val MODE_RERENDER = "rerender"

        fun inputData(projectId: Long, mode: RunMode): Data = when (mode) {
            RunMode.Full -> workDataOf(KEY_PROJECT_ID to projectId, KEY_MODE to MODE_FULL)
            RunMode.ResumeAfterTopic -> workDataOf(KEY_PROJECT_ID to projectId, KEY_MODE to MODE_RESUME_AFTER_TOPIC)
            is RunMode.RegenerateScene -> workDataOf(
                KEY_PROJECT_ID to projectId,
                KEY_MODE to MODE_REGENERATE_SCENE,
                KEY_SCENE_NUMBER to mode.sceneNumber,
            )
            RunMode.Rerender -> workDataOf(KEY_PROJECT_ID to projectId, KEY_MODE to MODE_RERENDER)
        }

        fun parseMode(data: Data): RunMode? = when (data.getString(KEY_MODE) ?: MODE_FULL) {
            MODE_FULL -> RunMode.Full
            MODE_RESUME_AFTER_TOPIC -> RunMode.ResumeAfterTopic
            MODE_REGENERATE_SCENE -> {
                val scene = data.getInt(KEY_SCENE_NUMBER, -1)
                if (scene < 0) null else RunMode.RegenerateScene(scene)
            }
            MODE_RERENDER -> RunMode.Rerender
            else -> null
        }
    }
}
