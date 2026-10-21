package com.autotube.ai.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.StepState
import com.autotube.ai.core.model.StepStatus
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.WorkScheduler
import com.autotube.ai.pipeline.orchestrator.RunMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [WorkScheduler]. One unique pipeline job ("pipeline-<id>") and one unique
 * upload job ("upload-<id>") per project.
 */
@Singleton
class WorkSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val projectRepository: ProjectRepository,
    private val userCancellations: UserCancellations,
) : WorkScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun startPipeline(projectId: Long) {
        enqueuePipeline(projectId, RunMode.Full, ExistingWorkPolicy.KEEP)
    }

    override fun resumeAfterTopic(projectId: Long) {
        enqueuePipeline(projectId, RunMode.ResumeAfterTopic, ExistingWorkPolicy.KEEP)
    }

    override fun regenerateScene(projectId: Long, sceneNumber: Int) {
        enqueuePipeline(projectId, RunMode.RegenerateScene(sceneNumber), ExistingWorkPolicy.REPLACE)
    }

    override fun rerender(projectId: Long) {
        enqueuePipeline(projectId, RunMode.Rerender, ExistingWorkPolicy.REPLACE)
    }

    override fun upload(projectId: Long) {
        userCancellations.clear(projectId)
        val settings = settingsRepository.current()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(UploadWorker.inputData(projectId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UPLOAD_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_UPLOAD)
            .addTag(projectTag(projectId))
            .build()
        // Status first, then enqueue, so a fast-finishing worker is never overwritten by this write.
        launchUpdate("queue the upload", then = {
            workManager.enqueueUniqueWork(uploadWorkName(projectId), ExistingWorkPolicy.KEEP, request)
        }) {
            projectRepository.updateProject(projectId) { p ->
                if (p.status == ProjectStatus.UPLOADING || p.status == ProjectStatus.UPLOADED) {
                    p
                } else {
                    val waitMessage = if (settings.wifiOnly) "Waiting for Wi-Fi" else "Waiting for a network connection"
                    p.copy(
                        status = ProjectStatus.UPLOADING,
                        currentStep = PipelineStep.UPLOAD,
                        uploadProgress = 0f,
                        error = null,
                        statusMessage = "Waiting to upload to YouTube",
                        steps = p.steps + (PipelineStep.UPLOAD to StepState(
                            status = StepStatus.WAITING,
                            message = waitMessage,
                        )),
                    )
                }
            }
        }
    }

    override fun cancel(projectId: Long) {
        userCancellations.markCancelled(projectId)
        workManager.cancelUniqueWork(pipelineWorkName(projectId))
        workManager.cancelUniqueWork(uploadWorkName(projectId))
        launchUpdate("mark cancelled") {
            projectRepository.updateProject(projectId) { p ->
                if (!p.status.isActive) {
                    p
                } else {
                    val step = p.currentStep
                    val steps = if (step != null && p.step(step).status in ACTIVE_STEP_STATES) {
                        p.steps + (step to p.step(step).copy(
                            status = StepStatus.PENDING,
                            message = "Cancelled",
                            finishedAt = System.currentTimeMillis(),
                        ))
                    } else {
                        p.steps
                    }
                    p.copy(status = ProjectStatus.CANCELLED, statusMessage = "Cancelled", steps = steps)
                }
            }
        }
    }

    /** Latest pipeline WorkInfo for a project (the unfinished one if any). Not part of [WorkScheduler]. */
    fun observeWork(projectId: Long): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(pipelineWorkName(projectId)).map { infos ->
            infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
        }

    /** Latest upload WorkInfo for a project. */
    fun observeUpload(projectId: Long): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(uploadWorkName(projectId)).map { infos ->
            infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
        }

    private fun enqueuePipeline(projectId: Long, mode: RunMode, policy: ExistingWorkPolicy) {
        userCancellations.clear(projectId)
        val settings = settingsRepository.current()
        val usesOnlineProviders = !settings.forceOffline && settingsRepository.apiKeysPresent.value.isNotEmpty()
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(if (usesOnlineProviders) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<PipelineWorker>()
            .setInputData(PipelineWorker.inputData(projectId, mode))
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_PIPELINE)
            .addTag(projectTag(projectId))
            .build()
        launchUpdate("queue the pipeline", then = {
            workManager.enqueueUniqueWork(pipelineWorkName(projectId), policy, request)
        }) {
            projectRepository.updateProject(projectId) { p ->
                if (p.status.isActive) {
                    p
                } else {
                    p.copy(
                        status = ProjectStatus.QUEUED,
                        error = null,
                        statusMessage = if (usesOnlineProviders) "Waiting to start (needs internet)…" else "Waiting to start…",
                    )
                }
            }
        }
    }

    /** Runs the status [block] off the main thread, then [then] even if the status write failed. */
    private fun launchUpdate(what: String, then: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not update the project status ($what)", e)
            }
            try {
                then()
            } catch (e: Exception) {
                Log.e(TAG, "Could not $what", e)
            }
        }
    }

    companion object {
        private const val TAG = "WorkScheduler"
        const val TAG_PIPELINE = "autotube-pipeline"
        const val TAG_UPLOAD = "autotube-upload"
        private const val UPLOAD_BACKOFF_SECONDS = 30L
        private val ACTIVE_STEP_STATES = setOf(StepStatus.RUNNING, StepStatus.WAITING)

        fun pipelineWorkName(projectId: Long): String = "pipeline-$projectId"
        fun uploadWorkName(projectId: Long): String = "upload-$projectId"
        fun projectTag(projectId: Long): String = "project-$projectId"
    }
}
