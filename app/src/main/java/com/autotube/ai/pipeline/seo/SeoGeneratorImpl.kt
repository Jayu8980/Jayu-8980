package com.autotube.ai.pipeline.seo

import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.SeoPackage
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VoiceTimeline
import com.autotube.ai.domain.LlmProvider
import com.autotube.ai.domain.LlmRequest
import com.autotube.ai.domain.SeoGenerator
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.completeJson
import com.autotube.ai.pipeline.script.ContentText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Title, description (hook paragraph + summary + chapters + CTA + hashtags), exactly 15 tags,
 * 3-5 hashtags and thumbnail text. Chapters are built deterministically from the voice timeline; the
 * LLM writes the copy, and an offline builder produces everything from the script when needed.
 */
@Singleton
class SeoGeneratorImpl @Inject constructor(
    private val llmProvider: LlmProvider,
) : SeoGenerator {

    override suspend fun generate(request: VideoRequest, script: Script, timeline: VoiceTimeline, log: StepLogger): SeoPackage {
        val chapters = if (request.isShorts) emptyList() else buildChapters(request.language, script, timeline)
        if (!request.isShorts && chapters.isEmpty()) log.i("Not enough distinct sections for YouTube chapters; skipping them")

        val client = llmProvider.text()
        var draft: SeoDraft? = null
        if (client != null) {
            try {
                draft = client.completeJson<SeoDraft>(
                    LlmRequest(system = SYSTEM_PROMPT, user = userPrompt(request, script), maxTokens = 4_000, json = true, jsonSchema = null, effort = "medium"),
                )
                log.i("SEO copy written by ${client.displayName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("AI SEO failed; building SEO from the script", e)
            }
        }
        val offline = offlineDraft(request, script)
        val d = draft ?: offline

        val title = cleanTitle(d.title).ifBlank { cleanTitle(offline.title) }
        val hashtags = fixHashtags(d.hashtags + offline.hashtags, request)
        val tags = fixTags(d.tags, offline.tags, request)
        val thumb = ContentText.limitWords(ContentText.sanitizeLine(d.thumbnailText).ifBlank { offline.thumbnailText }, 4)
        val body = sanitizeDescription(d.description).ifBlank { offline.description }
        val description = assembleDescription(body, chapters, ctaLine(script, request), hashtags, request.language)
        log.i("SEO: \"$title\", ${tags.size} tags, ${hashtags.size} hashtags, ${chapters.size} chapters")
        return SeoPackage(
            title = title,
            description = description,
            tags = tags,
            hashtags = hashtags,
            chapters = chapters,
            thumbnailText = thumb,
        )
    }

    // ------------------------------------------------------------------ chapters

    internal fun buildChapters(language: AppLanguage, script: Script, timeline: VoiceTimeline): List<String> {
        val introLabel = if (language == AppLanguage.HINDI) "परिचय" else "Intro"
        val entries = mutableListOf(0L to introLabel)
        var lastSection = ""
        for (scene in script.scenes) {
            if (scene.role == SceneRole.HOOK || scene.role == SceneRole.INTRO) continue
            val section = ContentText.sanitizeLine(scene.sectionTitle)
            if (section.isBlank() || section.equals(lastSection, ignoreCase = true)) continue
            lastSection = section
            val timing = timeline.timingOf(scene.sceneNumber) ?: continue
            // Start the chapter in the gap just before the scene's first word.
            val at = ((timing.audioStartMs - 200).coerceAtLeast(0) / 1000) * 1000
            if (at - entries.last().first < MIN_CHAPTER_MS) continue
            entries += at to ContentText.limitChars(section, 60)
        }
        // The last chapter must also last at least 10 s.
        while (entries.size > 1 && timeline.totalMs - entries.last().first < MIN_CHAPTER_MS) entries.removeAt(entries.size - 1)
        if (entries.size < 3) return emptyList()
        return entries.map { (ms, label) -> "${formatTime(ms)} $label" }
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    // ------------------------------------------------------------------ assembling

    private fun assembleDescription(body: String, chapters: List<String>, cta: String, hashtags: List<String>, language: AppLanguage): String {
        val sb = StringBuilder(body.trim())
        if (chapters.isNotEmpty()) {
            sb.append("\n\n").append(if (language == AppLanguage.HINDI) "चैप्टर:" else "Chapters:").append('\n')
            sb.append(chapters.joinToString("\n"))
        }
        if (cta.isNotBlank() && !body.contains(cta)) sb.append("\n\n").append(cta)
        if (hashtags.isNotEmpty()) sb.append("\n\n").append(hashtags.joinToString(" "))
        return ContentText.limitChars(sb.toString(), MAX_DESCRIPTION)
    }

    private fun ctaLine(script: Script, request: VideoRequest): String {
        val cta = script.scenes.lastOrNull { it.role == SceneRole.CTA }?.narrationText.orEmpty()
        return ContentText.sentences(cta).firstOrNull()
            ?: if (request.language == AppLanguage.HINDI) "ऐसे और वीडियो के लिए चैनल को सब्सक्राइब करें।" else "Subscribe for more videos like this."
    }

    private fun cleanTitle(raw: String): String =
        ContentText.limitChars(ContentText.sanitizeLine(raw).replace("<", "").replace(">", ""), MAX_TITLE)

    private fun sanitizeDescription(raw: String): String {
        // Chapters and hashtags are added deterministically; drop any the model wrote itself.
        val lines = raw.replace("<", "").replace(">", "").lines()
            .filterNot { TIMESTAMP_LINE.matches(it.trim()) }
            .filterNot { line -> line.trim().split(Regex("\\s+")).let { parts -> parts.isNotEmpty() && parts.all { it.startsWith("#") } } && line.isNotBlank() }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun fixHashtags(raw: List<String>, request: VideoRequest): List<String> {
        val cleaned = raw.map { tag ->
            "#" + tag.trim().trimStart('#').filter { it.isLetterOrDigit() || ContentText.isCombiningMark(it) || it == '_' }
        }.filter { it.length > 2 }.distinctBy { it.lowercase() }.toMutableList()
        if (request.isShorts) {
            cleaned.removeAll { it.equals("#shorts", ignoreCase = true) }
            cleaned.add(0, "#Shorts")
        }
        return cleaned.take(5)
    }

    private fun fixTags(primary: List<String>, fallback: List<String>, request: VideoRequest): List<String> {
        fun clean(t: String) = t.replace("#", "").replace(",", " ").replace("<", "").replace(">", "")
            .replace(Regex("\\s+"), " ").trim()

        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun cost(list: List<String>) = list.sumOf { it.length + if (it.contains(' ')) 2 else 0 } + (list.size - 1).coerceAtLeast(0)
        fun tryAdd(raw: String) {
            if (result.size >= TAG_COUNT) return
            val t = clean(raw)
            if (t.length < 2 || t.length > MAX_TAG_CHARS) return
            if (!seen.add(t.lowercase())) return
            if (cost(result + t) >= MAX_TAGS_TOTAL) return
            result += t
        }
        primary.forEach(::tryAdd)
        if (request.language == AppLanguage.HINDI) {
            // Hindi videos get mixed Hindi + English tags.
            if (result.none { ContentText.isAscii(it) }) listOf("hindi", "in hindi").forEach(::tryAdd)
        }
        fallback.forEach(::tryAdd)
        GENERIC_TAGS.forEach(::tryAdd)
        return result
    }

    // ------------------------------------------------------------------ offline copy

    private fun offlineDraft(request: VideoRequest, script: Script): SeoDraft {
        val hindi = request.language == AppLanguage.HINDI
        val niche = ContentText.sanitizeLine(request.niche)
        val hook = script.scenes.firstOrNull { it.role == SceneRole.HOOK }?.narrationText.orEmpty()
        val sections = script.scenes.filter { it.role == SceneRole.MAIN }
            .map { it.sectionTitle.ifBlank { ContentText.sentences(it.narrationText).firstOrNull().orEmpty() } }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
        val summary = buildString {
            append(if (hindi) "इस वीडियो में:" else "In this video:")
            sections.forEach { append("\n- ").append(ContentText.limitChars(it, 90)) }
        }
        val description = listOf(hook, summary).filter { it.isNotBlank() }.joinToString("\n\n")

        val nicheTag = niche.lowercase()
        val tags = mutableListOf<String>()
        tags += nicheTag
        tags += ContentText.limitChars(script.title.lowercase(), MAX_TAG_CHARS)
        ContentText.contentWords(niche + " " + script.title).forEach { tags += it.lowercase() }
        sections.forEach { if (it.length <= MAX_TAG_CHARS) tags += it.lowercase() }
        if (hindi) {
            tags += listOf("$nicheTag hindi", "$nicheTag in hindi", "hindi facts", "हिंदी", "रोचक तथ्य", "जानकारी")
        } else {
            tags += listOf("$nicheTag facts", "$nicheTag explained", "about $nicheTag", "facts", "explained")
        }
        if (request.isShorts) tags += "shorts"

        val nicheHash = niche.split(Regex("\\s+")).joinToString("") { w -> w.replaceFirstChar { it.uppercase() } }
        val hashtags = buildList {
            if (nicheHash.isNotBlank()) add("#$nicheHash")
            add(if (hindi) "#हिंदी" else "#Facts")
            add(if (hindi) "#Facts" else "#Explained")
        }
        val thumb = script.scenes.firstOrNull { it.role == SceneRole.HOOK }?.onScreenText?.takeIf { it.isNotBlank() }
            ?: ContentText.limitWords(script.title, 3)
        return SeoDraft(
            title = script.title,
            description = description,
            tags = tags,
            hashtags = hashtags,
            thumbnailText = ContentText.limitWords(thumb, 4),
        )
    }

    private fun userPrompt(request: VideoRequest, script: Script): String = buildString {
        val hindi = request.language == AppLanguage.HINDI
        appendLine("Video format: ${if (request.isShorts) "YouTube Shorts" else "long-form YouTube video"}")
        appendLine("Niche: ${request.niche}")
        appendLine("Script title: ${script.title}")
        appendLine("Script (role | section | narration):")
        script.scenes.forEach { s ->
            appendLine("- ${s.role.name.lowercase()} | ${s.sectionTitle} | ${ContentText.limitChars(s.narrationText, 400)}")
        }
        appendLine()
        if (hindi) appendLine("Write title, description and thumbnail_text in Hindi (Devanagari). Tags: mix Hindi and English (about half each).")
        else appendLine("Write everything in English.")
        appendLine("Rules:")
        appendLine("- title: max 70 characters, curiosity-driven, honest (no clickbait lies), main keyword near the start.")
        appendLine("- description: a 1-2 sentence hook paragraph, then a short summary of what the viewer learns, then a call to action. Do NOT include timestamps, chapters or hashtags (they are added automatically). No angle brackets.")
        appendLine("- tags: exactly 15 search phrases, each at most 30 characters, all together under 450 characters.")
        appendLine("- hashtags: 3-5 hashtags without spaces${if (request.isShorts) ", including #Shorts" else ""}.")
        appendLine("- thumbnail_text: 2-4 punchy words.")
        append("Return ONLY JSON: {\"title\": \"...\", \"description\": \"...\", \"tags\": [\"...\"], \"hashtags\": [\"#...\"], \"thumbnail_text\": \"...\"}")
    }

    private companion object {
        const val MAX_TITLE = 70
        const val MAX_DESCRIPTION = 4_900
        const val TAG_COUNT = 15
        const val MAX_TAG_CHARS = 30
        const val MAX_TAGS_TOTAL = 480
        const val MIN_CHAPTER_MS = 10_000L
        val TIMESTAMP_LINE = Regex("^\\(?\\d{1,2}:\\d{2}(?::\\d{2})?\\)?\\s*[-–—:]?\\s*.*$")
        val GENERIC_TAGS = listOf("facts", "explained", "did you know", "learn", "education", "documentary", "interesting", "knowledge", "top facts", "amazing facts", "fun facts", "science", "how it works", "tips")
        val SYSTEM_PROMPT = """
            You are a YouTube SEO expert. You write titles and descriptions that earn clicks honestly and rank
            in search, and you pick tags real viewers search for. Reply with JSON only.
        """.trimIndent()
    }
}

@Serializable
internal data class SeoDraft(
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    @SerialName("thumbnail_text") val thumbnailText: String = "",
)
