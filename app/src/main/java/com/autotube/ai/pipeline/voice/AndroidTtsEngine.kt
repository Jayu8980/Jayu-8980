package com.autotube.ai.pipeline.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.core.model.WordTiming
import com.autotube.ai.domain.AudioFileType
import com.autotube.ai.domain.AudioIO
import com.autotube.ai.domain.PcmAudio
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.TtsEngine
import com.autotube.ai.domain.TtsOutput
import com.autotube.ai.domain.TtsRequest
import com.autotube.ai.pipeline.script.ContentText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline voice through android.speech.tts.TextToSpeech. The engine is created lazily (suspending
 * until onInit), shut down after a minute of inactivity, and records word timings from
 * onBeginSynthesis + onRangeStart (API 26+). Output is the engine's WAV file.
 */
@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val audioIO: AudioIO,
) : TtsEngine {

    override val type: TtsProviderType = TtsProviderType.ON_DEVICE

    private val initMutex = Mutex()
    private val speakMutex = Mutex()
    private val activeUses = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var tts: TextToSpeech? = null
    private var idleJob: Job? = null

    override suspend fun isAvailable(): Boolean {
        activeUses.incrementAndGet()
        return try {
            ensureEngine()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            release()
        }
    }

    override suspend fun synthesize(request: TtsRequest, outFile: File): TtsOutput {
        activeUses.incrementAndGet()
        try {
            val engine = ensureEngine()
            return speakMutex.withLock { synthesizeLocked(engine, request, outFile) }
        } finally {
            release()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private suspend fun ensureEngine(): TextToSpeech = initMutex.withLock {
        tts?.let { return@withLock it }
        val status = CompletableDeferred<Int>()
        val created = withContext(Dispatchers.Main) {
            TextToSpeech(context) { s -> status.complete(s) }
        }
        val result = withTimeoutOrNull(INIT_TIMEOUT_MS) { status.await() }
        if (result != TextToSpeech.SUCCESS) {
            withContext(Dispatchers.Main) { created.shutdown() }
            throw PipelineException(
                userTitle = "On-device voice unavailable",
                userMessage = "Android's text-to-speech engine did not start. Install or enable Google Speech Services " +
                    "(Settings > Accessibility > Text-to-speech), or add a cloud voice key in Settings.",
                retryable = true,
                step = PipelineStep.VOICE,
            )
        }
        tts = created
        created
    }

    private fun release() {
        if (activeUses.decrementAndGet() > 0) return
        synchronized(this) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(IDLE_SHUTDOWN_MS)
                initMutex.withLock {
                    if (activeUses.get() == 0) {
                        tts?.shutdown()
                        tts = null
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ synthesis

    private suspend fun synthesizeLocked(engine: TextToSpeech, request: TtsRequest, outFile: File): TtsOutput {
        withContext(Dispatchers.Main) { configure(engine, request) }
        val maxLen = (TextToSpeech.getMaxSpeechInputLength() - 16).coerceAtLeast(200)
        val text = request.text.trim()
        val parts = splitForEngine(text, maxLen)
        outFile.parentFile?.mkdirs()

        if (parts.size == 1) {
            val timings = synthesizeOnce(engine, parts[0], outFile)
            return TtsOutput(outFile, AudioFileType.WAV, timings?.takeIf { it.isNotEmpty() })
        }

        // Long narration: synthesize piece by piece, then join into one WAV.
        val pieces = mutableListOf<FloatArray>()
        val allTimings = mutableListOf<WordTiming>()
        var timingsComplete = true
        var offsetMs = 0L
        var rate = 0
        for ((i, part) in parts.withIndex()) {
            val partFile = File(outFile.parentFile, "${outFile.nameWithoutExtension}_part$i.wav")
            try {
                val timings = synthesizeOnce(engine, part, partFile)
                val pcm = audioIO.decode(partFile, OUTPUT_RATE, 1)
                rate = pcm.sampleRate
                if (timings.isNullOrEmpty()) timingsComplete = false
                else timings.forEach { allTimings += WordTiming(it.word, it.startMs + offsetMs, it.endMs + offsetMs) }
                pieces += pcm.samples
                offsetMs += pcm.durationMs
            } finally {
                partFile.delete()
            }
        }
        val joined = FloatArray(pieces.sumOf { it.size })
        var pos = 0
        for (p in pieces) {
            System.arraycopy(p, 0, joined, pos, p.size)
            pos += p.size
        }
        audioIO.writeWav(PcmAudio(joined, if (rate > 0) rate else OUTPUT_RATE, 1), outFile)
        return TtsOutput(outFile, AudioFileType.WAV, allTimings.takeIf { timingsComplete && it.isNotEmpty() })
    }

    private fun configure(engine: TextToSpeech, request: TtsRequest) {
        val locale = Locale.forLanguageTag(request.language.bcp47)
        val availability = engine.isLanguageAvailable(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw languageMissing(request.language)
        }
        val set = engine.setLanguage(locale)
        if (set == TextToSpeech.LANG_MISSING_DATA || set == TextToSpeech.LANG_NOT_SUPPORTED) throw languageMissing(request.language)
        val preferred = request.voice ?: settingsRepository.current().onDeviceVoiceName
        pickVoice(engine, locale, preferred)?.let { engine.setVoice(it) }
        engine.setSpeechRate((request.speed * emotionRate(request.emotion, request.intensity)).coerceIn(0.5f, 2f))
        engine.setPitch(emotionPitch(request.emotion, request.intensity))
    }

    private fun pickVoice(engine: TextToSpeech, locale: Locale, preferredName: String?): Voice? {
        val voices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        if (voices.isEmpty()) return null
        if (!preferredName.isNullOrBlank()) {
            voices.firstOrNull { it.name == preferredName }?.let { return it }
        }
        return voices
            .filter { v ->
                v.locale.language == locale.language &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (v.features ?: emptySet<String>())
            }
            .sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.locale.country == locale.country }
                    .thenByDescending { it.quality },
            )
            .firstOrNull()
    }

    /** Returns word timings (or null when the engine reports no ranges). */
    private suspend fun synthesizeOnce(engine: TextToSpeech, text: String, file: File): List<WordTiming>? {
        val utteranceId = "autotube_" + UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        val ranges = Collections.synchronizedList(mutableListOf<IntArray>()) // start, end, frame
        val sampleRate = AtomicInteger(0)

        val listener = object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId) done.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) done.completeExceptionally(synthesisError(null))
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) done.completeExceptionally(synthesisError(errorCode))
            }

            override fun onBeginSynthesis(id: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (id == utteranceId) sampleRate.set(sampleRateInHz)
            }

            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                if (id == utteranceId) ranges.add(intArrayOf(start, end, frame))
            }
        }

        val rc = withContext(Dispatchers.Main) {
            engine.setOnUtteranceProgressListener(listener)
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
            engine.synthesizeToFile(text, params, file, utteranceId)
        }
        if (rc != TextToSpeech.SUCCESS) throw synthesisError(rc)

        val timeout = (30_000L + text.length * 150L).coerceAtMost(240_000L)
        val finished = withTimeoutOrNull(timeout) { done.await() }
        if (finished == null) {
            withContext(Dispatchers.Main) { engine.stop() }
            throw PipelineException(
                userTitle = "On-device voice timed out",
                userMessage = "The phone's text-to-speech engine took too long. Close other apps and retry.",
                retryable = true,
                step = PipelineStep.VOICE,
            )
        }
        if (!file.isFile || file.length() <= 44L) throw synthesisError(null)

        val rate = sampleRate.get()
        val snapshot = synchronized(ranges) { ranges.toList() }.sortedBy { it[0] }
        if (rate <= 0 || snapshot.isEmpty()) return null
        val durationMs = try {
            audioIO.durationMs(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }
        val words = mutableListOf<WordTiming>()
        for ((i, r) in snapshot.withIndex()) {
            val s = r[0].coerceIn(0, text.length)
            val e = r[1].coerceIn(s, text.length)
            val word = text.substring(s, e).trim()
            if (word.isEmpty() || ContentText.normalizeToken(word).isEmpty()) continue
            val startMs = r[2].toLong() * 1000L / rate
            val next = snapshot.getOrNull(i + 1)
            val endMs = if (next != null) next[2].toLong() * 1000L / rate else maxOf(durationMs, startMs + 300)
            words += WordTiming(word, startMs, maxOf(endMs, startMs + 1))
        }
        return words
    }

    private fun splitForEngine(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in ContentText.sentences(text)) {
            if (current.isNotEmpty() && current.length + 1 + sentence.length > maxLen) {
                parts += current.toString()
                current.setLength(0)
            }
            if (sentence.length > maxLen) {
                sentence.chunked(maxLen).forEach { parts += it }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private fun emotionRate(emotion: Emotion, intensity: Int): Float {
        val base = when (emotion) {
            Emotion.EXCITING -> 1.07f
            Emotion.FUNNY -> 1.05f
            Emotion.HAPPY -> 1.03f
            Emotion.INSPIRING -> 1.0f
            Emotion.CALM -> 0.95f
            Emotion.DRAMATIC -> 0.96f
            Emotion.SUSPENSE -> 0.95f
            Emotion.SAD -> 0.92f
        }
        val strength = 0.6f + intensity.coerceIn(1, 10) / 25f
        return 1f + (base - 1f) * strength
    }

    private fun emotionPitch(emotion: Emotion, intensity: Int): Float {
        val base = when (emotion) {
            Emotion.EXCITING -> 1.08f
            Emotion.FUNNY -> 1.06f
            Emotion.HAPPY -> 1.05f
            Emotion.INSPIRING -> 1.02f
            Emotion.CALM -> 0.97f
            Emotion.SUSPENSE -> 0.96f
            Emotion.DRAMATIC -> 0.95f
            Emotion.SAD -> 0.94f
        }
        val strength = 0.6f + intensity.coerceIn(1, 10) / 25f
        return 1f + (base - 1f) * strength
    }

    private fun languageMissing(language: AppLanguage) = PipelineException(
        userTitle = "${language.label} voice not installed",
        userMessage = "Your phone's text-to-speech engine has no ${language.label} voice data. Open Android Settings > " +
            "Text-to-speech output > engine settings > Install voice data and add ${language.label}, " +
            "or add an OpenAI / ElevenLabs key for a cloud voice.",
        retryable = false,
        step = PipelineStep.VOICE,
    )

    private fun synthesisError(code: Int?) = PipelineException(
        userTitle = "On-device voice failed",
        userMessage = "The phone's text-to-speech engine could not create the voiceover" +
            (code?.let { " (error $it)" } ?: "") + ". Retry, or choose a cloud voice in Settings.",
        retryable = true,
        step = PipelineStep.VOICE,
    )

    private companion object {
        const val INIT_TIMEOUT_MS = 15_000L
        const val IDLE_SHUTDOWN_MS = 60_000L
        const val OUTPUT_RATE = 48_000
    }
}
