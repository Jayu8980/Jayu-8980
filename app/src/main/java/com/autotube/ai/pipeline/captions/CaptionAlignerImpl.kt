package com.autotube.ai.pipeline.captions

import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.CaptionChunk
import com.autotube.ai.core.model.CaptionStyle
import com.autotube.ai.core.model.CaptionTrack
import com.autotube.ai.core.model.CaptionWord
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.SttProviderType
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VoiceTimeline
import com.autotube.ai.domain.CaptionAligner
import com.autotube.ai.domain.Progress
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.SpeechToTextEngine
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.pipeline.script.ContentText
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Word timing and the animated caption track.
 *
 * [align] prefers Whisper word timestamps aligned to the script text, then the TTS engine's own
 * timings, then an estimate. [buildTrack] places words on the final timeline and chunks them like a
 * human caption editor would (short chunks, breaks at punctuation and pauses, sparse emojis).
 */
@Singleton
class CaptionAlignerImpl @Inject constructor(
    private val speechToText: SpeechToTextEngine,
    private val settingsRepository: SettingsRepository,
) : CaptionAligner {

    override suspend fun align(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        audios: List<SceneAudio>,
        log: StepLogger,
        onProgress: Progress,
    ): List<SceneAudio> {
        val settings = settingsRepository.current()
        var useWhisper = settings.sttProvider == SttProviderType.WHISPER && !settings.forceOffline &&
            runCatchingAvailable()
        if (useWhisper) log.i("Aligning captions with Whisper word timestamps") else log.i("Aligning captions with on-device timing")
        val scenes = script.scenes.associateBy { it.sceneNumber }
        val result = ArrayList<SceneAudio>(audios.size)
        var whisperFailures = 0

        for ((index, audio) in audios.withIndex()) {
            val scene = scenes[audio.sceneNumber]
            val narration = ContentText.sanitizeNarration(scene?.narrationText.orEmpty())
            val scriptWords = ContentText.words(narration)
            if (scriptWords.isEmpty()) {
                result += audio
                onProgress((index + 1f) / audios.size)
                continue
            }
            var aligned: SceneAudio? = null

            if (useWhisper) {
                try {
                    val recognized = speechToText.transcribeWords(File(audio.filePath), request.language, narration)
                    val r = WordAligner.align(scriptWords, recognized, audio.durationMs)
                    if (r.matchRatio >= MIN_MATCH_RATIO) {
                        aligned = audio.copy(words = r.timings, wordsAccurate = true)
                    } else {
                        log.w("Scene ${audio.sceneNumber}: speech recognition matched only ${(r.matchRatio * 100).toInt()}% of the script; using voice timings")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    whisperFailures++
                    log.w("Scene ${audio.sceneNumber}: Whisper timing failed; using on-device timing", e)
                    if (whisperFailures >= 2) {
                        log.w("Whisper failed twice; switching to on-device timing for the remaining scenes")
                        useWhisper = false
                    }
                }
            }

            if (aligned == null && audio.words.isNotEmpty()) {
                // Engine timings: re-map to the script spelling (engines may normalise numbers/punctuation).
                val r = WordAligner.align(scriptWords, audio.words, audio.durationMs)
                aligned = audio.copy(words = r.timings, wordsAccurate = audio.wordsAccurate && r.matchRatio >= MIN_MATCH_RATIO)
            }
            if (aligned == null) {
                aligned = audio.copy(
                    words = WordTimingEstimator.estimate(scriptWords, audio.durationMs, audio.pausesMs),
                    wordsAccurate = false,
                )
            }
            result += aligned
            onProgress((index + 1f) / audios.size)
        }
        return result
    }

    private suspend fun runCatchingAvailable(): Boolean = try {
        speechToText.isAvailable()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    override fun buildTrack(
        request: VideoRequest,
        script: Script,
        audios: List<SceneAudio>,
        timeline: VoiceTimeline,
        settings: AppSettings,
    ): CaptionTrack {
        if (settings.captionStyle == CaptionStyle.OFF) return CaptionTrack(emptyList(), CaptionStyle.OFF)
        val scenes = script.scenes.associateBy { it.sceneNumber }
        val audioByScene = audios.associateBy { it.sceneNumber }
        val placed = mutableListOf<CaptionWord>()
        val punctuationEnds = mutableSetOf<Int>()
        var lastEmojiMs = Long.MIN_VALUE / 2

        for (timing in timeline.scenes) {
            val audio = audioByScene[timing.sceneNumber] ?: continue
            val scene = scenes[timing.sceneNumber]
            val words = audio.words.ifEmpty {
                val text = ContentText.sanitizeNarration(scene?.narrationText.orEmpty())
                WordTimingEstimator.estimate(ContentText.words(text), audio.durationMs, audio.pausesMs)
            }
            val keyTokens = scene?.keyWords.orEmpty()
                .flatMap { ContentText.words(it) }
                .map(ContentText::normalizeToken)
                .filter { it.isNotEmpty() }
                .toSet()
            val emojiAllowedScene = settings.emojiInCaptions && scene != null && scene.emotion in EMOJI_EMOTIONS
            var sentenceStart = true

            for (w in words) {
                val display = displayText(w.word)
                if (display.isEmpty()) continue
                val start = (timing.audioStartMs + w.startMs).coerceIn(timing.audioStartMs, timing.audioEndMs)
                val end = (timing.audioStartMs + w.endMs).coerceIn(start, timing.audioEndMs)
                val token = ContentText.normalizeToken(w.word)
                val isKeyword = token in keyTokens || display.any { it.isDigit() } ||
                    isProperNoun(display, sentenceStart, request.language)
                var emoji: String? = null
                if (emojiAllowedScene && isKeyword && start - lastEmojiMs >= EMOJI_SPACING_MS) {
                    emoji = emojiFor(token)
                    if (emoji != null) lastEmojiMs = start
                }
                placed += CaptionWord(
                    text = display,
                    startMs = start,
                    endMs = end.coerceAtLeast(start + MIN_WORD_MS).coerceAtMost(timing.audioEndMs.coerceAtLeast(start + MIN_WORD_MS)),
                    sceneNumber = timing.sceneNumber,
                    isKeyword = isKeyword,
                    emoji = emoji,
                )
                if (ContentText.endsSentence(w.word) || ContentText.endsClause(w.word)) punctuationEnds += placed.size - 1
                sentenceStart = ContentText.endsSentence(w.word)
            }
        }
        return CaptionTrack(chunks = chunk(placed, request.isShorts, punctuationEnds), style = settings.captionStyle)
    }

    private fun chunk(words: List<CaptionWord>, isShorts: Boolean, punctuationEnds: Set<Int>): List<CaptionChunk> {
        val maxWords = if (isShorts) 3 else 4
        val minWords = if (isShorts) 1 else 2
        val chunks = mutableListOf<CaptionChunk>()
        var current = mutableListOf<CaptionWord>()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks += CaptionChunk(words = current.toList(), startMs = current.first().startMs, endMs = current.last().endMs)
                current = mutableListOf()
            }
        }

        for ((i, w) in words.withIndex()) {
            val prev = current.lastOrNull()
            if (prev != null) {
                val chars = current.sumOf { it.text.length } + current.size - 1
                val pause = w.startMs - prev.endMs
                val breakNow = current.size >= maxWords ||
                    chars + 1 + w.text.length > MAX_CHUNK_CHARS ||
                    pause > PAUSE_BREAK_MS ||
                    w.sceneNumber != prev.sceneNumber
                if (breakNow) flush()
            }
            current.add(w)
            val endsHere = i in punctuationEnds
            val next = words.getOrNull(i + 1)
            // Break after punctuation, unless a long-form chunk would be left with a single short word
            // that flows straight into the next one.
            if (endsHere) {
                val tooShort = current.size < minWords && next != null && next.sceneNumber == w.sceneNumber &&
                    next.startMs - w.endMs < 120 && current.sumOf { it.text.length } <= 4
                if (!tooShort) flush()
            }
        }
        flush()

        // Hold each chunk until the next one starts when the gap is tiny (avoids flicker).
        return chunks.mapIndexed { i, c ->
            val next = chunks.getOrNull(i + 1)
            val end = if (next != null && next.startMs - c.endMs in 1..HOLD_GAP_MS) next.startMs else c.endMs
            c.copy(endMs = end.coerceAtLeast(c.startMs + MIN_WORD_MS))
        }
    }

    private fun displayText(raw: String): String =
        ContentText.stripEmoji(raw).trim()
            .trim('"', '\'', '“', '”', '‘', '’', '(', ')', '[', ']')
            .trimEnd(',', '.', ';', ':', '।', '॥', '…', '—', '–')
            .trim('"', '\'', '“', '”', '‘', '’')

    private fun isProperNoun(display: String, sentenceStart: Boolean, language: AppLanguage): Boolean {
        if (language == AppLanguage.HINDI && ContentText.isDevanagari(display)) return false
        if (sentenceStart || display.length < 2) return false
        val first = display.first()
        if (!first.isUpperCase()) return false
        if (display == "I" || display.startsWith("I'")) return false
        return ContentText.normalizeToken(display) !in ContentText.STOPWORDS_EN
    }

    private fun emojiFor(token: String): String? {
        if (token.length < 2) return null
        EMOJI_MAP[token]?.let { return it }
        return EMOJI_MAP.entries.firstOrNull { (k, _) -> k.length >= 4 && token.startsWith(k) }?.value
    }

    private companion object {
        const val MIN_MATCH_RATIO = 0.35f
        const val MAX_CHUNK_CHARS = 18
        const val PAUSE_BREAK_MS = 250L
        const val HOLD_GAP_MS = 150L
        const val MIN_WORD_MS = 60L
        const val EMOJI_SPACING_MS = 20_000L

        val EMOJI_EMOTIONS = setOf(Emotion.EXCITING, Emotion.HAPPY, Emotion.FUNNY, Emotion.INSPIRING)

        val EMOJI_MAP: Map<String, String> = mapOf(
            "money" to "💰", "rich" to "💰", "cash" to "💰", "profit" to "💰",
            "love" to "❤️", "heart" to "❤️",
            "fire" to "🔥", "hot" to "🔥", "viral" to "🔥",
            "brain" to "🧠", "mind" to "🧠", "smart" to "🧠",
            "idea" to "💡", "ideas" to "💡",
            "secret" to "🤫", "secrets" to "🤫",
            "rocket" to "🚀", "space" to "🚀", "launch" to "🚀", "growth" to "🚀",
            "win" to "🏆", "winner" to "🏆", "champion" to "🏆", "success" to "🏆",
            "star" to "⭐", "stars" to "⭐",
            "time" to "⏰",
            "world" to "🌍", "earth" to "🌍", "planet" to "🌍",
            "food" to "🍔", "pizza" to "🍕", "coffee" to "☕",
            "laugh" to "😂", "funny" to "😂", "joke" to "😂",
            "wow" to "🤯", "crazy" to "🤯", "insane" to "🤯", "mindblowing" to "🤯",
            "amazing" to "✨", "magic" to "✨", "beautiful" to "✨",
            "power" to "⚡", "energy" to "⚡", "fast" to "⚡",
            "water" to "💧", "ocean" to "🌊", "sea" to "🌊",
            "sun" to "☀️", "moon" to "🌙",
            "book" to "📚", "books" to "📚", "learn" to "📚", "study" to "📚",
            "music" to "🎵", "song" to "🎵",
            "phone" to "📱", "computer" to "💻", "code" to "💻",
            "ai" to "🤖", "robot" to "🤖", "robots" to "🤖",
            "car" to "🚗", "cars" to "🚗",
            "health" to "💪", "strong" to "💪", "fitness" to "💪", "muscle" to "💪",
            "gold" to "🥇", "dog" to "🐶", "cat" to "🐱",
            "पैसा" to "💰", "पैसे" to "💰", "प्यार" to "❤️", "दिल" to "❤️", "दिमाग" to "🧠",
            "आग" to "🔥", "दुनिया" to "🌍", "धरती" to "🌍", "समय" to "⏰", "वक्त" to "⏰",
            "जीत" to "🏆", "सफलता" to "🏆", "पानी" to "💧", "सूरज" to "☀️", "चाँद" to "🌙",
            "किताब" to "📚", "संगीत" to "🎵", "खाना" to "🍔", "ताकत" to "💪", "जादू" to "✨",
        )
    }
}
