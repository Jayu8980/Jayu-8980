package com.autotube.ai.pipeline.editor

import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.CaptionTrack
import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.LongPair
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.SceneTiming
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.core.model.VoiceTimeline
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A spoken word placed on the final timeline (absolute milliseconds). */
data class TimedWord(
    val text: String,
    /** Lowercase, NFC, punctuation removed: used for matching. */
    val norm: String,
    val startMs: Long,
    val endMs: Long,
    val sceneNumber: Int,
)

/** One sentence or breath group on the final timeline. */
data class SpokenLine(val words: List<TimedWord>, val sceneNumber: Int) {
    val startMs: Long get() = words.first().startMs
    val endMs: Long get() = words.last().endMs
    val text: String get() = words.joinToString(" ") { it.text }
}

/** A big moment (hook payoff / reveal): music drops out before it, a riser peaks on it, an impact hits. */
data class KeyMoment(
    val atMs: Long,
    val lineEndMs: Long,
    val sceneNumber: Int,
    val intensity: Int,
    val firstWord: String,
    val score: Double,
)

/** Word timings + pauses on the final timeline. [trusted] is false when the words are estimated. */
data class TimingData(
    val words: List<TimedWord>,
    val pauses: List<LongPair>,
    val trusted: Boolean,
)

/** One scene on the timeline with everything the editor needs about it. */
class SceneTrack(
    val scene: Scene,
    val timing: SceneTiming,
    /** 0-based order on the timeline. */
    val position: Int,
    val words: List<TimedWord>,
    /** Visuals sourced for this scene, in sourcing order. */
    val assets: List<VisualAsset>,
    /** 2 = scene role changed, 1 = new section title, 0 = same section. */
    val sectionChangeStrength: Int,
) {
    val number: Int get() = scene.sceneNumber
    val isSectionStart: Boolean get() = sectionChangeStrength > 0
}

/** Text helpers shared by the editor (word matching works for Latin and Devanagari). */
object WordText {
    private val PUNCT = Regex("[\\p{P}\\p{S}]")
    val WHITESPACE = Regex("\\s+")
    private val SENTENCE_END = setOf('.', '!', '?', '।', '॥', '…')
    private val CLAUSE_END = setOf(',', ';', ':', '—', '–')

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase().replace(PUNCT, "").trim()

    fun endsSentence(text: String): Boolean {
        val t = text.trimEnd().trimEnd('"', '\'', '”', '’', ')')
        return t.isNotEmpty() && t.last() in SENTENCE_END
    }

    fun endsClause(text: String): Boolean {
        val t = text.trimEnd()
        return t.isNotEmpty() && t.last() in CLAUSE_END
    }

    fun matches(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return min(a.length, b.length) >= 3 && (a.startsWith(b) || b.startsWith(a))
    }

    /** True when the text uses (mostly) Latin letters, so upper-casing makes sense. */
    fun isLatin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        return letters.count { it.code < 0x250 } * 2 >= letters.length
    }

    /** Removes leading/trailing punctuation but keeps %, currency and combining marks. */
    fun trimPunctuation(text: String): String = text.trim().trim { c ->
        c.isWhitespace() || (c != '%' && c.category in PUNCT_CATEGORIES)
    }

    private val PUNCT_CATEGORIES = setOf(
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION,
    )
}

/** Builds the absolute word timeline from scene audio (or captions, or an estimate). */
object WordTimelineBuilder {

    /** Uses [SceneAudio.words] (relative to each scene file) first, then caption words, then an estimate. */
    fun fromAudio(script: Script, timeline: VoiceTimeline, audios: List<SceneAudio>, captions: CaptionTrack?): TimingData {
        val words = ArrayList<TimedWord>()
        val pauses = ArrayList<LongPair>()
        val scenesByNumber = script.scenes.associateBy { it.sceneNumber }
        val captionWords = captions?.words.orEmpty()
        for (timing in timeline.scenes.sortedBy { it.audioStartMs }) {
            val audio = audios.firstOrNull { it.sceneNumber == timing.sceneNumber }
            val base = timing.audioStartMs
            val limit = timing.audioEndMs + 200
            var sceneWords = audio?.words.orEmpty().mapNotNull { w ->
                val text = w.word.trim()
                if (text.isEmpty()) return@mapNotNull null
                val s = (base + w.startMs.coerceAtLeast(0L)).coerceAtMost(limit)
                val e = (base + w.endMs).coerceIn(s + 1, max(s + 1, limit))
                TimedWord(text, WordText.normalize(text), s, e, timing.sceneNumber)
            }
            if (sceneWords.isEmpty()) {
                sceneWords = captionWords.filter { it.sceneNumber == timing.sceneNumber && it.text.isNotBlank() }.map { w ->
                    val e = max(w.endMs, w.startMs + 1)
                    TimedWord(w.text.trim(), WordText.normalize(w.text), w.startMs, e, timing.sceneNumber)
                }
            }
            if (sceneWords.isEmpty()) {
                sceneWords = estimateScene(scenesByNumber[timing.sceneNumber]?.narrationText.orEmpty(), timing)
            }
            words += sceneWords
            audio?.pausesMs?.forEach { p ->
                if (p.second > p.first) pauses += LongPair(base + p.first, base + p.second)
            }
        }
        words.sortBy { it.startMs }
        return TimingData(words, pauses, trusted = true)
    }

    /** Spreads each scene's narration over its audio span, weighted by word length. */
    fun estimate(script: Script, timeline: VoiceTimeline): TimingData {
        val scenesByNumber = script.scenes.associateBy { it.sceneNumber }
        val words = timeline.scenes.sortedBy { it.audioStartMs }.flatMap { timing ->
            estimateScene(scenesByNumber[timing.sceneNumber]?.narrationText.orEmpty(), timing)
        }
        return TimingData(words, emptyList(), trusted = false)
    }

    private fun estimateScene(narration: String, timing: SceneTiming): List<TimedWord> {
        val tokens = narration.split(WordText.WHITESPACE).filter { it.isNotBlank() }
        val span = timing.audioEndMs - timing.audioStartMs
        if (tokens.isEmpty() || span <= 0L) return emptyList()
        val weights = tokens.map { t -> t.length + 2.0 + if (WordText.endsSentence(t)) 4.0 else if (WordText.endsClause(t)) 2.0 else 0.0 }
        val total = weights.sum()
        var cursor = timing.audioStartMs.toDouble()
        return tokens.mapIndexed { i, t ->
            val slice = span * weights[i] / total
            val start = cursor.toLong()
            val speech = slice * if (WordText.endsSentence(t)) 0.7 else 0.88
            val end = (cursor + speech).toLong().coerceAtLeast(start + 1)
            cursor += slice
            TimedWord(t, WordText.normalize(t), start, end, timing.sceneNumber)
        }
    }
}

/**
 * Everything the planners and the normalizer need, precomputed once: ordered scene tracks,
 * absolute word timings, "busy" word intervals (a cut may never fall strictly inside one), and
 * scored cut candidates at pauses, sentence ends and word gaps.
 */
class EditContext private constructor(
    val request: VideoRequest,
    val script: Script,
    val timeline: VoiceTimeline,
    val pacing: PacingProfile,
    val tracks: List<SceneTrack>,
    val words: List<TimedWord>,
    val pauses: List<LongPair>,
    val visuals: List<VisualAsset>,
    val settings: AppSettings,
    /** False when the word timings are estimated (cuts already in a draft are then kept as they are). */
    val timingTrusted: Boolean,
) {
    val style: EditStyle get() = pacing.style
    val isShorts: Boolean get() = request.isShorts
    val totalMs: Long = max(
        timeline.totalMs,
        max(tracks.maxOf { it.timing.audioEndMs }, MIN_TOTAL_MS),
    )
    val assetById: Map<String, VisualAsset> = visuals.associateBy { it.id }
    /** Stable per-video seed for deterministic variation. */
    val seed: Int = DetRandom.key(request.niche + "|" + script.title)

    private val trackByNumber: Map<Int, SceneTrack> = tracks.associateBy { it.number }
    private val busyStart: LongArray
    private val busyEnd: LongArray
    private val candTimes: LongArray
    private val candWeights: DoubleArray
    private val keyNormCache = HashMap<Int, List<String>>()

    init {
        val (starts, ends) = buildBusy(words)
        busyStart = starts
        busyEnd = ends
        val candidates = buildCandidates()
        candTimes = LongArray(candidates.size) { candidates[it].first }
        candWeights = DoubleArray(candidates.size) { candidates[it].second }
    }

    /** Sentences / breath groups in timeline order. */
    val lines: List<SpokenLine> by lazy { buildLines() }

    /** The few most important lines (hook payoff / reveals), best first by time. */
    val keyMoments: List<KeyMoment> by lazy { findKeyMoments() }

    fun track(sceneNumber: Int): SceneTrack? = trackByNumber[sceneNumber]

    /** The scene whose audio started most recently at [timeMs] (the first scene during the lead-in). */
    fun trackAt(timeMs: Long): SceneTrack {
        var result = tracks.first()
        for (t in tracks) {
            if (t.timing.audioStartMs <= timeMs) result = t else break
        }
        return result
    }

    fun isInsideWord(timeMs: Long): Boolean = busyIndexAt(timeMs) >= 0

    /** Moves [t] (clamped to [lo]..[hi]) out of any word to the nearest word edge inside the range. */
    fun legalize(t: Long, lo: Long, hi: Long): Long {
        val top = max(lo, hi)
        val clamped = t.coerceIn(lo, top)
        val idx = busyIndexAt(clamped)
        if (idx < 0) return clamped
        val s = busyStart[idx]
        val e = busyEnd[idx]
        val options = listOf(s, e).filter { it in lo..top }.sortedBy { abs(it - clamped) }
        return options.firstOrNull() ?: clamped
    }

    /**
     * Best cut near [t] within [lo]..[hi]: prefers pauses and sentence ends, then clause ends and
     * word gaps, weighted by distance. Never returns a point strictly inside a word when avoidable.
     */
    fun snapCut(t: Long, lo: Long, hi: Long, windowMs: Long): Long {
        if (hi <= lo) return legalize(lo, lo, lo)
        val target = t.coerceIn(lo, hi)
        val window = windowMs.coerceAtLeast(1L)
        val from = max(lo, target - window)
        val to = min(hi, target + window)
        var bestT = -1L
        var bestScore = Double.NEGATIVE_INFINITY
        var i = lowerBound(candTimes, from)
        while (i < candTimes.size && candTimes[i] <= to) {
            val c = candTimes[i]
            val score = candWeights[i] * (1.0 - 0.55 * abs(c - target).toDouble() / window)
            if (score > bestScore && !isInsideWord(c)) {
                bestScore = score
                bestT = c
            }
            i++
        }
        return if (bestT >= 0L) bestT else legalize(target, lo, hi)
    }

    /** Words whose start lies in [fromMs, toMs). */
    fun wordsBetween(fromMs: Long, toMs: Long): List<TimedWord> {
        val out = ArrayList<TimedWord>()
        var i = lowerBoundWords(fromMs)
        while (i < words.size && words[i].startMs < toMs) {
            out += words[i]
            i++
        }
        return out
    }

    fun firstWordAtOrAfter(timeMs: Long): TimedWord? = words.getOrNull(lowerBoundWords(timeMs))

    /** The word matching the first token of [text] spoken closest to [nearMs] (within [radiusMs]). */
    fun findSpoken(text: String, nearMs: Long, radiusMs: Long): TimedWord? {
        val tokens = text.split(WordText.WHITESPACE).map { WordText.normalize(it) }.filter { it.isNotEmpty() }
        for (token in tokens.take(2)) {
            val hit = wordsBetween(nearMs - radiusMs, nearMs + radiusMs + 1)
                .filter { WordText.matches(it.norm, token) }
                .minByOrNull { abs(it.startMs - nearMs) }
            if (hit != null) return hit
        }
        return null
    }

    /** Normalized key-word tokens of [scene]. */
    fun keyNorms(scene: Scene): List<String> = keyNormCache.getOrPut(scene.sceneNumber) {
        scene.keyWords.flatMap { it.split(WordText.WHITESPACE) }
            .map { WordText.normalize(it) }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun isKeyWord(word: TimedWord): Boolean {
        val track = track(word.sceneNumber) ?: return false
        return keyNorms(track.scene).any { WordText.matches(word.norm, it) }
    }

    // ------------------------------------------------------------------ internals

    private fun busyIndexAt(t: Long): Int {
        var lo = 0
        var hi = busyStart.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (busyStart[mid] < t) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (idx >= 0 && t < busyEnd[idx]) idx else -1
    }

    private fun lowerBoundWords(t: Long): Int {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid].startMs < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun buildCandidates(): List<Pair<Long, Double>> {
        val list = ArrayList<Pair<Long, Double>>()
        for (p in pauses) {
            if (p.second > p.first) list += ((p.first + p.second) / 2) to 3.0
        }
        for (k in 0 until tracks.size - 1) {
            val ae = tracks[k].timing.audioEndMs
            val bs = tracks[k + 1].timing.audioStartMs
            if (bs > ae) list += ((ae + bs) / 2) to 3.0
        }
        for (k in 0 until words.size - 1) {
            val w = words[k]
            val n = words[k + 1]
            if (n.startMs < w.endMs) continue
            val gap = n.startMs - w.endMs
            val weight = when {
                n.sceneNumber != w.sceneNumber -> 2.5
                WordText.endsSentence(w.text) -> 2.5
                WordText.endsClause(w.text) -> 1.8
                gap >= 200 -> 1.5
                else -> 1.0
            } + if (gap >= 350) 0.5 else 0.0
            list += ((w.endMs + n.startMs) / 2) to weight
        }
        list.sortBy { it.first }
        return list
    }

    private fun buildLines(): List<SpokenLine> {
        val out = ArrayList<SpokenLine>()
        var current = ArrayList<TimedWord>()
        for (w in words) {
            val prev = current.lastOrNull()
            if (prev != null &&
                (w.sceneNumber != prev.sceneNumber || WordText.endsSentence(prev.text) || w.startMs - prev.endMs >= 450)
            ) {
                out += SpokenLine(current, prev.sceneNumber)
                current = ArrayList()
            }
            current += w
        }
        if (current.isNotEmpty()) out += SpokenLine(current, current.last().sceneNumber)
        return out
    }

    private fun findKeyMoments(): List<KeyMoment> {
        val count = if (isShorts) 1 else min(3, 1 + (totalMs / 150_000L).toInt())
        val minSpacing = if (isShorts) 0L else 45_000L
        val all = lines
        val scored = all.mapIndexedNotNull { idx, line ->
            val track = track(line.sceneNumber) ?: return@mapIndexedNotNull null
            val scene = track.scene
            if (scene.role == SceneRole.CTA) return@mapIndexedNotNull null
            if (line.startMs < 2_000L || line.words.size < 2) return@mapIndexedNotNull null
            var score = scene.intensity.coerceIn(1, 10).toDouble()
            if (scene.emotion == Emotion.DRAMATIC || scene.emotion == Emotion.SUSPENSE ||
                scene.emotion == Emotion.EXCITING || scene.emotion == Emotion.INSPIRING
            ) {
                score += 2.0
            }
            val norms = keyNorms(scene)
            val kwHits = line.words.count { w -> norms.any { WordText.matches(w.norm, it) } }
            score += 1.5 * min(kwHits, 2)
            if (line.words.any { w -> w.text.any { it.isDigit() } }) score += 1.5
            val prevLine = all.getOrNull(idx - 1)
            val firstInScene = prevLine == null || prevLine.sceneNumber != line.sceneNumber
            if (firstInScene) {
                val prevTrack = tracks.getOrNull(track.position - 1)
                if (prevTrack?.scene?.emotion == Emotion.SUSPENSE) score += 2.5
                if (prevTrack?.scene?.role == SceneRole.HOOK) score += 1.5
            }
            val nextLine = all.getOrNull(idx + 1)
            if (scene.role == SceneRole.HOOK && (nextLine == null || nextLine.sceneNumber != line.sceneNumber)) score += 1.5
            if (prevLine != null && line.startMs - prevLine.endMs >= 250) score += 1.0
            KeyMoment(
                atMs = line.startMs,
                lineEndMs = line.endMs,
                sceneNumber = line.sceneNumber,
                intensity = scene.intensity.coerceIn(1, 10),
                firstWord = line.words.first().text,
                score = score,
            )
        }
        val chosen = ArrayList<KeyMoment>()
        for (m in scored.sortedByDescending { it.score }) {
            if (chosen.size >= count) break
            if (chosen.all { abs(it.atMs - m.atMs) >= minSpacing }) chosen += m
        }
        return chosen.sortedBy { it.atMs }
    }

    companion object {
        private const val MIN_TOTAL_MS = 1_000L

        /** Builds the context; throws a readable [PipelineException] when there is nothing to edit. */
        fun create(
            request: VideoRequest,
            script: Script,
            timeline: VoiceTimeline,
            visuals: List<VisualAsset>,
            settings: AppSettings,
            timing: TimingData,
            pacing: PacingProfile = PacingProfile.forScript(request, script),
        ): EditContext {
            if (timeline.scenes.isEmpty()) {
                throw PipelineException(
                    userTitle = "Nothing to edit",
                    userMessage = "The voiceover timeline is empty, so there is nothing to edit. Re-run the voice step.",
                    retryable = true,
                    step = PipelineStep.EDIT_PLAN,
                )
            }
            if (visuals.isEmpty()) {
                throw PipelineException(
                    userTitle = "No visuals to edit",
                    userMessage = "No pictures or clips were found for the video. Re-run \"Finding visuals\".",
                    retryable = true,
                    step = PipelineStep.EDIT_PLAN,
                )
            }
            val scenesByNumber = script.scenes.associateBy { it.sceneNumber }
            val timings = sanitizeTimings(timeline.scenes)
            val wordsByScene = timing.words.groupBy { it.sceneNumber }
            var previous: Scene? = null
            val tracks = timings.mapIndexed { idx, t ->
                val scene = scenesByNumber[t.sceneNumber] ?: Scene(sceneNumber = t.sceneNumber, narrationText = "")
                val strength = when {
                    previous == null -> 0
                    scene.role != previous?.role -> 2
                    scene.sectionTitle.isNotBlank() &&
                        !scene.sectionTitle.trim().equals(previous?.sectionTitle?.trim().orEmpty(), ignoreCase = true) -> 1
                    else -> 0
                }
                previous = scene
                SceneTrack(
                    scene = scene,
                    timing = t,
                    position = idx,
                    words = wordsByScene[t.sceneNumber].orEmpty().sortedBy { it.startMs },
                    assets = visuals.filter { it.sceneNumber == t.sceneNumber },
                    sectionChangeStrength = strength,
                )
            }
            return EditContext(
                request = request,
                script = script,
                timeline = timeline,
                pacing = pacing,
                tracks = tracks,
                words = timing.words.sortedBy { it.startMs },
                pauses = timing.pauses.sortedBy { it.first },
                visuals = visuals,
                settings = settings,
                timingTrusted = timing.trusted,
            )
        }

        /** Sorted, non-overlapping scene timings with end >= start. */
        private fun sanitizeTimings(raw: List<SceneTiming>): List<SceneTiming> {
            val out = ArrayList<SceneTiming>()
            var lastEnd = 0L
            for (t in raw.sortedBy { it.audioStartMs }) {
                val start = max(t.audioStartMs, lastEnd)
                val end = max(t.audioEndMs, start + 1)
                out += t.copy(audioStartMs = start, audioEndMs = end)
                lastEnd = end
            }
            return out
        }

        private fun buildBusy(words: List<TimedWord>): Pair<LongArray, LongArray> {
            val sorted = words.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            var curS = -1L
            var curE = -1L
            for (w in sorted) {
                if (curS < 0L) {
                    curS = w.startMs
                    curE = w.endMs
                } else if (w.startMs < curE) {
                    curE = max(curE, w.endMs)
                } else {
                    starts += curS
                    ends += curE
                    curS = w.startMs
                    curE = w.endMs
                }
            }
            if (curS >= 0L) {
                starts += curS
                ends += curE
            }
            return starts.toLongArray() to ends.toLongArray()
        }

        private fun lowerBound(array: LongArray, value: Long): Int {
            var lo = 0
            var hi = array.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (array[mid] < value) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
