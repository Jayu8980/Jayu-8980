package com.autotube.ai.pipeline.editor

import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.PunchIn
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.TextPop
import com.autotube.ai.core.model.TextPopKind
import com.autotube.ai.core.model.TransitionType
import kotlin.math.max
import kotlin.math.min

/** A punch-in suggestion with a priority (higher wins when spacing forces a choice). */
internal data class PunchCandidate(val punch: PunchIn, val priority: Double)

/** A text-pop suggestion with a priority (higher wins when two would overlap). */
internal data class PopCandidate(val pop: TextPop, val priority: Double)

/**
 * Deterministic editorial suggestions shared by the heuristic planner, the normalizer (to fill what
 * an LLM plan left out) and the QC fixer: punch-ins on key words / reveals, text pops for numbers,
 * names and key facts exactly when spoken, the CTA pop, and section-change transitions.
 */
internal object EditHeuristics {

    const val PUNCH_LEAD_MS = 80L

    fun punchInCandidates(ctx: EditContext, sceneFilter: Int? = null): List<PunchCandidate> {
        val out = ArrayList<PunchCandidate>()
        val momentStarts = ctx.keyMoments.map { it.atMs }.toHashSet()
        for (track in ctx.tracks) {
            if (sceneFilter != null && track.number != sceneFilter) continue
            if (track.scene.role == SceneRole.CTA) continue
            val energy = ctx.pacing.energy(track.scene)
            for (w in track.words) {
                val isMoment = w.startMs in momentStarts
                val isNumber = w.text.any { it.isDigit() }
                val isKey = ctx.isKeyWord(w)
                val base = when {
                    isMoment -> 5.0
                    isNumber -> 4.0
                    isKey -> if (energy >= -0.3) 3.0 else 1.5
                    else -> continue
                }
                val k = (w.startMs / 7L).toInt()
                val scale = when {
                    isMoment -> DetRandom.between(1.11, 1.15, ctx.seed, k, 1)
                    isNumber -> DetRandom.between(1.08, 1.12, ctx.seed, k, 2)
                    else -> DetRandom.between(1.05, 1.10, ctx.seed, k, 3)
                }
                val duration = DetRandom.betweenLong(500L, 900L, ctx.seed, k, 4)
                out += PunchCandidate(
                    PunchIn(
                        atMs = max(0L, w.startMs - PUNCH_LEAD_MS),
                        durationMs = duration,
                        scale = scale.toFloat(),
                        word = WordText.trimPunctuation(w.text),
                    ),
                    base + track.scene.intensity.coerceIn(1, 10) * 0.1,
                )
            }
        }
        return out
    }

    fun popCandidates(ctx: EditContext, sceneFilter: Int? = null): List<PopCandidate> {
        val out = ArrayList<PopCandidate>()
        for (track in ctx.tracks) {
            if (sceneFilter != null && track.number != sceneFilter) continue
            val scene = track.scene
            if (scene.role == SceneRole.CTA) {
                out += ctaPop(ctx, track)
                continue
            }
            val words = track.words

            if (scene.onScreenText.isNotBlank()) {
                val text = clip(scene.onScreenText)
                val anchor = ctx.findSpoken(scene.onScreenText, track.timing.audioStartMs, track.timing.durationMs + 500)
                    ?: words.firstOrNull { ctx.isKeyWord(it) }
                val start = anchor?.startMs ?: (track.timing.audioStartMs + 300)
                val lastEnd = anchor?.endMs ?: (start + 600)
                if (text.isNotBlank()) {
                    out += PopCandidate(
                        TextPop(
                            text = text,
                            startMs = start,
                            endMs = popEnd(ctx, track, start, lastEnd),
                            kind = if (text.any { it.isDigit() }) TextPopKind.NUMBER else TextPopKind.FACT,
                            sceneNumber = track.number,
                            reason = "On-screen text shown as it is spoken",
                        ),
                        3.5,
                    )
                }
            }

            for ((i, w) in words.withIndex()) {
                if (w.text.none { it.isDigit() }) continue
                var text = WordText.trimPunctuation(w.text)
                var lastWord = w
                val next = words.getOrNull(i + 1)
                if (next != null && next.norm in UNITS && !WordText.endsSentence(w.text)) {
                    text = text + " " + WordText.trimPunctuation(next.text)
                    lastWord = next
                }
                if (text.isBlank()) continue
                val money = text.any { it == '$' || it == '₹' || it == '€' || it == '£' } ||
                    (next != null && next.norm in MONEY_WORDS)
                out += PopCandidate(
                    TextPop(
                        text = clip(text),
                        startMs = w.startMs,
                        endMs = popEnd(ctx, track, w.startMs, lastWord.endMs),
                        kind = TextPopKind.NUMBER,
                        emoji = if (money) "💰" else null,
                        sceneNumber = track.number,
                        reason = "Number \"$text\" popped exactly when spoken",
                    ),
                    4.0,
                )
            }

            if (WordText.isLatin(scene.narrationText)) {
                var i = 0
                while (i < words.size) {
                    val w = words[i]
                    if (!isNameToken(w.text) || i == 0 || WordText.endsSentence(words[i - 1].text)) {
                        i++
                        continue
                    }
                    var j = i + 1
                    while (j < words.size && j - i < 3 && isNameToken(words[j].text) && !WordText.endsSentence(words[j - 1].text)) j++
                    val group = words.subList(i, j)
                    if (group.size >= 2 || ctx.isKeyWord(w)) {
                        val text = group.joinToString(" ") { WordText.trimPunctuation(it.text) }
                        out += PopCandidate(
                            TextPop(
                                text = clip(text),
                                startMs = w.startMs,
                                endMs = popEnd(ctx, track, w.startMs, group.last().endMs),
                                kind = TextPopKind.NAME,
                                sceneNumber = track.number,
                                reason = "Name \"$text\" shown when spoken",
                            ),
                            2.5,
                        )
                    }
                    i = j
                }
            }

            if (scene.intensity >= 7) {
                val key = words.firstOrNull { ctx.isKeyWord(it) && it.text.none { c -> c.isDigit() } }
                if (key != null) {
                    val text = WordText.trimPunctuation(key.text)
                    out += PopCandidate(
                        TextPop(
                            text = clip(text),
                            startMs = key.startMs,
                            endMs = popEnd(ctx, track, key.startMs, key.endMs),
                            kind = TextPopKind.FACT,
                            sceneNumber = track.number,
                            reason = "Key word of a high-intensity moment",
                        ),
                        1.5,
                    )
                }
            }
        }
        return out
    }

    fun ctaPop(ctx: EditContext, track: SceneTrack): PopCandidate {
        val hindi = ctx.script.language == AppLanguage.HINDI
        val text = track.scene.onScreenText.takeIf { it.isNotBlank() }?.let { clip(it) }
            ?: if (hindi) "सब्सक्राइब करें" else "Subscribe for more"
        val anchor = CTA_WORDS.firstNotNullOfOrNull { word ->
            ctx.findSpoken(word, track.timing.audioStartMs, track.timing.durationMs + 500)
        }
        val start = anchor?.startMs ?: (track.timing.audioStartMs + 400)
        return PopCandidate(
            TextPop(
                text = text,
                startMs = start,
                endMs = min(ctx.totalMs, start + 2_000L),
                kind = TextPopKind.CTA,
                emoji = if (ctx.isShorts) "👇" else "🔔",
                sceneNumber = track.number,
                reason = "Call to action as the viewer is asked to subscribe",
            ),
            100.0,
        )
    }

    /** Section-change transition matched to the style and the incoming scene's mood. */
    fun transitionFor(ctx: EditContext, track: SceneTrack): TransitionType {
        val e = track.scene.emotion
        return when {
            track.scene.role == SceneRole.CTA -> TransitionType.FADE
            ctx.style == EditStyle.FAST_SHORTS ->
                if (DetRandom.chance(0.5, ctx.seed, track.number, 71)) TransitionType.WHIP else TransitionType.ZOOM
            ctx.style == EditStyle.CALM_EDUCATIONAL -> TransitionType.FADE
            e == Emotion.DRAMATIC || e == Emotion.SUSPENSE || e == Emotion.SAD -> TransitionType.DIP_TO_BLACK
            e == Emotion.EXCITING || e == Emotion.FUNNY -> TransitionType.ZOOM
            else -> TransitionType.FADE
        }
    }

    fun transitionMs(ctx: EditContext, key: Int): Long = when (ctx.style) {
        EditStyle.FAST_SHORTS -> DetRandom.betweenLong(250L, 380L, ctx.seed, key, 72)
        EditStyle.DOCUMENTARY -> DetRandom.betweenLong(380L, 600L, ctx.seed, key, 72)
        EditStyle.CALM_EDUCATIONAL -> DetRandom.betweenLong(450L, 600L, ctx.seed, key, 72)
    }

    private fun popEnd(ctx: EditContext, track: SceneTrack, start: Long, lastWordEnd: Long): Long {
        val wanted = max(start + 900L, lastWordEnd + 450L)
        val capped = min(wanted, start + 2_000L)
        val sceneLimit = max(start + 900L, track.timing.audioEndMs + 600L)
        return min(min(capped, sceneLimit), ctx.totalMs)
    }

    private fun clip(text: String): String {
        val clean = WordText.trimPunctuation(text.replace(WordText.WHITESPACE, " "))
        if (clean.length <= MAX_POP_CHARS) return clean
        val cut = clean.take(MAX_POP_CHARS)
        val space = cut.lastIndexOf(' ')
        return if (space >= MAX_POP_CHARS / 2) cut.substring(0, space) else cut
    }

    private fun isNameToken(raw: String): Boolean {
        val t = WordText.trimPunctuation(raw)
        if (t.length < 3) return false
        val first = t.first()
        if (!first.isUpperCase() || !first.isLetter()) return false
        if (t.all { it.isUpperCase() || !it.isLetter() } && t.length > 4) return false // SHOUTING, not a name
        return t !in NAME_STOPWORDS
    }

    private const val MAX_POP_CHARS = 32

    private val UNITS = setOf(
        "%", "percent", "per", "million", "billion", "trillion", "thousand", "hundred", "years", "year", "days",
        "hours", "minutes", "km", "kg", "miles", "meters", "metres", "feet", "dollars", "rupees", "people", "times",
        "प्रतिशत", "हज़ार", "हजार", "लाख", "करोड़", "साल", "वर्ष", "दिन", "घंटे", "रुपये", "लोग", "गुना", "किलो", "मीटर",
    )
    private val MONEY_WORDS = setOf("dollars", "dollar", "rupees", "rupee", "euros", "रुपये", "रुपए", "डॉलर")
    private val CTA_WORDS = listOf("subscribe", "सब्सक्राइब", "follow", "like", "फॉलो", "लाइक")
    private val NAME_STOPWORDS = setOf(
        "The", "This", "That", "These", "Those", "What", "When", "Where", "Why", "How", "And", "But", "Its", "You",
        "Your", "We", "Our", "They", "Their", "He", "She", "His", "Her", "If", "So", "In", "On", "At", "For", "Of",
        "To", "An", "My", "Now", "Then", "Here", "There", "Today", "Imagine", "Just", "Every", "One", "Some", "Most",
        "Did", "Do", "Does", "Is", "Are", "Was", "Were", "Not", "No", "Yes", "Let", "Let's", "Okay", "But", "Because",
        "After", "Before", "Even", "Still", "Yet", "Who", "Which", "Was", "With", "Without", "From", "Into", "All",
        "Many", "More", "Less", "First", "Second", "Third", "Next", "Finally", "Also", "Over", "Under", "It's",
    )
}
