package com.autotube.ai.pipeline.editor

import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.CaptionTrack
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.EditFix
import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.VideoFormat
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.core.model.VisualSource
import com.autotube.ai.core.model.VoiceTimeline
import com.autotube.ai.domain.EditorBrain
import com.autotube.ai.domain.LlmClient
import com.autotube.ai.domain.LlmProvider
import com.autotube.ai.domain.LlmRequest
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.completeJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The AI editor (spec 7A/7C/7D). Picks the edit style, asks the LLM (acting as a professional
 * YouTube editor) for a full EDL, falls back to [HeuristicEditPlanner] without an LLM or on any
 * failure, and always runs [EdlNormalizer] so every editing rule holds. [applyFixes] applies QC
 * fixes deterministically through [EditFixer] and normalizes again.
 */
@Singleton
class EditorBrainImpl @Inject constructor(
    private val llmProvider: LlmProvider,
    private val settingsRepository: SettingsRepository,
) : EditorBrain {

    /** Word timings of the last plan, reused by [applyFixes] (which receives no audio/captions). */
    @Volatile
    private var timingCache: Pair<Int, TimingData>? = null

    override suspend fun plan(
        request: VideoRequest,
        script: Script,
        timeline: VoiceTimeline,
        audios: List<SceneAudio>,
        captions: CaptionTrack,
        visuals: List<VisualAsset>,
        log: StepLogger,
    ): EditDecisionList {
        val settings = settingsRepository.current()
        val ctx = withContext(Dispatchers.Default) {
            val timing = WordTimelineBuilder.fromAudio(script, timeline, audios, captions)
            val key = timingKey(script, timeline)
            timingCache = key to timing
            lastRequest = key to request
            EditContext.create(request, script, timeline, visuals, settings, timing)
        }
        log.i("Editing style: ${ctx.style.label} (${ctx.tracks.size} scenes, ${visuals.size} visuals, ${ctx.totalMs / 1000} s)")

        val llmDraft = llmPlan(ctx, log)
        return withContext(Dispatchers.Default) {
            val edl = if (llmDraft != null) {
                try {
                    EdlNormalizer(ctx).normalize(llmDraft.first, "AI editor (${llmDraft.second})")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w("The AI edit plan could not be repaired; using the built-in editor.", e)
                    heuristic(ctx)
                }
            } else {
                heuristic(ctx)
            }
            log.i(edl.notes)
            edl
        }
    }

    override fun applyFixes(
        edl: EditDecisionList,
        fixes: List<EditFix>,
        script: Script,
        visuals: List<VisualAsset>,
        timeline: VoiceTimeline,
    ): EditDecisionList {
        if (fixes.isEmpty() || visuals.isEmpty() || timeline.scenes.isEmpty()) return edl
        val key = timingKey(script, timeline)
        val timing = timingCache?.takeIf { it.first == key }?.second ?: WordTimelineBuilder.estimate(script, timeline)
        val settings = settingsRepository.current()
        val request = lastRequest?.takeIf { it.first == key }?.second ?: VideoRequest(
            niche = script.title,
            format = inferFormat(timeline, visuals, edl),
            targetDurationSec = (timeline.totalMs / 1000L).toInt(),
            language = script.language,
        )
        val pacing = PacingProfile.of(edl.style, request.format)
        val ctx = EditContext.create(request, script, timeline, visuals, settings, timing, pacing)
        val draft = EditFixer(ctx).apply(edl, fixes).copy(notes = "")
        val names = fixes.joinToString(", ") { it.action.name.lowercase() }
        val origin = edl.notes.substringBefore('.').ifBlank { "Editor" }
        return EdlNormalizer(ctx).normalize(draft, "$origin + QC fixes ($names)")
    }

    // ------------------------------------------------------------------ LLM

    /** Returns the draft and the model name, or null to use the built-in editor. */
    private suspend fun llmPlan(ctx: EditContext, log: StepLogger): Pair<EditDecisionList, String>? {
        val client: LlmClient = (try {
            llmProvider.text()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("No AI model available for editing: ${e.message?.take(160)}")
            null
        }) ?: run {
            log.i("No AI model available: using the built-in editor.")
            return null
        }
        val user = EditorPrompt.user(ctx)
        val onDevice = client.displayName.contains("device", ignoreCase = true) ||
            client.displayName.contains("gemma", ignoreCase = true)
        if (onDevice && user.length > ON_DEVICE_MAX_PROMPT_CHARS) {
            log.i("The video is too long for the on-device model's editor prompt: using the built-in editor.")
            return null
        }
        log.i("Asking ${client.displayName} to plan the edit…")
        return try {
            val dto = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                client.completeJson<LlmEdlDto>(
                    LlmRequest(
                        system = EditorPrompt.SYSTEM,
                        user = user,
                        maxTokens = 16_000,
                        json = true,
                        effort = "medium",
                    ),
                )
            }
            if (dto == null) {
                log.w("The AI editor took too long: using the built-in editor.")
                null
            } else {
                val draft = EditorPrompt.toDraft(dto, ctx)
                if (draft == null) {
                    log.w("The AI edit plan was incomplete (missing shots or unknown visuals): using the built-in editor.")
                    null
                } else {
                    log.i("AI editor returned ${draft.shots.size} shots and ${draft.textPops.size} text pops.")
                    draft to client.displayName
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("AI edit plan failed (${e.message?.take(160)}): using the built-in editor.")
            null
        }
    }

    private fun heuristic(ctx: EditContext): EditDecisionList = try {
        EdlNormalizer(ctx).normalize(HeuristicEditPlanner.plan(ctx), "Built-in editor")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw PipelineException(
            userTitle = "Editing failed",
            userMessage = "The edit plan could not be built from these visuals. Try \"Finding visuals\" again.",
            retryable = true,
            step = PipelineStep.EDIT_PLAN,
            cause = e,
        )
    }

    /**
     * The video format is not part of applyFixes' inputs: use the format of the last plan for the same
     * script (same request, so the same deterministic seed), else the orientation of on-device art (always frame-sized), else the duration.
     */
    private fun inferFormat(timeline: VoiceTimeline, visuals: List<VisualAsset>, edl: EditDecisionList): VideoFormat {
        visuals.firstOrNull { it.source == VisualSource.LOCAL_GENERATED && it.width > 0 && it.height > 0 }?.let {
            return if (it.height > it.width) VideoFormat.SHORTS else VideoFormat.LONG_FORM
        }
        return if (edl.style == EditStyle.FAST_SHORTS || timeline.totalMs <= SHORTS_MAX_MS) VideoFormat.SHORTS else VideoFormat.LONG_FORM
    }

    @Volatile
    private var lastRequest: Pair<Int, VideoRequest>? = null

    private fun timingKey(script: Script, timeline: VoiceTimeline): Int =
        31 * script.scenes.joinToString("|") { it.sceneNumber.toString() + ":" + it.narrationText }.hashCode() + timeline.hashCode()

    private companion object {
        const val LLM_TIMEOUT_MS = 240_000L
        const val ON_DEVICE_MAX_PROMPT_CHARS = 6_000
        const val SHORTS_MAX_MS = 61_000L
    }
}
