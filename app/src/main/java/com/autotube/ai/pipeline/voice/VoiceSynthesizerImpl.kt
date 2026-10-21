package com.autotube.ai.pipeline.voice

import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.LongPair
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.WordTiming
import com.autotube.ai.domain.AudioFileType
import com.autotube.ai.domain.AudioIO
import com.autotube.ai.domain.PcmAudio
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.Progress
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.TtsEngine
import com.autotube.ai.domain.TtsOutput
import com.autotube.ai.domain.TtsRequest
import com.autotube.ai.domain.VoiceSynthesizer
import com.autotube.ai.pipeline.captions.WordTimingEstimator
import com.autotube.ai.pipeline.script.ContentText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Voiceover for every scene. Picks the TTS engine (settings choice, then OpenAI -> ElevenLabs ->
 * on-device), passes emotion/intensity/key words/speed, then post-processes each result into a clean
 * 48 kHz mono WAV with trimmed silences and valid word timings.
 */
@Singleton
class VoiceSynthesizerImpl @Inject constructor(
    private val engines: Map<TtsProviderType, @JvmSuppressWildcards TtsEngine>,
    private val audioIO: AudioIO,
    private val projectFiles: ProjectFiles,
    private val settingsRepository: SettingsRepository,
) : VoiceSynthesizer {

    private val versionLock = Any()

    override suspend fun synthesizeScene(projectId: Long, request: VideoRequest, scene: Scene, speed: Float, log: StepLogger): SceneAudio {
        val text = ContentText.sanitizeNarration(scene.narrationText)
        if (text.isBlank()) {
            throw PipelineException(
                userTitle = "Scene has no narration",
                userMessage = "Scene ${scene.sceneNumber} has no text to read aloud. Edit the script and try again.",
                retryable = false,
                step = PipelineStep.VOICE,
            )
        }
        val chain = engineChain()
        var lastError: Throwable? = null
        for (engine in chain) {
            try {
                return synthesizeWith(engine, projectId, request, scene, text, speed, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                log.w("${engine.type.label} could not voice scene ${scene.sceneNumber}; trying the next voice", e)
            }
        }
        throw (lastError as? PipelineException) ?: PipelineException(
            userTitle = "Voiceover failed",
            userMessage = "No voice engine could read scene ${scene.sceneNumber}. Check your internet connection or voice settings and retry.",
            retryable = true,
            step = PipelineStep.VOICE,
            cause = lastError,
        )
    }

    override suspend fun synthesizeAll(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        speed: Float,
        log: StepLogger,
        onProgress: Progress,
    ): List<SceneAudio> {
        if (script.scenes.isEmpty()) return emptyList()
        val primary = engineChain().first()
        val concurrency = if (primary.type == TtsProviderType.ON_DEVICE) 1 else 2
        log.i("Voiceover with ${primary.type.label} for ${script.scenes.size} scenes (speed ${"%.2f".format(speed)})")
        val semaphore = Semaphore(concurrency)
        val done = AtomicInteger(0)
        val total = script.scenes.size
        onProgress(0f)
        return coroutineScope {
            script.scenes.map { scene ->
                async {
                    semaphore.withPermit {
                        val audio = synthesizeScene(projectId, request, scene, speed, log)
                        onProgress(done.incrementAndGet().toFloat() / total)
                        audio
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun changeSpeed(projectId: Long, audio: SceneAudio, newSpeed: Float, log: StepLogger): SceneAudio {
        val target = newSpeed.coerceIn(0.5f, 2f)
        val current = if (audio.speed > 0f) audio.speed else 1f
        val factor = target / current
        if (abs(factor - 1f) < 0.003f) return audio.copy(speed = target)
        val source = File(audio.filePath)
        val pcm = audioIO.decode(source, VoicePostProcessor.SAMPLE_RATE, 1)
        val stretched = withContext(Dispatchers.Default) { audioIO.timeStretch(pcm, factor) }
        val ratio = stretched.durationMs.toDouble() / max(1L, pcm.durationMs)
        val outFile = nextVersionFile(projectId, audio.sceneNumber)
        audioIO.writeWav(stretched, outFile)
        val duration = stretched.durationMs
        fun scale(ms: Long) = (ms * ratio).roundToLong().coerceIn(0L, duration)
        log.i("Scene ${audio.sceneNumber}: speed ${"%.2f".format(current)} -> ${"%.2f".format(target)} (${audio.durationMs} -> $duration ms)")
        return audio.copy(
            filePath = outFile.absolutePath,
            durationMs = duration,
            speed = target,
            words = audio.words.map { WordTiming(it.word, scale(it.startMs), scale(it.endMs)) },
            pausesMs = audio.pausesMs.map { LongPair(scale(it.first), scale(it.second)) },
        )
    }

    /**
     * Shortens internal silences longer than [maxPauseMs] in an already processed scene file (used by
     * the length manager when a voiceover is only slightly too long). Word timings are remapped.
     */
    suspend fun tightenPauses(projectId: Long, audio: SceneAudio, maxPauseMs: Long, log: StepLogger): SceneAudio {
        val pcm = audioIO.decode(File(audio.filePath), VoicePostProcessor.SAMPLE_RATE, 1)
        val mono = monoAt48k(pcm)
        val options = VoicePostProcessor.Options(
            longSilenceMs = maxPauseMs,
            compressMinMs = (maxPauseMs * 0.55).roundToLong(),
            compressMaxMs = (maxPauseMs * 0.75).roundToLong(),
            highPass = false,
            normalize = false,
            seed = audio.sceneNumber * 131 + 7,
        )
        val result = withContext(Dispatchers.Default) { VoicePostProcessor.process(mono, options) }
        if (result.durationMs >= audio.durationMs - 20) return audio
        val outFile = nextVersionFile(projectId, audio.sceneNumber)
        audioIO.writeWav(PcmAudio(result.samples, VoicePostProcessor.SAMPLE_RATE, 1), outFile)
        log.i("Scene ${audio.sceneNumber}: tightened pauses (${audio.durationMs} -> ${result.durationMs} ms)")
        return audio.copy(
            filePath = outFile.absolutePath,
            durationMs = result.durationMs,
            words = result.map.mapWords(audio.words, result.durationMs),
            pausesMs = result.pauses,
        )
    }

    // ------------------------------------------------------------------ internals

    /** Engines in fallback order, only those that are available right now. */
    private suspend fun engineChain(): List<TtsEngine> {
        val settings: AppSettings = settingsRepository.current()
        val order = if (settings.forceOffline) {
            listOf(TtsProviderType.ON_DEVICE)
        } else {
            listOf(settings.ttsProvider, TtsProviderType.OPENAI, TtsProviderType.ELEVENLABS, TtsProviderType.ON_DEVICE).distinct()
        }
        val available = order.mapNotNull { type ->
            val engine = engines[type] ?: return@mapNotNull null
            val ok = try {
                engine.isAvailable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            engine.takeIf { ok }
        }
        if (available.isEmpty()) {
            throw PipelineException(
                userTitle = "No voice available",
                userMessage = "No text-to-speech voice could be started. Enable Google Speech Services in Android Settings > Text-to-speech, or add an OpenAI or ElevenLabs key in Settings.",
                retryable = false,
                step = PipelineStep.VOICE,
            )
        }
        return available
    }

    private suspend fun synthesizeWith(
        engine: TtsEngine,
        projectId: Long,
        request: VideoRequest,
        scene: Scene,
        text: String,
        speed: Float,
        log: StepLogger,
    ): SceneAudio {
        val tmpDir = projectFiles.tempDir(projectId).apply { mkdirs() }
        val guessedExt = if (engine.type == TtsProviderType.ELEVENLABS) "mp3" else "wav"
        val raw = File(tmpDir, "tts_s${scene.sceneNumber}_${System.nanoTime()}.$guessedExt")
        val ttsRequest = TtsRequest(
            text = text,
            language = request.language,
            emotion = scene.emotion,
            intensity = scene.intensity.coerceIn(1, 10),
            speed = speed.coerceIn(0.5f, 2f),
            keyWords = scene.keyWords,
        )
        val output: TtsOutput = engine.synthesize(ttsRequest, raw)
        try {
            val pcm = decodeOutput(output)
            if (pcm.isEmpty()) {
                throw PipelineException(
                    userTitle = "Empty voiceover",
                    userMessage = "${engine.type.label} returned no audio for scene ${scene.sceneNumber}.",
                    retryable = true,
                    step = PipelineStep.VOICE,
                )
            }
            val options = VoicePostProcessor.Options(seed = scene.sceneNumber * 977 + text.length)
            val processed = withContext(Dispatchers.Default) { VoicePostProcessor.process(pcm, options) }
            val outFile = nextVersionFile(projectId, scene.sceneNumber)
            audioIO.writeWav(PcmAudio(processed.samples, VoicePostProcessor.SAMPLE_RATE, 1), outFile)
            val duration = processed.durationMs
            val engineWords = output.wordTimings?.takeIf { it.isNotEmpty() }
            val words = if (engineWords != null) {
                WordTimingEstimator.monotonic(processed.map.mapWords(engineWords, duration), duration)
            } else {
                WordTimingEstimator.estimate(ContentText.words(text), duration, processed.pauses)
            }
            log.i("Scene ${scene.sceneNumber}: ${engine.type.label}, ${duration} ms, ${processed.pauses.size} pauses")
            return SceneAudio(
                sceneNumber = scene.sceneNumber,
                filePath = outFile.absolutePath,
                durationMs = duration,
                speed = speed,
                words = words,
                wordsAccurate = engineWords != null,
                pausesMs = processed.pauses,
            )
        } finally {
            raw.delete()
            if (output.file.absolutePath != raw.absolutePath) output.file.delete()
        }
    }

    private suspend fun decodeOutput(output: TtsOutput): FloatArray = when (output.type) {
        AudioFileType.PCM_S16LE_24K -> {
            val bytes = withContext(Dispatchers.IO) { output.file.readBytes() }
            val pcm = audioIO.fromPcm16(bytes, 24_000, 1)
            withContext(Dispatchers.Default) { VoicePostProcessor.resample(pcm.samples, 24_000, VoicePostProcessor.SAMPLE_RATE) }
        }
        AudioFileType.MP3, AudioFileType.WAV -> monoAt48k(audioIO.decode(output.file, VoicePostProcessor.SAMPLE_RATE, 1))
    }

    private fun monoAt48k(pcm: PcmAudio): FloatArray {
        val mono = VoicePostProcessor.downmix(pcm.samples, pcm.channels)
        return VoicePostProcessor.resample(mono, pcm.sampleRate, VoicePostProcessor.SAMPLE_RATE)
    }

    /** audioDir/scene_<n>_v<k>.wav with k never reused, so regenerations never overwrite files in use. */
    private suspend fun nextVersionFile(projectId: Long, sceneNumber: Int): File = withContext(Dispatchers.IO) {
        synchronized(versionLock) { allocateVersion(projectId, sceneNumber) }
    }

    private fun allocateVersion(projectId: Long, sceneNumber: Int): File {
        val dir = projectFiles.audioDir(projectId).apply { mkdirs() }
        val pattern = Regex("scene_${sceneNumber}_v(\\d+)\\.wav")
        val maxVersion = dir.listFiles()?.mapNotNull { f -> pattern.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
        val file = File(dir, "scene_${sceneNumber}_v${maxVersion + 1}.wav")
        file.createNewFile()
        return file
    }
}
