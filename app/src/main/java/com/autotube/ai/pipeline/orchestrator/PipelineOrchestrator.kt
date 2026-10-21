package com.autotube.ai.pipeline.orchestrator

import com.autotube.ai.core.common.LowStorageException
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.AudioMixResult
import com.autotube.ai.core.model.CaptionTrack
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.EditFixAction
import com.autotube.ai.core.model.PipelineError
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.QcReport
import com.autotube.ai.core.model.RenderResult
import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.SeoPackage
import com.autotube.ai.core.model.StepState
import com.autotube.ai.core.model.StepStatus
import com.autotube.ai.core.model.UploadMode
import com.autotube.ai.core.model.UploadOptions
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.core.model.VoiceTimeline
import com.autotube.ai.domain.AudioEngine
import com.autotube.ai.domain.CaptionAligner
import com.autotube.ai.domain.DeviceStatus
import com.autotube.ai.domain.EditorBrain
import com.autotube.ai.domain.LengthManager
import com.autotube.ai.domain.MediaStoreSaver
import com.autotube.ai.domain.Progress
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.ProjectLogger
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.QualityChecker
import com.autotube.ai.domain.ScriptWriter
import com.autotube.ai.domain.SeoGenerator
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.ThumbnailMaker
import com.autotube.ai.domain.TimelineBuilder
import com.autotube.ai.domain.TopicResearcher
import com.autotube.ai.domain.VideoRenderer
import com.autotube.ai.domain.VisualSourcer
import com.autotube.ai.domain.VoiceSynthesizer
import com.autotube.ai.domain.WorkScheduler
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the video pipeline for one project: research -> script -> length -> voice -> captions ->
 * visuals -> edit plan -> music -> render -> quality check -> SEO/export, then hands off to review
 * or upload.
 *
 * Every step writes its result into the project, so a run can resume where a previous one stopped:
 * a step whose artifact already exists is SKIPPED, until one step actually runs again, after which
 * every later step is rebuilt. Failures become a readable [PipelineError] on the project and a
 * [RunOutcome.Failed]; cancellation marks the project CANCELLED and is rethrown.
 */
@Singleton
class PipelineOrchestrator @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val projectFiles: ProjectFiles,
    private val projectLogger: ProjectLogger,
    private val deviceStatus: DeviceStatus,
    private val mediaStoreSaver: MediaStoreSaver,
    private val topicResearcher: TopicResearcher,
    private val scriptWriter: ScriptWriter,
    private val lengthManager: LengthManager,
    private val voiceSynthesizer: VoiceSynthesizer,
    private val captionAligner: CaptionAligner,
    private val timelineBuilder: TimelineBuilder,
    private val visualSourcer: VisualSourcer,
    private val editorBrain: EditorBrain,
    private val audioEngine: AudioEngine,
    private val videoRenderer: VideoRenderer,
    private val qualityChecker: QualityChecker,
    private val seoGenerator: SeoGenerator,
    private val thumbnailMaker: ThumbnailMaker,
    private val workScheduler: Lazy<WorkScheduler>,
) {
    /** The newest run per project; an older, replaced run must not overwrite its status. */
    private val activeRuns = ConcurrentHashMap<Long, Any>()

    /**
     * Executes [mode] for [projectId]. [progressSink] receives overall progress (0..1) and the current
     * human-readable message; it may be called often and from any thread.
     */
    suspend fun run(
        projectId: Long,
        mode: RunMode,
        progressSink: (overall: Float, message: String) -> Unit,
    ): RunOutcome {
        val token = Any()
        activeRuns[projectId] = token
        try {
            return coroutineScope {
                val tracker = ProgressTracker(projectId, projectRepository, progressSink, this)
                try {
                    Run(projectId, mode, tracker).execute()
                } finally {
                    tracker.close()
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { markCancelled(projectId, token) }
            throw e
        } finally {
            activeRuns.remove(projectId, token)
        }
    }

    private suspend fun markCancelled(projectId: Long, token: Any) {
        if (activeRuns[projectId] !== token) return
        try {
            projectRepository.updateProject(projectId) { p ->
                if (!p.status.isActive) {
                    p
                } else {
                    val step = p.currentStep
                    val steps = if (step != null && p.step(step).status == StepStatus.RUNNING) {
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
            projectLogger.forStep(projectId, null).i("Run cancelled")
        } catch (e: Exception) {
            projectLogger.forStep(projectId, null).w("Could not mark the project as cancelled", e)
        }
    }

    /** State of one run. */
    private inner class Run(
        private val projectId: Long,
        private val mode: RunMode,
        private val tracker: ProgressTracker,
    ) {
        private lateinit var project: Project
        private lateinit var settings: AppSettings
        private var currentStep: PipelineStep? = null

        /** Becomes true once any step really ran; from then on later artifacts are stale. */
        private var dirty = false

        private val request: VideoRequest get() = project.request

        suspend fun execute(): RunOutcome {
            project = projectRepository.getProject(projectId)
                ?: return RunOutcome.Failed(
                    PipelineError(
                        step = null,
                        title = "Project not found",
                        message = "This project no longer exists.",
                        retryable = false,
                    ),
                )
            if (mode == RunMode.Full && project.status == ProjectStatus.UPLOADED) return RunOutcome.Completed
            return try {
                preflight()
                when (mode) {
                    RunMode.Full, RunMode.ResumeAfterTopic -> runFull()
                    is RunMode.RegenerateScene -> runRegenerate(mode.sceneNumber)
                    RunMode.Rerender -> runRerender()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }

        // ------------------------------------------------------------------ preflight / finish

        private suspend fun preflight() {
            settings = settingsRepository.current()
            save {
                it.copy(
                    status = ProjectStatus.RUNNING,
                    error = null,
                    statusMessage = "Starting…",
                    previewImagePath = it.previewImagePath ?: previewOf(it.artifacts.visuals),
                )
            }
            val log = projectLogger.forStep(projectId, null)
            log.i("Run started: mode=${modeName()}, project=$projectId")
            val free = deviceStatus.freeStorageMb()
            val needed = settings.minFreeStorageMb.toLong()
            if (free < needed) throw LowStorageException(free, needed)
            if (deviceStatus.isPowerSaveMode()) addWarning(POWER_SAVE_WARNING)
            log.i(settingsSummary(free))
        }

        private suspend fun finishRun(): RunOutcome {
            currentStep = null
            if (settings.reviewMode) return awaitReview("Your video is ready to review")
            if (settings.googleAccountEmail != null) {
                val options = project.uploadOptions ?: defaultUploadOptions()
                save {
                    it.copy(
                        status = ProjectStatus.UPLOADING,
                        uploadOptions = options,
                        overallProgress = 1f,
                        uploadProgress = 0f,
                        currentStep = PipelineStep.UPLOAD,
                        statusMessage = "Waiting to upload to YouTube",
                        steps = it.steps + (PipelineStep.UPLOAD to StepState(
                            status = StepStatus.WAITING,
                            message = "Waiting for a network connection",
                        )),
                    )
                }
                workScheduler.get().upload(projectId)
                projectLogger.forStep(projectId, PipelineStep.UPLOAD).i("Upload queued (${options.mode})")
                return RunOutcome.UploadQueued
            }
            addWarning(SIGN_IN_WARNING)
            return awaitReview("Your video is ready")
        }

        private suspend fun awaitReview(message: String): RunOutcome {
            save {
                it.copy(
                    status = ProjectStatus.AWAITING_REVIEW,
                    currentStep = null,
                    overallProgress = 1f,
                    statusMessage = message,
                    error = null,
                )
            }
            tracker.progress(PipelineStep.SEO, 1f, message)
            projectLogger.forStep(projectId, null).i("Run finished: $message")
            return RunOutcome.AwaitingReview
        }

        private suspend fun fail(error: Throwable): RunOutcome {
            val step = currentStep
            val pipelineError = PipelineErrors.from(error, step)
            projectLogger.forStep(projectId, step).e("Failed: ${pipelineError.title} - ${pipelineError.message}", error)
            val now = System.currentTimeMillis()
            projectRepository.updateProject(projectId) { p ->
                val steps = if (step != null) {
                    val old = p.step(step)
                    p.steps + (step to old.copy(
                        status = StepStatus.FAILED,
                        message = pipelineError.message,
                        attempts = maxOf(old.attempts, tracker.attemptsOf(step)),
                        finishedAt = now,
                    ))
                } else {
                    p.steps
                }
                p.copy(
                    status = ProjectStatus.FAILED,
                    error = pipelineError,
                    statusMessage = pipelineError.title,
                    steps = steps,
                )
            }
            return RunOutcome.Failed(pipelineError)
        }

        // ------------------------------------------------------------------ modes

        private suspend fun runFull(): RunOutcome {
            if (researchStep()) return RunOutcome.AwaitingTopic
            scriptStep()
            lengthStep()
            voiceStep()

            if (!dirty && project.artifacts.voiceTimeline != null && project.artifacts.captions != null) {
                skip(PipelineStep.CAPTIONS, "Captions already timed")
            } else {
                captionsStep()
            }

            if (!dirty && visualsValid(project.artifacts.visuals)) {
                skip(PipelineStep.VISUALS, "${project.artifacts.visuals.size} visuals already prepared")
            } else {
                visualsStep(existing = emptyList(), onlyScenes = null)
            }

            if (!dirty && project.artifacts.edl != null) {
                skip(PipelineStep.EDIT_PLAN, "Edit plan already made")
            } else {
                editPlanStep()
            }

            if (!dirty && project.artifacts.audioMix?.let { File(it.mixPath).exists() } == true) {
                skip(PipelineStep.MUSIC, "Sound mix already made")
            } else {
                musicStep()
            }

            if (!dirty && project.artifacts.render?.let { File(it.outputPath).exists() } == true) {
                skip(PipelineStep.RENDER, "Video already rendered")
            } else {
                renderStep()
            }

            if (!dirty && project.qcReports.isNotEmpty() &&
                project.step(PipelineStep.QUALITY_CHECK).status == StepStatus.DONE
            ) {
                skip(PipelineStep.QUALITY_CHECK, "Quality check already done")
            } else {
                qualityStep(maxFixRounds = settings.maxQcPasses.coerceIn(0, MAX_FIX_ROUNDS), appendReports = false)
            }

            val exported = project.seo != null &&
                project.galleryUri != null &&
                project.thumbnailPath?.let { File(it).exists() } == true
            if (!dirty && exported) {
                skip(PipelineStep.SEO, "Already saved to your gallery")
            } else {
                seoStep(keepSeo = false, makeThumbnail = true)
            }
            return finishRun()
        }

        private suspend fun runRegenerate(sceneNumber: Int): RunOutcome {
            val original = requireScript()
            requireTimeline()
            if (project.artifacts.sceneAudios.isEmpty()) throw missingResults(PipelineStep.VOICE)
            if (original.scenes.none { it.sceneNumber == sceneNumber }) {
                throw PipelineException(
                    userTitle = "Scene not found",
                    userMessage = "Scene $sceneNumber is not part of this script.",
                    retryable = false,
                    step = PipelineStep.SCRIPT,
                )
            }

            // Script: rewrite just this scene.
            val rewritten = runStep(PipelineStep.SCRIPT, "Rewriting scene $sceneNumber") { log, _ ->
                scriptWriter.rewriteScene(request, original, sceneNumber, REWRITE_INSTRUCTION, log)
            }.copy(sceneNumber = sceneNumber)
            val script = original.copy(scenes = original.scenes.map { if (it.sceneNumber == sceneNumber) rewritten else it })
            save { it.copy(script = script) }
            tracker.finish(PipelineStep.SCRIPT, "Scene $sceneNumber rewritten")

            // Voice: re-record only this scene at the speed the length manager chose.
            val speed = project.artifacts.voiceSpeed
            val sceneAudio = runStep(PipelineStep.VOICE, "Recording scene $sceneNumber") { log, _ ->
                voiceSynthesizer.synthesizeScene(projectId, request, rewritten, speed, log)
            }
            val order = script.scenes.map { it.sceneNumber }
            val audios = (project.artifacts.sceneAudios.filter { it.sceneNumber != sceneNumber } + sceneAudio)
                .sortedBy { order.indexOf(it.sceneNumber).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            save { it.copy(artifacts = it.artifacts.copy(sceneAudios = audios)) }
            tracker.finish(PipelineStep.VOICE, "Scene $sceneNumber recorded (${formatDuration(sceneAudio.durationMs)})")

            captionsStep()
            visualsStep(
                existing = project.artifacts.visuals.filter { it.sceneNumber != sceneNumber },
                onlyScenes = setOf(sceneNumber),
            )
            editPlanStep()
            musicStep()
            renderStep()
            qualityStep(maxFixRounds = 0, appendReports = true)
            seoStep(keepSeo = true, makeThumbnail = true)
            return awaitReview("Scene $sceneNumber regenerated. Review the new version.")
        }

        private suspend fun runRerender(): RunOutcome {
            requireScript()
            requireTimeline()
            requireCaptions()
            requireEdl()
            if (project.artifacts.visuals.isEmpty()) throw missingResults(PipelineStep.VISUALS)
            if (project.artifacts.sceneAudios.isEmpty()) throw missingResults(PipelineStep.VOICE)

            musicStep()
            renderStep()
            qualityStep(maxFixRounds = 0, appendReports = true)
            val hasThumbnail = project.thumbnailPath?.let { File(it).exists() } == true
            seoStep(keepSeo = true, makeThumbnail = !hasThumbnail)
            return awaitReview("Re-render finished. Review the new version.")
        }

        // ------------------------------------------------------------------ steps

        /** Returns true when the run must stop and wait for the user to pick a topic. */
        private suspend fun researchStep(): Boolean {
            val step = PipelineStep.RESEARCH
            val chosen = project.chosenTopic
            if (chosen != null) {
                if (mode == RunMode.ResumeAfterTopic) {
                    currentStep = step
                    tracker.finish(step, "Topic: ${chosen.title}")
                    dirty = true
                } else {
                    skip(step, "Topic: ${chosen.title}")
                }
                return false
            }

            val candidates = project.topicCandidates.ifEmpty {
                runStep(step, "Researching trending topics in ${request.niche}") { log, progress ->
                    topicResearcher.research(request, log, progress)
                }
            }
            if (candidates.isEmpty()) {
                throw PipelineException(
                    userTitle = "No topic ideas",
                    userMessage = "No topic ideas were found for \"${request.niche}\". Try a broader niche.",
                    retryable = true,
                    step = step,
                )
            }
            save { it.copy(topicCandidates = candidates) }

            if (mode != RunMode.ResumeAfterTopic && settings.reviewMode && candidates.size > 1) {
                save { it.copy(status = ProjectStatus.AWAITING_TOPIC, statusMessage = "Pick a topic to continue") }
                currentStep = step
                tracker.waiting(step, "Pick one of ${candidates.size} topics to continue")
                projectLogger.forStep(projectId, step).i("Waiting for the user to pick a topic")
                return true
            }

            val best = candidates.first()
            save { it.copy(chosenTopic = best) }
            currentStep = step
            tracker.finish(step, "Topic: ${best.title}")
            dirty = true
            return false
        }

        private suspend fun scriptStep() {
            val step = PipelineStep.SCRIPT
            val existing = project.script
            if (!dirty && existing != null && existing.scenes.isNotEmpty()) {
                skip(step, "Script ready: ${existing.scenes.size} scenes")
                return
            }
            val topic = project.chosenTopic ?: throw missingResults(PipelineStep.RESEARCH)
            val script = runStep(step, "Writing the script") { log, _ ->
                scriptWriter.write(request, topic, log)
            }
            if (script.scenes.isEmpty()) {
                throw PipelineException(
                    userTitle = "Empty script",
                    userMessage = "The script came back without any scenes. Try again.",
                    retryable = true,
                    step = step,
                )
            }
            save {
                it.copy(
                    script = script,
                    seo = null,
                    qcReports = emptyList(),
                )
            }
            tracker.finish(step, "Script written: ${script.scenes.size} scenes, ${script.totalWords} words")
        }

        private suspend fun lengthStep() {
            val step = PipelineStep.LENGTH_CHECK
            val previous = project.step(step).status
            if (!dirty && (previous == StepStatus.DONE || previous == StepStatus.SKIPPED)) {
                skip(step, "Length already checked")
                return
            }
            val script = requireScript()
            val fitted = runStep(step, "Checking the script length") { log, _ ->
                lengthManager.fitBeforeVoice(request, script, log)
            }
            save { it.copy(script = fitted) }
            val estimate = lengthManager.estimateDurationMs(fitted)
            tracker.finish(step, "Estimated length ${formatDuration(estimate)}")
        }

        private suspend fun voiceStep() {
            val step = PipelineStep.VOICE
            val script = requireScript()
            if (!dirty && audiosValid(project.artifacts.sceneAudios, script)) {
                skip(step, "Voiceover already recorded")
                return
            }
            val fit = runStep(step, "Recording the voiceover") { log, progress ->
                val audios = voiceSynthesizer.synthesizeAll(projectId, request, script, 1f, log) { f ->
                    progress(f * 0.7f)
                }
                tracker.progress(step, 0.7f, "Fitting the video length")
                lengthManager.fitAfterVoice(projectId, request, script, audios, log) { f ->
                    progress(0.7f + 0.3f * f)
                }
            }
            val log = projectLogger.forStep(projectId, step)
            fit.notes.forEach { log.i(it) }
            save {
                it.copy(
                    script = fit.script,
                    artifacts = it.artifacts.copy(
                        sceneAudios = fit.audios,
                        voiceSpeed = fit.speed,
                        lengthAttempts = fit.attempts,
                    ),
                )
            }
            if (!fit.withinLimits) {
                val range = lengthManager.allowedRangeMs(request)
                addWarning(
                    "The video is ${formatDuration(fit.finalDurationMs)} long, outside the target " +
                        "${formatDuration(range.first)}–${formatDuration(range.last)}.",
                )
            }
            tracker.finish(step, "Voiceover ready: ${formatDuration(fit.finalDurationMs)}")
        }

        private suspend fun captionsStep() {
            val step = PipelineStep.CAPTIONS
            val script = requireScript()
            val audios = project.artifacts.sceneAudios
            if (audios.isEmpty()) throw missingResults(PipelineStep.VOICE)
            val result = runStep(step, "Timing every word for the captions") { log, progress ->
                val aligned = captionAligner.align(projectId, request, script, audios, log, progress)
                val timeline = timelineBuilder.build(request, script, aligned)
                val captions = captionAligner.buildTrack(request, script, aligned, timeline, settings)
                CaptionResult(aligned, timeline, captions)
            }
            save {
                it.copy(
                    artifacts = it.artifacts.copy(
                        sceneAudios = result.audios,
                        voiceTimeline = result.timeline,
                        captions = result.captions,
                    ),
                )
            }
            tracker.finish(step, "${result.captions.words.size} words timed (${formatDuration(result.timeline.totalMs)})")
        }

        private suspend fun visualsStep(existing: List<VisualAsset>, onlyScenes: Set<Int>?) {
            val step = PipelineStep.VISUALS
            val script = requireScript()
            val timeline = requireTimeline()
            val sceneCount = onlyScenes?.size ?: script.scenes.size
            val visuals = runStep(step, "Finding visuals for $sceneCount scene${if (sceneCount == 1) "" else "s"}") { log, progress ->
                visualSourcer.source(
                    projectId = projectId,
                    request = request,
                    script = script,
                    timeline = timeline,
                    existing = existing,
                    onlyScenes = onlyScenes,
                    log = log,
                    onProgress = progress,
                )
            }
            if (visuals.isEmpty()) {
                throw PipelineException(
                    userTitle = "No visuals",
                    userMessage = "No pictures or clips could be prepared for this video. Try again.",
                    retryable = true,
                    step = step,
                )
            }
            save {
                it.copy(
                    artifacts = it.artifacts.copy(visuals = visuals),
                    previewImagePath = previewOf(visuals) ?: it.previewImagePath,
                )
            }
            tracker.finish(step, "${visuals.size} visuals ready")
        }

        private suspend fun editPlanStep() {
            val step = PipelineStep.EDIT_PLAN
            val script = requireScript()
            val timeline = requireTimeline()
            val captions = requireCaptions()
            val audios = project.artifacts.sceneAudios
            val visuals = project.artifacts.visuals
            val edl = runStep(step, "The AI editor is planning the cuts") { log, _ ->
                editorBrain.plan(request, script, timeline, audios, captions, visuals, log)
            }
            save { it.copy(artifacts = it.artifacts.copy(edl = edl)) }
            tracker.finish(step, "${edl.shots.size} shots planned")
        }

        private suspend fun musicStep() {
            val step = PipelineStep.MUSIC
            val script = requireScript()
            val timeline = requireTimeline()
            val edl = requireEdl()
            val audios = project.artifacts.sceneAudios
            val mix = runStep(step, "Mixing voice, music and sound effects") { log, progress ->
                audioEngine.mix(projectId, request, script, timeline, audios, edl, settings, log, progress)
            }
            save { it.copy(artifacts = it.artifacts.copy(audioMix = mix)) }
            tracker.finish(step, "Sound mixed at ${"%.1f".format(mix.integratedLufs)} LUFS")
        }

        private suspend fun renderStep() {
            val step = PipelineStep.RENDER
            val edl = requireEdl()
            val captions = requireCaptions()
            val mix = project.artifacts.audioMix ?: throw missingResults(PipelineStep.MUSIC)
            val visuals = project.artifacts.visuals
            val result = runStep(step, "Rendering the video") { log, progress ->
                renderPass(edl, visuals, captions, mix, log, progress)
            }
            save {
                it.copy(
                    artifacts = it.artifacts.copy(render = result),
                    outputVideoPath = result.outputPath,
                )
            }
            tracker.finish(step, "Rendered ${formatDuration(result.durationMs)} at ${result.width}x${result.height}")
        }

        private suspend fun qualityStep(maxFixRounds: Int, appendReports: Boolean) {
            val step = PipelineStep.QUALITY_CHECK
            if (!settings.qualityCheck) {
                skip(step, "Quality check is turned off in Settings")
                return
            }
            val script = requireScript()
            val timeline = requireTimeline()
            val captions = requireCaptions()
            var edl = requireEdl()
            var visuals = project.artifacts.visuals
            var mix = project.artifacts.audioMix ?: throw missingResults(PipelineStep.MUSIC)
            var render = project.artifacts.render ?: throw missingResults(PipelineStep.RENDER)
            val audios = project.artifacts.sceneAudios

            currentStep = step
            tracker.begin(step, "Checking the finished video")
            val log = projectLogger.forStep(projectId, step)

            val reports = if (appendReports) project.qcReports.toMutableList() else mutableListOf()
            var pass = reports.size + 1
            var fixRounds = 0
            var musicVolume = settings.musicVolume
            var best: QcCandidate? = null
            val totalRounds = maxFixRounds + 1

            while (true) {
                val base = fixRounds.toFloat() / totalRounds
                val span = 1f / totalRounds
                tracker.progress(step, base, "Quality check pass $pass")
                val checkPass = pass
                val checkEdl = edl
                val checkVisuals = visuals
                val checkMix = mix
                val checkRender = render
                val report: QcReport = retrying(step, log) {
                    qualityChecker.check(
                        projectId, request, script, checkEdl, checkVisuals, captions, checkMix, checkRender,
                        checkPass, settings, log,
                    ) { f -> tracker.progress(step, base + span * 0.4f * f) }
                }
                reports += report
                val snapshot = reports.toList()
                save { it.copy(qcReports = snapshot) }
                log.i(
                    "QC pass $pass: human feel ${report.humanFeelScore}/10, pacing ${report.pacingScore}/10, " +
                        "${report.issues.size} issues, ${report.fixes.size} fixes",
                )

                val currentBest = best
                if (currentBest == null || report.humanFeelScore > currentBest.humanFeel) {
                    best = QcCandidate(render, edl, mix, visuals, report.humanFeelScore)
                }
                if (!report.needsFix || fixRounds >= maxFixRounds) break
                fixRounds++

                tracker.progress(step, base + span * 0.45f, "Fixing ${report.fixes.size} issue${if (report.fixes.size == 1) "" else "s"}")
                val updatedVisuals = visuals.toMutableList()
                for (fix in report.fixes) {
                    if (fix.action != EditFixAction.REPLACE_VISUAL) continue
                    val sceneNumber = fix.sceneNumber
                        ?: fix.shotIndex?.let { idx -> edl.shots.firstOrNull { it.index == idx }?.sceneNumber }
                        ?: continue
                    val scene = script.scenes.firstOrNull { it.sceneNumber == sceneNumber } ?: continue
                    val exclude = updatedVisuals.map { it.id }.toSet()
                    val replacement = retrying(step, log) {
                        visualSourcer.replace(projectId, request, scene, exclude, log)
                    }
                    if (replacement != null) {
                        updatedVisuals += replacement
                        log.i("Scene $sceneNumber: new visual ${replacement.id}")
                    } else {
                        log.w("Scene $sceneNumber: no replacement visual found")
                    }
                }
                visuals = updatedVisuals
                val fixedEdl = editorBrain.applyFixes(edl, report.fixes, script, visuals, timeline)
                val lowerMusic = report.fixes.any { it.action == EditFixAction.LOWER_MUSIC }
                val timingChanged = fixedEdl.totalMs != edl.totalMs ||
                    report.fixes.any { it.action in TIMING_FIXES }
                edl = fixedEdl
                val newVisuals = visuals
                val newEdl = edl
                save { it.copy(artifacts = it.artifacts.copy(visuals = newVisuals, edl = newEdl)) }

                if (lowerMusic || timingChanged) {
                    if (lowerMusic) musicVolume = (musicVolume * MUSIC_LOWER_FACTOR).coerceIn(0f, 1f)
                    tracker.progress(step, base + span * 0.5f, "Re-mixing the sound")
                    val mixSettings = settings.copy(musicVolume = musicVolume)
                    mix = retrying(step, log) {
                        audioEngine.mix(projectId, request, script, timeline, audios, newEdl, mixSettings, log)
                    }
                    val newMix = mix
                    save { it.copy(artifacts = it.artifacts.copy(audioMix = newMix)) }
                }

                pass++
                tracker.progress(step, base + span * 0.6f, "Re-rendering with fixes")
                val renderMix = mix
                render = retrying(step, log) {
                    renderPass(newEdl, newVisuals, captions, renderMix, log) { f ->
                        tracker.progress(step, base + span * (0.6f + 0.4f * f))
                    }
                }
                val newRender = render
                save {
                    it.copy(
                        artifacts = it.artifacts.copy(render = newRender),
                        outputVideoPath = newRender.outputPath,
                    )
                }
            }

            val chosen = checkNotNull(best)
            val finalReports = reports.toList()
            save {
                it.copy(
                    qcReports = finalReports,
                    outputVideoPath = chosen.render.outputPath,
                    artifacts = it.artifacts.copy(
                        render = chosen.render,
                        edl = chosen.edl,
                        audioMix = chosen.mix,
                        visuals = chosen.visuals,
                    ),
                )
            }
            if (chosen.render !== render) log.i("Keeping the earlier render (${chosen.render.outputPath}); it scored higher")
            if (chosen.humanFeel < settings.minHumanFeelScore) {
                addWarning("The quality check rated this video ${chosen.humanFeel}/10 for human feel. Review it before uploading.")
            }
            tracker.finish(step, "Quality check done: human feel ${chosen.humanFeel}/10")
            dirty = true
        }

        private suspend fun seoStep(keepSeo: Boolean, makeThumbnail: Boolean) {
            val step = PipelineStep.SEO
            val script = requireScript()
            val timeline = requireTimeline()
            val existingSeo = project.seo
            val message = when {
                keepSeo && existingSeo != null && makeThumbnail -> "Making a new thumbnail"
                keepSeo && existingSeo != null -> "Saving the new version"
                else -> "Writing the title, description and thumbnail"
            }
            runStep(step, message, retry = false) { log, progress ->
                val seo = if (keepSeo && existingSeo != null) {
                    existingSeo
                } else {
                    val generated = retrying(step, log) { seoGenerator.generate(request, script, timeline, log) }
                    save { it.copy(seo = generated) }
                    generated
                }
                progress(0.3f)
                exportOutputs(script, seo, makeThumbnail, log, progress)
            }
            tracker.finish(step, "Saved to your gallery")
        }

        // ------------------------------------------------------------------ helpers

        private suspend fun renderPass(
            edl: EditDecisionList,
            visuals: List<VisualAsset>,
            captions: CaptionTrack,
            mix: AudioMixResult,
            log: StepLogger,
            onProgress: Progress,
        ): RenderResult {
            val pass = project.artifacts.renderPasses + 1
            val outFile = withContext(Dispatchers.IO) {
                val dir = projectFiles.renderDir(projectId)
                if (!dir.exists()) dir.mkdirs()
                File(dir, "final_pass$pass.mp4").also { if (it.exists()) it.delete() }
            }
            log.i("Render pass $pass -> ${outFile.name}")
            val result = videoRenderer.render(projectId, request, edl, visuals, captions, mix, settings, outFile, log, onProgress)
            val ok = withContext(Dispatchers.IO) { File(result.outputPath).let { it.exists() && it.length() > 0 } }
            if (!ok) {
                throw PipelineException(
                    userTitle = "Rendering failed",
                    userMessage = "Rendering failed on this device's video encoder: no video file was written.",
                    retryable = true,
                    step = PipelineStep.RENDER,
                )
            }
            save { it.copy(artifacts = it.artifacts.copy(renderPasses = pass)) }
            return result
        }

        private suspend fun exportOutputs(
            script: Script,
            seo: SeoPackage,
            makeThumbnail: Boolean,
            log: StepLogger,
            progress: Progress,
        ) {
            val existingThumb = project.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
            val thumbnail = if (makeThumbnail || existingThumb == null) {
                val visuals = project.artifacts.visuals
                retrying(PipelineStep.SEO, log) {
                    thumbnailMaker.make(projectId, request, script, seo, visuals, log)
                }
            } else {
                existingThumb
            }
            save { it.copy(thumbnailPath = thumbnail.absolutePath, previewImagePath = thumbnail.absolutePath) }
            progress(0.55f)

            val videoPath = project.outputVideoPath ?: throw missingResults(PipelineStep.RENDER)
            val video = File(videoPath)
            if (!withContext(Dispatchers.IO) { video.exists() }) {
                throw PipelineException(
                    userTitle = "Video file missing",
                    userMessage = "The rendered video file is missing. Re-render the video and try again.",
                    retryable = false,
                    step = PipelineStep.SEO,
                )
            }
            val name = galleryName(seo.title)
            val videoUri = retrying(PipelineStep.SEO, log) { mediaStoreSaver.saveVideo(video, name) }
            save { it.copy(galleryUri = videoUri.toString()) }
            log.i("Saved video to the gallery as \"$name\"")
            progress(0.85f)
            try {
                mediaStoreSaver.saveImage(thumbnail, "$name thumbnail")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("Thumbnail could not be saved to the gallery", e)
            }
            if (settings.deleteTempAfterExport) cleanUp(log)
            progress(1f)
        }

        private suspend fun cleanUp(log: StepLogger) {
            projectFiles.clearTemp(projectId, keepFinal = true)
            val keep = project.outputVideoPath?.let { File(it).absolutePath }
            withContext(Dispatchers.IO) {
                projectFiles.renderDir(projectId).listFiles()
                    ?.filter { it.isFile && it.name.startsWith("final_pass") && it.name.endsWith(".mp4") }
                    ?.filter { it.absolutePath != keep }
                    ?.forEach { file ->
                        if (file.delete()) log.i("Deleted superseded ${file.name}")
                    }
            }
        }

        private suspend fun <T> runStep(
            step: PipelineStep,
            message: String,
            retry: Boolean = true,
            block: suspend (log: StepLogger, progress: Progress) -> T,
        ): T {
            currentStep = step
            tracker.begin(step, message)
            val log = projectLogger.forStep(projectId, step)
            log.i("Step started: $message")
            val progress: Progress = { f -> tracker.progress(step, f) }
            val result = if (retry) {
                retrying(step, log) { block(log, progress) }
            } else {
                block(log, progress)
            }
            dirty = true
            return result
        }

        private suspend fun <T> retrying(step: PipelineStep, log: StepLogger, block: suspend () -> T): T =
            withRetry(
                attempts = 3,
                onRetry = { attempt, e ->
                    log.w("Attempt $attempt failed (${e.message?.take(300)}); retrying", e)
                    tracker.retrying(step, attempt, shortReason(e))
                },
            ) { block() }

        private suspend fun skip(step: PipelineStep, message: String) {
            currentStep = step
            tracker.finish(step, message, StepStatus.SKIPPED)
        }

        private suspend fun save(transform: (Project) -> Project): Project {
            val updated = projectRepository.updateProject(projectId, transform)
                ?: throw PipelineException(
                    userTitle = "Project deleted",
                    userMessage = "This project was deleted while the video was being made.",
                    retryable = false,
                )
            project = updated
            return updated
        }

        private suspend fun addWarning(text: String) {
            save { if (text in it.warnings) it else it.copy(warnings = it.warnings + text) }
            projectLogger.forStep(projectId, currentStep).w(text)
        }

        private fun requireScript(): Script = project.script ?: throw missingResults(PipelineStep.SCRIPT)
        private fun requireTimeline(): VoiceTimeline =
            project.artifacts.voiceTimeline ?: throw missingResults(PipelineStep.CAPTIONS)
        private fun requireCaptions(): CaptionTrack =
            project.artifacts.captions ?: throw missingResults(PipelineStep.CAPTIONS)
        private fun requireEdl(): EditDecisionList =
            project.artifacts.edl ?: throw missingResults(PipelineStep.EDIT_PLAN)

        private fun missingResults(step: PipelineStep) = PipelineException(
            userTitle = "Earlier results missing",
            userMessage = "The results of \"${step.title}\" are missing. Start this video again from the beginning.",
            retryable = false,
            step = step,
        )

        private fun defaultUploadOptions(): UploadOptions {
            val mode = settings.defaultUploadMode
            return UploadOptions(
                mode = mode,
                publishAtMillis = if (mode == UploadMode.SCHEDULE) System.currentTimeMillis() + DEFAULT_SCHEDULE_DELAY_MS else null,
                madeForKids = settings.madeForKids,
                categoryId = settings.youtubeCategoryId,
            )
        }

        private fun modeName(): String = when (mode) {
            RunMode.Full -> "full"
            RunMode.ResumeAfterTopic -> "resume-after-topic"
            is RunMode.RegenerateScene -> "regenerate-scene-${mode.sceneNumber}"
            RunMode.Rerender -> "rerender"
        }

        private fun settingsSummary(freeMb: Long): String = buildString {
            append("Settings: format=").append(request.format.name)
            append(", target=").append(request.targetDurationSec).append("s")
            append(", language=").append(request.language.code)
            append(", review=").append(settings.reviewMode)
            append(", offline=").append(settings.forceOffline)
            append(", llm=").append(settings.llmProvider.name)
            append(", tts=").append(settings.ttsProvider.name)
            append(", stt=").append(settings.sttProvider.name)
            append(", stock=").append(settings.useStockFootage)
            append(", t2v=").append(settings.textToVideoProvider.name)
            append(", images=").append(settings.imageGenProvider.name)
            append(", music=").append(settings.musicStyle.name)
            append(", captions=").append(settings.captionStyle.name)
            append(", qc=").append(settings.qualityCheck).append("/").append(settings.maxQcPasses)
            append(", wifiOnly=").append(settings.wifiOnly)
            append(", freeMb=").append(freeMb)
            append(", powerSave=").append(deviceStatus.isPowerSaveMode())
            append(", battery=").append(deviceStatus.batteryPercent()).append("%")
            append(", signedIn=").append(settings.googleAccountEmail != null)
        }
    }

    private class CaptionResult(
        val audios: List<SceneAudio>,
        val timeline: VoiceTimeline,
        val captions: CaptionTrack,
    )

    private class QcCandidate(
        val render: RenderResult,
        val edl: EditDecisionList,
        val mix: AudioMixResult,
        val visuals: List<VisualAsset>,
        val humanFeel: Int,
    )

    companion object {
        const val POWER_SAVE_WARNING = "Battery saver is on: rendering may be slower"
        const val SIGN_IN_WARNING = "Sign in with Google in Settings to upload automatically"
        private const val REWRITE_INSTRUCTION = "Rewrite this scene with a fresher angle, same role and length"
        private const val MAX_FIX_ROUNDS = 2
        private const val MUSIC_LOWER_FACTOR = 0.7f
        private const val DEFAULT_SCHEDULE_DELAY_MS = 24L * 60 * 60 * 1000
        private val TIMING_FIXES = setOf(EditFixAction.SHORTEN_SHOTS, EditFixAction.LENGTHEN_SHOTS)

        private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
        private val WHITESPACE = Regex("\\s+")

        /** File-system safe gallery name; keeps Devanagari and other Unicode letters. */
        internal fun galleryName(title: String): String {
            val cleaned = title.replace(ILLEGAL_NAME_CHARS, " ").replace(WHITESPACE, " ").trim().trim('.', ' ')
            return cleaned.take(80).trim().ifBlank { "AutoTube video" }
        }

        internal fun formatDuration(ms: Long): String {
            val totalSec = (ms.coerceAtLeast(0) + 500) / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }

        private fun previewOf(visuals: List<VisualAsset>): String? = visuals.firstNotNullOfOrNull { asset ->
            asset.previewPath?.takeIf { File(it).exists() }
                ?: asset.localPath.takeIf { !asset.isVideo && File(it).exists() }
        }

        private fun audiosValid(audios: List<SceneAudio>, script: Script): Boolean {
            if (audios.isEmpty()) return false
            val byScene = audios.associateBy { it.sceneNumber }
            return script.scenes.all { scene ->
                val audio = byScene[scene.sceneNumber]
                audio != null && audio.durationMs > 0 && File(audio.filePath).exists()
            }
        }

        private fun visualsValid(visuals: List<VisualAsset>): Boolean =
            visuals.isNotEmpty() && visuals.all { File(it.localPath).exists() }

        private fun shortReason(e: Throwable): String =
            (e as? PipelineException)?.userTitle ?: e.javaClass.simpleName
    }
}
