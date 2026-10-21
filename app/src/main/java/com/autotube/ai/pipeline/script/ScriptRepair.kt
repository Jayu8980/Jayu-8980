package com.autotube.ai.pipeline.script

import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.ScenePriority
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.VideoRequest

/**
 * Validates and repairs scripts (from the LLM or after edits): clean narration, exactly one hook
 * first and one CTA last, clamped intensity, sensible keywords, scenes renumbered 1..n.
 */
internal object ScriptRepair {

    private const val HOOK_MAX_WORDS = 25
    private val GENERIC_KEYWORDS = listOf("cinematic b-roll", "abstract background", "people walking city")

    fun repair(script: Script, request: VideoRequest, subject: String): Script {
        val language = request.language
        var scenes = script.scenes
            .map { cleanScene(it, language, subject) }
            .filter { it.narrationText.isNotBlank() }
            .toMutableList()

        // Exactly one hook, first.
        val hookIndex = scenes.indexOfFirst { it.role == SceneRole.HOOK }
        if (hookIndex > 0) scenes.add(0, scenes.removeAt(hookIndex))
        if (hookIndex < 0) {
            val first = scenes.firstOrNull()
            if (first != null && first.role != SceneRole.CTA && first.wordCount <= HOOK_MAX_WORDS && scenes.size > 2) {
                scenes[0] = first.copy(role = SceneRole.HOOK)
            } else {
                scenes.add(0, OfflineScriptTemplates.hookScene(request, subject, script.title.hashCode()))
            }
        }
        scenes = scenes.mapIndexed { i, s ->
            if (i > 0 && s.role == SceneRole.HOOK) s.copy(role = if (i == 1) SceneRole.INTRO else SceneRole.MAIN) else s
        }.toMutableList()

        // Exactly one CTA, last.
        val ctaIndex = scenes.indexOfLast { it.role == SceneRole.CTA }
        if (ctaIndex in 1 until scenes.size - 1) scenes.add(scenes.removeAt(ctaIndex))
        if (ctaIndex <= 0) scenes.add(OfflineScriptTemplates.ctaScene(request, subject, script.title.hashCode()))
        scenes = scenes.mapIndexed { i, s ->
            if (i < scenes.size - 1 && s.role == SceneRole.CTA) s.copy(role = SceneRole.MAIN) else s
        }.toMutableList()

        // There must be something between the hook and the CTA.
        if (scenes.size < 3) {
            scenes.add(
                scenes.size - 1,
                OfflineScriptTemplates.extraMainScene(request, script.copy(scenes = scenes), subject, 1, 25),
            )
        }

        val hook = scenes.first()
        scenes[0] = hook.copy(
            narrationText = trimHook(hook.narrationText, language),
            intensity = hook.intensity.coerceAtLeast(7),
            priority = ScenePriority.ESSENTIAL,
        )
        scenes[scenes.size - 1] = scenes.last().copy(priority = ScenePriority.ESSENTIAL)

        return script.copy(
            title = ContentText.limitChars(ContentText.sanitizeLine(script.title), 100)
                .ifBlank { OfflineScriptTemplates.defaultTitle(subject, language) },
            language = language,
            scenes = renumber(scenes),
        )
    }

    fun renumber(scenes: List<Scene>): List<Scene> = scenes.mapIndexed { i, s -> s.copy(sceneNumber = i + 1) }

    fun cleanScene(scene: Scene, language: AppLanguage, subject: String): Scene {
        val narration = ContentText.sanitizeNarration(scene.narrationText)
        val visual = ContentText.sanitizeLine(scene.visualDescription)
        return scene.copy(
            narrationText = if (narration.isBlank()) "" else ContentText.ensureTerminalPunctuation(narration, language),
            visualDescription = visual,
            searchKeywords = fixKeywords(scene.searchKeywords, visual, scene.sectionTitle, subject),
            onScreenText = ContentText.limitWords(ContentText.sanitizeLine(scene.onScreenText), 6),
            intensity = scene.intensity.coerceIn(1, 10),
            priority = if (scene.role == SceneRole.HOOK || scene.role == SceneRole.CTA) ScenePriority.ESSENTIAL else scene.priority,
            keyWords = fixKeyWords(scene.keyWords, narration, subject),
            sectionTitle = ContentText.limitChars(ContentText.sanitizeLine(scene.sectionTitle), 60),
        )
    }

    private fun trimHook(text: String, language: AppLanguage): String {
        if (ContentText.wordCount(text) <= HOOK_MAX_WORDS) return text
        val kept = StringBuilder()
        for (sentence in ContentText.sentences(text)) {
            val candidate = if (kept.isEmpty()) sentence else "$kept $sentence"
            if (ContentText.wordCount(candidate) > HOOK_MAX_WORDS) break
            kept.setLength(0)
            kept.append(candidate)
        }
        if (kept.isNotEmpty()) return kept.toString()
        return ContentText.ensureTerminalPunctuation(ContentText.limitWords(text, HOOK_MAX_WORDS), language)
    }

    private fun fixKeywords(raw: List<String>, visual: String, section: String, subject: String): List<String> {
        val cleaned = raw.map { ContentText.sanitizeLine(it).lowercase() }
            .map { ContentText.limitWords(it, 5) }
            .filter { it.length >= 3 }
            .distinct()
            .toMutableList()
        if (cleaned.size < 3) {
            val englishSubject = OfflineScriptTemplates.englishSubject(subject)
            val nouns = ContentText.contentWords(visual)
                .filter { ContentText.isAscii(it) }
                .map { it.lowercase() }
            // Pair adjacent visual nouns into short concrete queries, then single nouns.
            nouns.windowed(2, 2, partialWindows = false).forEach { pair ->
                if (cleaned.size < 5) cleaned += pair.joinToString(" ")
            }
            nouns.forEach { if (cleaned.size < 4 && it !in cleaned) cleaned += it }
            if (englishSubject.isNotBlank() && cleaned.size < 5) cleaned += englishSubject
            if (ContentText.isAscii(section) && section.isNotBlank() && cleaned.size < 3) cleaned += section.lowercase()
            GENERIC_KEYWORDS.forEach { if (cleaned.size < 3) cleaned += it }
        }
        return cleaned.distinct().take(5)
    }

    private fun fixKeyWords(raw: List<String>, narration: String, subject: String): List<String> {
        val tokens = ContentText.words(narration).map(ContentText::normalizeToken).toSet()
        val kept = raw.map { ContentText.sanitizeLine(it) }
            .filter { it.isNotBlank() && ContentText.wordCount(it) <= 3 }
            .filter { kw -> ContentText.words(kw).all { ContentText.normalizeToken(it) in tokens } }
            .distinct()
            .take(3)
        return kept.ifEmpty { OfflineScriptTemplates.pickKeyWords(narration, subject) }
    }
}
