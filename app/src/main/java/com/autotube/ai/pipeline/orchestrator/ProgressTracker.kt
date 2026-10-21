package com.autotube.ai.pipeline.orchestrator

import android.util.Log
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.StepState
import com.autotube.ai.core.model.StepStatus
import com.autotube.ai.domain.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks step states and the overall progress of one run.
 *
 * Step boundaries ([begin], [finish], [fail], [waiting]) are written to the database immediately.
 * In-step progress ([progress]) is non-suspending (it is called from the steps' progress callbacks)
 * and is persisted by a background collector at most once every [PERSIST_INTERVAL_MS], so long
 * renders do not churn Room. A throttled write is only applied while its step is still the
 * running step, so it can never overwrite a later boundary write.
 */
internal class ProgressTracker(
    private val projectId: Long,
    private val repository: ProjectRepository,
    private val sink: (overall: Float, message: String) -> Unit,
    scope: CoroutineScope,
) {
    private data class PendingProgress(
        val step: PipelineStep,
        val fraction: Float,
        val message: String,
        val overall: Float,
    )

    private val pending = MutableStateFlow<PendingProgress?>(null)
    private val lastMessage = ConcurrentHashMap<PipelineStep, String>()
    private val attempts = ConcurrentHashMap<PipelineStep, Int>()

    private val collector: Job = scope.launch {
        pending.filterNotNull().collect { p ->
            try {
                persistProgress(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A missed progress write is harmless; the next boundary write corrects it.
                Log.w(TAG, "Progress write failed", e)
            }
            delay(PERSIST_INTERVAL_MS)
        }
    }

    /** Marks [step] RUNNING and makes it the current step. */
    suspend fun begin(step: PipelineStep, message: String) {
        lastMessage[step] = message
        attempts[step] = 1
        val overall = StepWeights.overall(step, 0f)
        val now = System.currentTimeMillis()
        repository.updateProject(projectId) { p ->
            p.copy(
                currentStep = step,
                overallProgress = overall,
                statusMessage = message,
                steps = p.steps + (step to StepState(
                    status = StepStatus.RUNNING,
                    progress = 0f,
                    message = message,
                    attempts = 1,
                    startedAt = now,
                    finishedAt = null,
                )),
            )
        }
        sink(overall, message)
    }

    /** Reports in-step progress (0..1). Safe to call from any thread, very often. */
    fun progress(step: PipelineStep, fraction: Float, message: String? = null) {
        val text = message ?: lastMessage[step] ?: step.title
        if (message != null) lastMessage[step] = message
        val clamped = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        val overall = StepWeights.overall(step, clamped)
        pending.value = PendingProgress(step, clamped, text, overall)
        sink(overall, text)
    }

    /** Called before a retry so the UI shows that the step is being attempted again. */
    fun retrying(step: PipelineStep, attempt: Int, reason: String) {
        attempts[step] = attempt + 1
        progress(step, 0f, "Retrying (${attempt + 1}/3): $reason")
    }

    /** Marks [step] DONE or SKIPPED (anything else is coerced to DONE). */
    suspend fun finish(step: PipelineStep, message: String, status: StepStatus = StepStatus.DONE) {
        val finalStatus = if (status == StepStatus.SKIPPED) StepStatus.SKIPPED else StepStatus.DONE
        lastMessage[step] = message
        val overall = StepWeights.overall(step, 1f)
        val now = System.currentTimeMillis()
        val tries = attempts[step] ?: 0
        repository.updateProject(projectId) { p ->
            val old = p.step(step)
            p.copy(
                currentStep = step,
                overallProgress = overall,
                statusMessage = message,
                steps = p.steps + (step to old.copy(
                    status = finalStatus,
                    progress = 1f,
                    message = message,
                    attempts = if (finalStatus == StepStatus.SKIPPED) old.attempts else tries,
                    startedAt = old.startedAt ?: now,
                    finishedAt = now,
                )),
            )
        }
        sink(overall, message)
    }

    /** Marks [step] WAITING (for user input). */
    suspend fun waiting(step: PipelineStep, message: String) {
        val overall = StepWeights.overall(step, 1f)
        repository.updateProject(projectId) { p ->
            p.copy(
                currentStep = step,
                overallProgress = overall,
                statusMessage = message,
                steps = p.steps + (step to p.step(step).copy(
                    status = StepStatus.WAITING,
                    progress = 1f,
                    message = message,
                )),
            )
        }
        sink(overall, message)
    }

    /** Overall progress value at the start of [step]. */
    fun overallAt(step: PipelineStep, fraction: Float = 0f): Float = StepWeights.overall(step, fraction)

    fun attemptsOf(step: PipelineStep): Int = attempts[step] ?: 0

    /** Stops the background persistence job. */
    fun close() {
        collector.cancel()
    }

    private suspend fun persistProgress(p: PendingProgress) {
        repository.updateProject(projectId) { project ->
            val state = project.step(p.step)
            if (project.status != ProjectStatus.RUNNING ||
                project.currentStep != p.step ||
                state.status != StepStatus.RUNNING
            ) {
                project
            } else {
                project.copy(
                    overallProgress = p.overall,
                    statusMessage = p.message,
                    steps = project.steps + (p.step to state.copy(
                        progress = p.fraction,
                        message = p.message,
                        attempts = attempts[p.step] ?: state.attempts,
                    )),
                )
            }
        }
    }

    companion object {
        const val PERSIST_INTERVAL_MS = 700L
        private const val TAG = "ProgressTracker"
    }
}
