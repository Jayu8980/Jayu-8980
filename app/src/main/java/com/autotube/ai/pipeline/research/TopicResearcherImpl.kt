package com.autotube.ai.pipeline.research

import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.extractJson
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.TopicCandidate
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.domain.LlmClient
import com.autotube.ai.domain.LlmProvider
import com.autotube.ai.domain.LlmRequest
import com.autotube.ai.domain.Progress
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.TopicResearcher
import com.autotube.ai.domain.YouTubeDataClient
import com.autotube.ai.domain.YtVideoStat
import com.autotube.ai.pipeline.script.ContentText
import com.autotube.ai.pipeline.script.Seeded
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Topic research: YouTube trends of the last 30 days (views/day, engagement, recency) -> LLM ideas
 * that ride the trend without copying titles. Without a YouTube key the LLM brainstorms; without an
 * LLM a heuristic/template generator produces good angles (English and Hindi). Best first, max 5.
 */
@Singleton
class TopicResearcherImpl @Inject constructor(
    private val youTube: YouTubeDataClient,
    private val llmProvider: LlmProvider,
    private val settingsRepository: SettingsRepository,
) : TopicResearcher {

    private class ScoredVideo(val stat: YtVideoStat, val viewsPerDay: Double, val score: Double)

    override suspend fun research(request: VideoRequest, log: StepLogger, onProgress: Progress): List<TopicCandidate> {
        val settings = settingsRepository.current()
        val niche = ContentText.sanitizeLine(request.niche).ifBlank { request.niche.trim() }
        onProgress(0.05f)

        val trending = if (!settings.forceOffline && safeAvailable()) fetchTrends(request, niche, settings.regionCode, log) else {
            log.i("YouTube trends unavailable (no key or offline mode); researching without them")
            emptyList()
        }
        onProgress(0.4f)

        val client = llmProvider.text()
        val ideas: List<TopicCandidate> = try {
            when {
                trending.isNotEmpty() && client != null -> llmIdeas(client, request, niche, trending, log)
                trending.isNotEmpty() -> heuristicFromTrends(request, niche, trending)
                client != null -> llmIdeas(client, request, niche, emptyList(), log)
                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("AI topic ideas failed; using offline ideas", e)
            if (trending.isNotEmpty()) heuristicFromTrends(request, niche, trending) else emptyList()
        }
        onProgress(0.85f)

        fun clean(list: List<TopicCandidate>) = list
            .map { it.copy(title = ContentText.limitChars(ContentText.sanitizeLine(it.title), 100), score = it.score.coerceIn(0, 100)) }
            .filter { it.title.isNotBlank() }
            .distinctBy { titleKey(it.title) }
        val primary = clean(ideas).sortedByDescending { it.score }.take(5)
        val usedKeys = primary.map { titleKey(it.title) }.toSet()
        val floor = primary.minOfOrNull { it.score } ?: 100
        // Offline ideas only fill missing slots and always rank below generated ones.
        val fillers = clean(offlineIdeas(request, niche))
            .filter { titleKey(it.title) !in usedKeys }
            .take(5 - primary.size)
            .map { it.copy(score = min(it.score, (floor - 1).coerceAtLeast(0))) }
        val result = (primary + fillers).sortedByDescending { it.score }
        log.i("Topic ideas: " + result.joinToString(" | ") { "${it.title} (${it.score})" })
        onProgress(1f)
        return result
    }

    private fun titleKey(title: String): String = ContentText.normalizeToken(title.replace(" ", ""))

    private suspend fun safeAvailable(): Boolean = try {
        youTube.isAvailable()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun fetchTrends(request: VideoRequest, niche: String, region: String, log: StepLogger): List<ScoredVideo> {
        val now = System.currentTimeMillis()
        val query = if (request.isShorts) "$niche #shorts" else niche
        val stats = try {
            youTube.searchPopular(
                query = query,
                language = request.language,
                regionCode = region,
                publishedAfterMillis = now - THIRTY_DAYS_MS,
                shortsOnly = request.isShorts,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("YouTube trend search failed; continuing without trends", e)
            return emptyList()
        }
        if (stats.isEmpty()) return emptyList()
        val withVpd = stats.map { s ->
            val ageDays = max(0.5, (now - s.publishedAtMillis) / 86_400_000.0)
            s to s.viewCount / ageDays
        }
        val maxVpd = withVpd.maxOf { it.second }.coerceAtLeast(1.0)
        val scored = withVpd.map { (s, vpd) ->
            val ageDays = max(0.5, (now - s.publishedAtMillis) / 86_400_000.0)
            val engagement = if (s.viewCount > 0) (s.likeCount + 2.0 * s.commentCount) / s.viewCount else 0.0
            val score = 0.6 * log10(1 + vpd) / log10(1 + maxVpd) +
                0.25 * min(1.0, engagement / 0.06) +
                0.15 * exp(-ageDays / 15.0)
            ScoredVideo(s, vpd, score)
        }.sortedByDescending { it.score }.take(15)
        log.i("Found ${stats.size} recent videos; top: ${scored.firstOrNull()?.stat?.title.orEmpty()}")
        return scored
    }

    private suspend fun llmIdeas(
        client: LlmClient,
        request: VideoRequest,
        niche: String,
        trending: List<ScoredVideo>,
        log: StepLogger,
    ): List<TopicCandidate> {
        val language = if (request.language == AppLanguage.HINDI) "Hindi (Devanagari script)" else "English"
        val format = if (request.isShorts) "YouTube Shorts (under 60 seconds)" else "a ${request.targetDurationSec / 60}-minute YouTube video"
        val user = buildString {
            appendLine("Niche: $niche")
            appendLine("Format: $format")
            appendLine("Write titles in $language.")
            if (request.notes.isNotBlank()) appendLine("Creator notes: ${request.notes}")
            if (trending.isNotEmpty()) {
                appendLine("Videos trending in this niche over the last 30 days (title | views/day | likes | comments):")
                trending.forEach {
                    appendLine("- ${it.stat.title} | ${it.viewsPerDay.roundToInt()} | ${it.stat.likeCount} | ${it.stat.commentCount}")
                }
                appendLine("Suggest 5 fresh video ideas that ride these trends WITHOUT copying any title.")
            } else {
                appendLine("Brainstorm 5 fresh video ideas with high curiosity and search demand.")
            }
            appendLine("Titles: specific, curiosity-driven, honest (no clickbait lies), under 70 characters.")
            appendLine("Return ONLY a JSON array: [{\"title\": \"...\", \"angle\": \"one sentence on the unique angle\", \"score\": 0-100, \"reason\": \"why it will perform\"}]")
        }
        val raw = client.complete(
            LlmRequest(
                system = "You are a YouTube growth strategist who finds video topics with proven demand. Reply with JSON only.",
                user = user,
                maxTokens = 4_000,
                json = true,
                jsonSchema = null,
                effort = "medium",
            ),
        )
        val parsed = parseIdeas(raw)
        val avgVpd = if (trending.isEmpty()) 0L else trending.map { it.viewsPerDay }.average().toLong()
        val sources = trending.take(3).map { it.stat.title }
        log.i("${client.displayName} suggested ${parsed.size} ideas")
        return parsed.filter { it.title.isNotBlank() }.map {
            TopicCandidate(
                title = it.title.trim(),
                angle = it.angle.trim(),
                score = it.score.coerceIn(0, 100),
                reason = it.reason.trim(),
                sourceVideoTitles = sources,
                avgViewsPerDay = avgVpd,
            )
        }
    }

    private fun parseIdeas(raw: String): List<IdeaDto> {
        val element: JsonElement = AppJson.parseToJsonElement(extractJson(raw))
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> element.values.firstOrNull { it is JsonArray } as? JsonArray ?: JsonArray(listOf(element))
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { e ->
            try {
                AppJson.decodeFromJsonElement(IdeaDto.serializer(), e)
            } catch (ex: Exception) {
                null
            }
        }
    }

    /** Without an LLM: angles built from the most frequent words in trending titles. */
    private fun heuristicFromTrends(request: VideoRequest, niche: String, trending: List<ScoredVideo>): List<TopicCandidate> {
        val counts = mutableMapOf<String, Int>()
        val display = mutableMapOf<String, String>()
        val nicheTokens = ContentText.words(niche).map(ContentText::normalizeToken).toSet()
        trending.forEach { v ->
            ContentText.contentWords(v.stat.title)
                .filter { !it.startsWith("#") && ContentText.normalizeToken(it) !in nicheTokens && ContentText.normalizeToken(it).length >= 3 }
                .forEach { w ->
                    val key = ContentText.normalizeToken(w)
                    counts[key] = (counts[key] ?: 0) + 1
                    display.putIfAbsent(key, w.lowercase())
                }
        }
        val hot = counts.entries.sortedByDescending { it.value }.take(5).mapNotNull { display[it.key] }
        val avgVpd = trending.map { it.viewsPerDay }.average().toLong()
        val topScore = (trending.first().score * 100).roundToInt().coerceIn(40, 95)
        val templates = templatesFor(request.language)
        val subjects = if (hot.isEmpty()) listOf(niche) else hot.map { "$niche $it" }
        return templates.mapIndexed { i, t ->
            val subject = subjects[i % subjects.size]
            TopicCandidate(
                title = t.title.replace("{x}", subject).replace("{n}", factCount(request).toString()),
                angle = t.angle.replace("{x}", subject),
                score = (topScore - i * 4).coerceIn(0, 100),
                reason = if (request.language == AppLanguage.HINDI) "हाल के लोकप्रिय वीडियो में \"${hot.getOrElse(i % max(1, hot.size)) { niche }}\" का ट्रेंड" else "Trending in recent popular videos: \"${hot.getOrElse(i % max(1, hot.size)) { niche }}\"",
                sourceVideoTitles = trending.take(3).map { it.stat.title },
                avgViewsPerDay = avgVpd,
            )
        }
    }

    /** Fully offline: five good templated angles for the niche. */
    private fun offlineIdeas(request: VideoRequest, niche: String): List<TopicCandidate> {
        val templates = templatesFor(request.language)
        return templates.mapIndexed { i, t ->
            TopicCandidate(
                title = t.title.replace("{x}", niche).replace("{n}", factCount(request).toString()),
                angle = t.angle.replace("{x}", niche),
                score = (62 - i * 3 + Seeded.between(0, 4, niche, i).toInt()).coerceIn(0, 100),
                reason = if (request.language == AppLanguage.HINDI) "ऑफ़लाइन सुझाव: ये फ़ॉर्मेट लगातार अच्छा प्रदर्शन करता है" else "Offline suggestion: a format that reliably performs",
            )
        }
    }

    private fun factCount(request: VideoRequest): Int = if (request.isShorts) 3 else when {
        request.targetDurationSec >= 600 -> 10
        request.targetDurationSec >= 300 -> 7
        else -> 5
    }

    private class Template(val title: String, val angle: String)

    private fun templatesFor(language: AppLanguage): List<Template> = when (language) {
        AppLanguage.ENGLISH -> listOf(
            Template("{n} surprising facts about {x}", "Little-known facts about {x} that change how people see it"),
            Template("Why {x} is not what you think", "Myth-busting: what most people get wrong about {x}"),
            Template("The truth about {x}", "An honest, evidence-based look at {x}"),
            Template("{x} explained in 60 seconds", "The fastest clear explanation of {x}"),
            Template("Beginner's guide to {x}", "Everything a newcomer needs to get started with {x}"),
        )
        AppLanguage.HINDI -> listOf(
            Template("{x} के बारे में {n} हैरान करने वाले तथ्य", "{x} के ऐसे तथ्य जो बहुत कम लोग जानते हैं"),
            Template("{x} वैसा नहीं है जैसा आप सोचते हैं", "{x} के बारे में आम गलतफहमियाँ और उनका सच"),
            Template("{x} का असली सच", "{x} पर एक ईमानदार, तथ्यों पर आधारित नज़र"),
            Template("60 सेकंड में समझिए {x}", "{x} की सबसे तेज़ और आसान व्याख्या"),
            Template("{x}: शुरुआत करने वालों के लिए पूरी गाइड", "{x} शुरू करने के लिए ज़रूरी हर बात"),
        )
    }

    private companion object {
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}

@Serializable
internal data class IdeaDto(
    val title: String = "",
    val angle: String = "",
    val score: Int = 50,
    val reason: String = "",
)
