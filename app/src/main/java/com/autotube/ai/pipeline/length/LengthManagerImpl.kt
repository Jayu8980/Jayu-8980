package com.autotube.ai.pipeline.length

import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.ScenePriority
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.domain.LengthFitResult
import com.autotube.ai.domain.LengthManager
import com.autotube.ai.domain.Progress
import com.autotube.ai.domain.ScriptWriter
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.TimelineBuilder
import com.autotube.ai.pipeline.script.ScriptRepair
import com.autotube.ai.pipeline.voice.VoiceSynthesizerImpl
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Enforces the hard length limits: Shorts end at or before 59 s; long-form stays within +-5 % of the
 * target. Before the voiceover it resizes the script from word-count estimates; afterwards it measures
 * the real timeline and fixes it with (a) a 0.9-1.1 pitch-preserving speed change, (b) tighter
 * pauses, or (c) regenerating the worst scenes. Max 3 attempts each.
 */
@Singleton
class LengthManagerImpl @Inject constructor(
    private val scriptWriter: ScriptWriter,
    private val voice: VoiceSynthesizerImpl,
    private val timelineBuilder: TimelineBuilder,
) : LengthManager {

    override fun estimateDurationMs(script: Script, speed: Float): Long =
        DurationModel.estimateMs(script, DurationModel.looksLikeShorts(script), speed)

    override fun allowedRangeMs(request: VideoRequest): LongRange = DurationModel.allowedRange(request)

    override suspend fun fitBeforeVoice(request: VideoRequest, script: Script, log: StepLogger): Script {
        val range = allowedRangeMs(request)
        val centre = (range.first + range.last) / 2
        var current = script
        for (attempt in 1..MAX_ATTEMPTS) {
            val est = DurationModel.estimateMs(current, request.isShorts)
            if (est in range) {
                log.i("Estimated length ${sec(est)} fits ${sec(range.first)}-${sec(range.last)}")
                return current
            }
            log.i("Attempt $attempt: estimated ${sec(est)}, target window ${sec(range.first)}-${sec(range.last)}")
            current = try {
                if (est > range.last) scriptWriter.shorten(request, current, centre, log)
                else scriptWriter.expand(request, current, centre, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("Resizing the script failed on attempt $attempt", e)
                current
            }
        }
        val est = DurationModel.estimateMs(current, request.isShorts)
        if (request.isShorts && est > range.last) {
            current = forceFitShorts(current, range.last, log) { s -> DurationModel.estimateMs(s, true) }
        }
        log.i("Script length after fitting: ~${sec(DurationModel.estimateMs(current, request.isShorts))}")
        return current
    }

    override suspend fun fitAfterVoice(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        audios: List<SceneAudio>,
        log: StepLogger,
        onProgress: Progress,
    ): LengthFitResult {
        val range = allowedRangeMs(request)
        val centre = (range.first + range.last) / 2
        val notes = mutableListOf<String>()
        var currentScript = script
        var currentAudios = alignToScript(script, audios)
        var speed = currentAudios.map { it.speed }.average().takeIf { !it.isNaN() }?.toFloat() ?: 1f
        var attempts = 0

        fun measure(): Long = timelineBuilder.build(request, currentScript, currentAudios).totalMs

        onProgress(0f)
        while (attempts < MAX_ATTEMPTS) {
            val total = measure()
            if (total in range) break
            attempts++
            val speech = currentAudios.sumOf { it.durationMs }
            val fixed = total - speech
            val requiredSpeech = (centre - fixed).coerceAtLeast(1_000)
            val ratio = speech.toDouble() / requiredSpeech // > 1 means we need to go faster
            val wantedSpeed = (speed * ratio).toFloat()
            log.i("Length attempt $attempts: ${sec(total)} vs ${sec(range.first)}-${sec(range.last)} (speed needed ${"%.3f".format(wantedSpeed)})")

            try {
                when {
                    wantedSpeed in MIN_SPEED..MAX_SPEED -> {
                        currentAudios = currentAudios.map { voice.changeSpeed(projectId, it, wantedSpeed, log) }
                        speed = wantedSpeed
                        notes += "Adjusted voice speed to ${"%.2f".format(wantedSpeed)}x"
                    }
                    total > range.last && wantedSpeed <= MAX_SPEED + 0.08f -> {
                        if (abs(speed - MAX_SPEED) > 0.003f) {
                            currentAudios = currentAudios.map { voice.changeSpeed(projectId, it, MAX_SPEED, log) }
                            speed = MAX_SPEED
                        }
                        if (measure() > range.last) {
                            currentAudios = currentAudios.map { voice.tightenPauses(projectId, it, TIGHT_PAUSE_MS, log) }
                            notes += "Sped voice up to ${"%.2f".format(MAX_SPEED)}x and tightened long pauses"
                        } else {
                            notes += "Sped voice up to ${"%.2f".format(MAX_SPEED)}x"
                        }
                    }
                    else -> {
                        val (s, a) = regenerate(projectId, request, currentScript, currentAudios, total, centre, speed, log)
                        currentScript = s
                        currentAudios = a
                        notes += if (total > range.last) "Rewrote scenes shorter to fit the length" else "Rewrote scenes longer to fit the length"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("Length adjustment attempt $attempts failed", e)
                notes += "Length adjustment attempt $attempts failed: ${e.message?.take(120)}"
            }
            onProgress(attempts.toFloat() / (MAX_ATTEMPTS + 1))
        }

        // Shorts must end by 59 s, whatever it takes (never dropping the hook or CTA).
        if (request.isShorts && measure() > range.last) {
            val before = currentScript.scenes.size
            val fitted = forceFitShorts(currentScript, range.last, log) { s ->
                timelineBuilder.build(request, s, alignToScript(s, currentAudios)).totalMs
            }
            val keptNumbers = fitted.scenes.map { it.sceneNumber }
            val keptAudios = alignToScript(fitted, currentAudios)
            // Renumber script and audio together so file mapping stays valid.
            val renumbered = ScriptRepair.renumber(fitted.scenes)
            val numberMap = keptNumbers.zip(renumbered.map { it.sceneNumber }).toMap()
            currentScript = fitted.copy(scenes = renumbered)
            currentAudios = keptAudios.map { it.copy(sceneNumber = numberMap[it.sceneNumber] ?: it.sceneNumber) }
            notes += "Dropped ${before - fitted.scenes.size} scene(s) to stay under 60 seconds"
            if (measure() > range.last && speed < 1.15f) {
                currentAudios = currentAudios.map { voice.changeSpeed(projectId, it, 1.15f, log) }
                speed = 1.15f
                notes += "Sped voice up to 1.15x as a last resort"
            }
        }

        val finalMs = measure()
        val within = finalMs in range
        if (!within) notes += "Final length ${sec(finalMs)} is outside ${sec(range.first)}-${sec(range.last)}"
        log.i("Final voice length ${sec(finalMs)} (${if (within) "within limits" else "outside limits"})")
        onProgress(1f)
        return LengthFitResult(
            script = currentScript,
            audios = currentAudios,
            speed = speed,
            attempts = attempts,
            finalDurationMs = finalMs,
            withinLimits = within,
            notes = notes,
        )
    }

    /** Rewrites the worst scenes to about the right word count and re-voices them. */
    private suspend fun regenerate(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        audios: List<SceneAudio>,
        total: Long,
        target: Long,
        speed: Float,
        log: StepLogger,
    ): Pair<Script, List<SceneAudio>> {
        val tooLong = total > target
        var needMs = abs(total - target)
        val audioByScene = audios.associateBy { it.sceneNumber }
        val candidates = script.scenes
            .filter { it.role == SceneRole.MAIN || it.role == SceneRole.INTRO }
            .filter { audioByScene[it.sceneNumber] != null }
            .sortedWith(
                compareBy<com.autotube.ai.core.model.Scene> { if (it.role == SceneRole.MAIN) 0 else 1 }
                    .thenByDescending { audioByScene[it.sceneNumber]?.durationMs ?: 0L },
            )

        // A big miss is easier to fix by resizing the whole script and re-voicing it.
        if (candidates.isEmpty() || needMs > total * WHOLE_SCRIPT_THRESHOLD) {
            val resized = if (tooLong) scriptWriter.shorten(request, script, target, log) else scriptWriter.expand(request, script, target, log)
            val newAudios = voice.synthesizeAll(projectId, request, resized, speed, log)
            return resized to newAudios
        }

        var newScript = script
        val newAudios = audios.toMutableList()
        for (scene in candidates) {
            if (needMs <= 0) break
            val audio = audioByScene[scene.sceneNumber] ?: continue
            val maxChange = (audio.durationMs * if (tooLong) 0.4 else 0.5).roundToLong()
            val change = minOf(needMs, maxChange)
            val newDuration = if (tooLong) audio.durationMs - change else audio.durationMs + change
            val words = max(4, (scene.wordCount * newDuration.toDouble() / audio.durationMs.coerceAtLeast(1)).roundToInt())
            val rewritten = scriptWriter.rewriteScene(
                request, newScript, scene.sceneNumber,
                "Make it ~$words words (currently ${scene.wordCount}); keep the meaning, tone and continuity.", log,
            ).copy(sceneNumber = scene.sceneNumber, role = scene.role)
            val revoiced = voice.synthesizeScene(projectId, request, rewritten, speed, log)
            newScript = newScript.copy(scenes = newScript.scenes.map { if (it.sceneNumber == scene.sceneNumber) rewritten else it })
            val idx = newAudios.indexOfFirst { it.sceneNumber == scene.sceneNumber }
            if (idx >= 0) newAudios[idx] = revoiced else newAudios += revoiced
            needMs -= abs(audio.durationMs - revoiced.durationMs)
        }
        return newScript to newAudios
    }

    /**
     * Drops optional scenes (least intense first), then trailing main scenes, until [measure] fits
     * [maxMs]. Never drops the hook, the CTA or the last remaining main scene.
     */
    private fun forceFitShorts(script: Script, maxMs: Long, log: StepLogger, measure: (Script) -> Long): Script {
        var current = script
        val optional = current.scenes
            .filter { it.priority == ScenePriority.OPTIONAL && it.role != SceneRole.HOOK && it.role != SceneRole.CTA }
            .sortedBy { it.intensity }
        for (s in optional) {
            if (measure(current) <= maxMs) break
            if (current.scenes.count { it.role == SceneRole.MAIN } <= 1 && s.role == SceneRole.MAIN) break
            current = current.copy(scenes = current.scenes.filter { it.sceneNumber != s.sceneNumber })
            log.i("Dropped optional scene ${s.sceneNumber} to keep the Short under 60 seconds")
        }
        while (measure(current) > maxMs && current.scenes.count { it.role == SceneRole.MAIN } > 1) {
            val last = current.scenes.last { it.role == SceneRole.MAIN }
            current = current.copy(scenes = current.scenes.filter { it.sceneNumber != last.sceneNumber })
            log.i("Dropped main scene ${last.sceneNumber} to keep the Short under 60 seconds")
        }
        return current
    }

    /** Audio for exactly the script's scenes, in script order. */
    private fun alignToScript(script: Script, audios: List<SceneAudio>): List<SceneAudio> {
        val byScene = audios.associateBy { it.sceneNumber }
        return script.scenes.mapNotNull { byScene[it.sceneNumber] }
    }

    private fun sec(ms: Long): String = "%.1f s".format(ms / 1000.0)

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MIN_SPEED = 0.9f
        const val MAX_SPEED = 1.1f
        const val TIGHT_PAUSE_MS = 380L
        const val WHOLE_SCRIPT_THRESHOLD = 0.3
    }
}
