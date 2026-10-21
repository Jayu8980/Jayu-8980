package com.autotube.ai.pipeline.script

import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.ScenePriority
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.TopicCandidate
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.domain.LlmClient
import com.autotube.ai.domain.LlmProvider
import com.autotube.ai.domain.LlmRequest
import com.autotube.ai.domain.ScriptWriter
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.completeJson
import com.autotube.ai.pipeline.length.DurationModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes the video script. With an LLM it acts as a top YouTube scriptwriter (JSON scenes); every
 * answer is validated and repaired. Without an LLM (or when the call fails) it uses
 * [OfflineScriptTemplates], so the pipeline always gets a usable script.
 */
@Singleton
class ScriptWriterImpl @Inject constructor(
    private val llmProvider: LlmProvider,
) : ScriptWriter {

    override suspend fun write(request: VideoRequest, topic: TopicCandidate, log: StepLogger): Script {
        val subject = OfflineScriptTemplates.subjectOf(request, topic)
        val client = llmProvider.text()
        if (client != null) {
            try {
                val sceneCount = DurationModel.recommendedSceneCount(request)
                val words = DurationModel.targetWords(request, DurationModel.centreMs(request), sceneCount)
                log.i("Writing script with ${client.displayName}: ~$words words, ~$sceneCount scenes")
                val dto = client.completeJson<ScriptDto>(
                    LlmRequest(system = systemPrompt(request), user = writePrompt(request, topic, words, sceneCount), json = true, jsonSchema = null, effort = "high"),
                )
                val script = ScriptRepair.repair(dto.toScript(request, topic.title), request, subject)
                if (script.scenes.size >= 3 && script.totalWords >= words / 3) {
                    log.i("Script ready: ${script.scenes.size} scenes, ${script.totalWords} words")
                    return script
                }
                log.w("AI script was too thin (${script.totalWords} words); using the offline writer instead")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("AI script writing failed; using the offline writer", e)
            }
        } else {
            log.i("No AI model available; writing the script from offline templates")
        }
        val offline = ScriptRepair.repair(OfflineScriptTemplates.build(request, topic), request, subject)
        log.i("Offline script ready: ${offline.scenes.size} scenes, ${offline.totalWords} words")
        return offline
    }

    override suspend fun rewriteScene(request: VideoRequest, script: Script, sceneNumber: Int, instruction: String, log: StepLogger): Scene {
        val original = script.scenes.firstOrNull { it.sceneNumber == sceneNumber }
            ?: throw PipelineException(
                userTitle = "Scene not found",
                userMessage = "Scene $sceneNumber is not part of this script any more.",
                retryable = false,
                step = PipelineStep.SCRIPT,
            )
        val subject = OfflineScriptTemplates.subjectOf(request, null)
        val targetWords = WORDS_INSTRUCTION.find(instruction)?.groupValues?.get(1)?.toIntOrNull()
        val client = llmProvider.text()
        if (client != null) {
            try {
                val dto = client.completeJson<SceneDto>(
                    LlmRequest(system = systemPrompt(request), user = rewritePrompt(request, script, original, instruction, targetWords), json = true, jsonSchema = null, effort = "medium"),
                )
                val candidate = dto.toScene(original.sceneNumber).copy(role = original.role, sceneNumber = original.sceneNumber)
                val merged = candidate.copy(
                    visualDescription = candidate.visualDescription.ifBlank { original.visualDescription },
                    searchKeywords = candidate.searchKeywords.ifEmpty { original.searchKeywords },
                    sectionTitle = candidate.sectionTitle.ifBlank { original.sectionTitle },
                    priority = if (original.role == SceneRole.HOOK || original.role == SceneRole.CTA) ScenePriority.ESSENTIAL else candidate.priority,
                )
                val cleaned = ScriptRepair.cleanScene(merged, request.language, subject)
                if (cleaned.narrationText.isNotBlank()) {
                    log.i("Scene $sceneNumber rewritten (${original.wordCount} -> ${cleaned.wordCount} words)")
                    return cleaned
                }
                log.w("AI rewrite of scene $sceneNumber came back empty; using the offline rewrite")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("AI rewrite of scene $sceneNumber failed; using the offline rewrite", e)
            }
        }
        val offline = OfflineScriptTemplates.rewriteOffline(original, request.language, subject, script.title, targetWords, instruction.hashCode())
        return ScriptRepair.cleanScene(offline, request.language, subject)
    }

    override suspend fun shorten(request: VideoRequest, script: Script, targetMs: Long, log: StepLogger): Script {
        val isShorts = request.isShorts
        if (DurationModel.estimateMs(script, isShorts) <= targetMs) return script
        val subject = OfflineScriptTemplates.subjectOf(request, null)

        // 1) Optional scenes first (least intense first), only if that alone reaches the target.
        val optional = script.scenes
            .filter { it.priority == ScenePriority.OPTIONAL && it.role != SceneRole.HOOK && it.role != SceneRole.CTA }
            .sortedWith(compareBy<Scene> { it.intensity }.thenByDescending { it.sceneNumber })
        var trial = script
        for (scene in optional) {
            if (trial.scenes.count { it.role == SceneRole.MAIN } <= 1 && scene.role == SceneRole.MAIN) break
            trial = trial.copy(scenes = trial.scenes.filter { it.sceneNumber != scene.sceneNumber })
            if (DurationModel.estimateMs(trial, isShorts) <= targetMs) {
                log.i("Shortened by dropping ${script.scenes.size - trial.scenes.size} optional scene(s)")
                return trial.copy(scenes = ScriptRepair.renumber(trial.scenes))
            }
        }

        // 2) Ask the LLM to tighten the whole script.
        val targetWords = scaledWords(script, targetMs, isShorts)
        llmRewriteWhole(request, script, targetWords, shorten = true, subject = subject, log = log)?.let { candidate ->
            if (DurationModel.estimateMs(candidate, isShorts) < DurationModel.estimateMs(script, isShorts)) return candidate
        }

        // 3) Offline: drop optional scenes, trim long scenes, then drop trailing main points.
        return offlineShorten(request, script, targetMs, subject, log)
    }

    override suspend fun expand(request: VideoRequest, script: Script, targetMs: Long, log: StepLogger): Script {
        val isShorts = request.isShorts
        if (DurationModel.estimateMs(script, isShorts) >= targetMs) return script
        val subject = OfflineScriptTemplates.subjectOf(request, null)
        val targetWords = scaledWords(script, targetMs, isShorts)
        llmRewriteWhole(request, script, targetWords, shorten = false, subject = subject, log = log)?.let { candidate ->
            if (DurationModel.estimateMs(candidate, isShorts) > DurationModel.estimateMs(script, isShorts)) return candidate
        }
        return offlineExpand(request, script, targetMs, subject, log)
    }

    // ------------------------------------------------------------------ LLM helpers

    private suspend fun llmRewriteWhole(
        request: VideoRequest,
        script: Script,
        targetWords: Int,
        shorten: Boolean,
        subject: String,
        log: StepLogger,
    ): Script? {
        val client: LlmClient = llmProvider.text() ?: return null
        return try {
            val dto = client.completeJson<ScriptDto>(
                LlmRequest(system = systemPrompt(request), user = resizePrompt(request, script, targetWords, shorten), json = true, jsonSchema = null, effort = "medium"),
            )
            var candidate = ScriptRepair.repair(dto.toScript(request, script.title), request, subject)
            // Never lose the hook or the CTA the viewer already heard in earlier versions.
            val origHook = script.scenes.firstOrNull { it.role == SceneRole.HOOK }
            val origCta = script.scenes.lastOrNull { it.role == SceneRole.CTA }
            if (origHook != null && candidate.scenes.first().wordCount < 3) {
                candidate = candidate.copy(scenes = listOf(origHook) + candidate.scenes.drop(1))
            }
            if (origCta != null && candidate.scenes.last().wordCount < 3) {
                candidate = candidate.copy(scenes = candidate.scenes.dropLast(1) + origCta)
            }
            candidate = candidate.copy(scenes = ScriptRepair.renumber(candidate.scenes), style = script.style)
            log.i("${if (shorten) "Shortened" else "Expanded"} script with ${client.displayName}: ${script.totalWords} -> ${candidate.totalWords} words")
            candidate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("AI ${if (shorten) "shortening" else "expansion"} failed; using the offline method", e)
            null
        }
    }

    private fun scaledWords(script: Script, targetMs: Long, isShorts: Boolean): Int {
        val est = DurationModel.estimateMs(script, isShorts).coerceAtLeast(1)
        val silence = est - script.scenes.sumOf { DurationModel.speechMs(it.wordCount, script.language, isShorts) }
        val speechTarget = (targetMs - silence).coerceAtLeast(2_000)
        return (speechTarget / 60_000.0 * DurationModel.wordsPerMinute(script.language, isShorts)).roundToInt().coerceAtLeast(8)
    }

    // ------------------------------------------------------------------ offline resizing

    private fun offlineShorten(request: VideoRequest, script: Script, targetMs: Long, subject: String, log: StepLogger): Script {
        val isShorts = request.isShorts
        val scenes = script.scenes.toMutableList()
        fun est() = DurationModel.estimateMs(script.copy(scenes = scenes.toList()), isShorts)

        val optionalMains = scenes.filter { it.priority == ScenePriority.OPTIONAL && it.role == SceneRole.MAIN }.sortedBy { it.intensity }
        for (s in optionalMains) {
            if (est() <= targetMs || scenes.count { it.role == SceneRole.MAIN } <= 1) break
            scenes.remove(s)
        }

        // Trim the longest non-hook scenes sentence by sentence.
        var guard = 0
        while (est() > targetMs && guard++ < 200) {
            val idx = scenes.indices
                .filter { scenes[it].role != SceneRole.HOOK && ContentText.sentences(scenes[it].narrationText).size > 1 }
                .maxByOrNull { scenes[it].wordCount } ?: break
            val s = scenes[idx]
            val sentences = ContentText.sentences(s.narrationText)
            val kept = if (s.role == SceneRole.CTA) sentences.take(sentences.size - 1) else sentences.dropLast(1)
            scenes[idx] = s.copy(narrationText = kept.joinToString(" "))
        }
        // Drop trailing main points (never hook/intro/CTA), keeping at least one.
        while (est() > targetMs && scenes.count { it.role == SceneRole.MAIN } > 1) {
            val idx = scenes.indexOfLast { it.role == SceneRole.MAIN }
            scenes.removeAt(idx)
        }
        val result = script.copy(scenes = ScriptRepair.renumber(scenes.map { ScriptRepair.cleanScene(it, request.language, subject) }))
        log.i("Shortened offline: ${script.totalWords} -> ${result.totalWords} words")
        return result
    }

    private fun offlineExpand(request: VideoRequest, script: Script, targetMs: Long, subject: String, log: StepLogger): Script {
        val isShorts = request.isShorts
        var current = script
        val maxScenes = if (isShorts) 8 else 40
        val perScene = max(15, current.scenes.filter { it.role == SceneRole.MAIN }.map { it.wordCount }.average().takeIf { !it.isNaN() }?.roundToInt() ?: 30)
        var salt = 1
        // First lengthen existing main scenes a little, then add new main points before the CTA.
        current = current.copy(
            scenes = current.scenes.map { s ->
                if (s.role == SceneRole.MAIN && DurationModel.estimateMs(current, isShorts) < targetMs) {
                    OfflineScriptTemplates.rewriteOffline(s, request.language, subject, script.title, (s.wordCount * 1.25).roundToInt(), salt++)
                } else s
            },
        )
        while (DurationModel.estimateMs(current, isShorts) < targetMs && current.scenes.size < maxScenes) {
            val extra = OfflineScriptTemplates.extraMainScene(request, current, subject, salt++, perScene)
            val scenes = current.scenes.toMutableList()
            val ctaIdx = scenes.indexOfLast { it.role == SceneRole.CTA }.let { if (it < 0) scenes.size else it }
            scenes.add(ctaIdx, extra)
            current = current.copy(scenes = ScriptRepair.renumber(scenes))
        }
        val result = current.copy(scenes = ScriptRepair.renumber(current.scenes.map { ScriptRepair.cleanScene(it, request.language, subject) }))
        log.i("Expanded offline: ${script.totalWords} -> ${result.totalWords} words")
        return result
    }

    // ------------------------------------------------------------------ prompts

    private fun languageName(language: AppLanguage) = when (language) {
        AppLanguage.ENGLISH -> "English"
        AppLanguage.HINDI -> "Hindi (Devanagari script, natural everyday spoken Hindi; common English loanwords are fine)"
    }

    private fun systemPrompt(request: VideoRequest): String = """
        You are one of the best YouTube scriptwriters in the world. You write voiceover scripts that keep
        viewers watching: a pattern-breaking hook in the first seconds, a fast clear intro, tight sections
        with concrete details, curiosity loops, and a short natural call to action.
        Rules for narration_text:
        - Written in ${languageName(request.language)}, exactly as it should be spoken by a narrator.
        - Natural conversational style with contractions; vary sentence length; no filler.
        - No stage directions, no speaker labels, no brackets, no emojis, no markdown, no hashtags.
        - Never invent fake statistics or quotes; if unsure, phrase it as a general observation.
        Visual fields (visual_description, search_keywords) are ALWAYS in English, even for Hindi scripts.
        search_keywords: 3-5 short concrete stock-footage queries (2-4 words each, things a camera can film).
        Reply with ONLY valid JSON. No prose before or after it.
    """.trimIndent()

    private fun jsonShape(): String = """
        {"title": "...", "style": "documentary" | "fast_shorts" | "calm_educational",
         "scenes": [{"scene_number": 1, "role": "hook" | "intro" | "main" | "cta", "section_title": "...",
           "narration_text": "...", "visual_description": "...", "search_keywords": ["...", "...", "..."],
           "on_screen_text": "max 6 words", "emotion": "suspense|happy|sad|exciting|calm|dramatic|inspiring|funny",
           "intensity": 1-10, "priority": "essential" | "optional", "key_words": ["1-3 words copied from the narration"]}]}
    """.trimIndent()

    private fun writePrompt(request: VideoRequest, topic: TopicCandidate, words: Int, sceneCount: Int): String {
        val format = if (request.isShorts) "YouTube Shorts (vertical, fast paced, under 60 seconds)" else "long-form YouTube video"
        val sceneGuide = if (request.isShorts) "4-8 scenes" else "about $sceneCount scenes (one per 25-40 seconds)"
        return buildString {
            appendLine("Write a script for a $format.")
            appendLine("Niche: ${request.niche}")
            appendLine("Topic: ${topic.title}")
            if (topic.angle.isNotBlank()) appendLine("Angle: ${topic.angle}")
            if (request.notes.isNotBlank()) appendLine("Creator notes: ${request.notes}")
            appendLine("Language of narration: ${languageName(request.language)}")
            appendLine("Target length: ${request.targetDurationSec} seconds = about $words spoken words in total (count carefully).")
            appendLine("Scenes: $sceneGuide.")
            appendLine("Structure:")
            appendLine("1. Exactly one scene with role \"hook\" first: at most 25 words, spoken in the first 5-10 seconds, pattern-breaking (a surprising claim, question or stakes). No greeting, no channel intro.")
            appendLine("2. One \"intro\" scene that sets up what the viewer gets.")
            appendLine("3. Several \"main\" scenes, grouped into sections with a short section_title each; build curiosity and pay it off.")
            appendLine("4. Exactly one \"cta\" scene last (priority essential): short, natural, ask to like/subscribe/comment.")
            appendLine("Mark main scenes that could be cut without breaking the story as priority \"optional\".")
            appendLine("Pick the style that suits it: \"fast_shorts\" for Shorts, otherwise \"documentary\" or \"calm_educational\".")
            appendLine("Vary emotion and intensity across scenes like a real story arc.")
            appendLine("Return JSON in exactly this shape:")
            append(jsonShape())
        }
    }

    private fun rewritePrompt(request: VideoRequest, script: Script, scene: Scene, instruction: String, targetWords: Int?): String = buildString {
        appendLine("Here is the full script for context (keep continuity with the scenes before and after):")
        appendLine(AppJson.encodeToString(ScriptDto.serializer(), ScriptDto.from(script)))
        appendLine()
        appendLine("Rewrite ONLY scene ${scene.sceneNumber} (role \"${scene.role.name.lowercase()}\"). Instruction: $instruction")
        if (targetWords != null) appendLine("The new narration_text must be about $targetWords words.")
        else appendLine("Keep roughly the same length (${scene.wordCount} words).")
        if (scene.role == SceneRole.HOOK) appendLine("It is the hook: at most 25 words, pattern-breaking.")
        appendLine("Narration language: ${languageName(request.language)}.")
        appendLine("Reply with ONE scene object only, same fields as in the script, e.g.:")
        append("{\"scene_number\": ${scene.sceneNumber}, \"role\": \"${scene.role.name.lowercase()}\", \"section_title\": \"...\", \"narration_text\": \"...\", \"visual_description\": \"...\", \"search_keywords\": [\"...\"], \"on_screen_text\": \"...\", \"emotion\": \"...\", \"intensity\": 5, \"priority\": \"essential\", \"key_words\": [\"...\"]}")
    }

    private fun resizePrompt(request: VideoRequest, script: Script, targetWords: Int, shorten: Boolean): String = buildString {
        appendLine("Current script (${script.totalWords} words):")
        appendLine(AppJson.encodeToString(ScriptDto.serializer(), ScriptDto.from(script)))
        appendLine()
        if (shorten) {
            appendLine("Shorten it to about $targetWords spoken words in total.")
            appendLine("Remove scenes marked optional first, then tighten wording. Never remove the hook or the CTA; keep the hook at most 25 words.")
        } else {
            appendLine("Expand it to about $targetWords spoken words in total.")
            appendLine("Add depth: new concrete main scenes (examples, details, a mini story) and richer existing scenes. Keep the hook and the CTA as they are.")
        }
        appendLine("Keep the same language (${languageName(request.language)}), style and story order.")
        appendLine("Return the COMPLETE script as JSON in exactly this shape:")
        append(jsonShape())
    }

    private companion object {
        val WORDS_INSTRUCTION = Regex("~?\\s*(\\d{1,4})\\s*(?:words|शब्द)", RegexOption.IGNORE_CASE)
    }
}

@Serializable
private data class ScriptDto(
    val title: String = "",
    val style: String = "documentary",
    val scenes: List<SceneDto> = emptyList(),
) {
    fun toScript(request: VideoRequest, fallbackTitle: String): Script = Script(
        title = title.ifBlank { fallbackTitle },
        language = request.language,
        scenes = scenes.mapIndexed { i, s -> s.toScene(i + 1) },
        style = when {
            request.isShorts -> EditStyle.FAST_SHORTS
            style.trim().lowercase() == "calm_educational" -> EditStyle.CALM_EDUCATIONAL
            else -> EditStyle.DOCUMENTARY
        },
    )

    companion object {
        fun from(script: Script) = ScriptDto(
            title = script.title,
            style = when (script.style) {
                EditStyle.DOCUMENTARY -> "documentary"
                EditStyle.FAST_SHORTS -> "fast_shorts"
                EditStyle.CALM_EDUCATIONAL -> "calm_educational"
            },
            scenes = script.scenes.map { SceneDto.from(it) },
        )
    }
}

@Serializable
private data class SceneDto(
    @SerialName("scene_number") val sceneNumber: Int = 0,
    val role: String = "main",
    @SerialName("section_title") val sectionTitle: String = "",
    @SerialName("narration_text") val narrationText: String = "",
    @SerialName("visual_description") val visualDescription: String = "",
    @SerialName("search_keywords") val searchKeywords: List<String> = emptyList(),
    @SerialName("on_screen_text") val onScreenText: String = "",
    val emotion: String = "calm",
    val intensity: Int = 5,
    val priority: String = "essential",
    @SerialName("key_words") val keyWords: List<String> = emptyList(),
) {
    fun toScene(number: Int): Scene = Scene(
        sceneNumber = number,
        role = when (role.trim().lowercase()) {
            "hook" -> SceneRole.HOOK
            "intro" -> SceneRole.INTRO
            "cta", "call_to_action", "outro" -> SceneRole.CTA
            else -> SceneRole.MAIN
        },
        narrationText = narrationText,
        visualDescription = visualDescription,
        searchKeywords = searchKeywords,
        onScreenText = onScreenText,
        emotion = Emotion.parse(emotion),
        intensity = intensity,
        priority = if (priority.trim().lowercase() == "optional") ScenePriority.OPTIONAL else ScenePriority.ESSENTIAL,
        keyWords = keyWords,
        sectionTitle = sectionTitle,
    )

    companion object {
        fun from(scene: Scene) = SceneDto(
            sceneNumber = scene.sceneNumber,
            role = scene.role.name.lowercase(),
            sectionTitle = scene.sectionTitle,
            narrationText = scene.narrationText,
            visualDescription = scene.visualDescription,
            searchKeywords = scene.searchKeywords,
            onScreenText = scene.onScreenText,
            emotion = scene.emotion.wireName,
            intensity = scene.intensity,
            priority = scene.priority.name.lowercase(),
            keyWords = scene.keyWords,
        )
    }
}
