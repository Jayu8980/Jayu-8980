package com.autotube.ai.data.remote.tts

import android.util.Log
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.data.remote.http.JSON_MEDIA_TYPE
import com.autotube.ai.data.remote.http.callForBytes
import com.autotube.ai.data.remote.http.isRemoteUsable
import com.autotube.ai.data.remote.http.requireApiKey
import com.autotube.ai.domain.AudioFileType
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.TtsEngine
import com.autotube.ai.domain.TtsOutput
import com.autotube.ai.domain.TtsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI text-to-speech (`POST /v1/audio/speech`, WAV output).
 *
 * Instruction-following models (gpt-4o-mini-tts and newer) get a delivery direction built from the
 * scene's emotion, intensity, key words and language; classic tts-1 / tts-1-hd get `speed` instead.
 * Text longer than the API limit is synthesized in sentence-aligned pieces and joined into one WAV.
 * The WAV header is always rewritten with exact sizes. No word timings (Whisper/alignment adds them).
 */
@Singleton
class OpenAiTtsEngine @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : TtsEngine {

    override val type: TtsProviderType = TtsProviderType.OPENAI

    override suspend fun isAvailable(): Boolean = settingsRepository.isRemoteUsable(ApiKey.OPENAI)

    override suspend fun synthesize(request: TtsRequest, outFile: File): TtsOutput {
        val apiKey = settingsRepository.requireApiKey(ApiKey.OPENAI)
        val settings = settingsRepository.current()
        val model = settings.openAiTtsModel.trim().ifEmpty { DEFAULT_MODEL }
        val voice = request.voice?.trim()?.takeIf { it.isNotEmpty() }
            ?: settings.openAiVoice.trim().ifEmpty { DEFAULT_VOICE }
        val text = request.text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) {
            throw PipelineException(
                userTitle = "Nothing to narrate",
                userMessage = "A scene has no narration text, so no voice could be made for it.",
                retryable = false,
            )
        }

        val classicModel = model.startsWith("tts-1")
        val instructions = if (classicModel) null else VoiceDirection.openAiInstructions(request)
        val speed = if (classicModel) request.speed.coerceIn(0.25f, 4.0f) else null

        val pieces = TextChunker.split(text, MAX_INPUT_CHARS)
        val audioParts = pieces.mapIndexed { index, piece ->
            val body = OaiSpeechRequest(
                model = model,
                voice = voice,
                input = piece,
                instructions = instructions,
                speed = speed,
            )
            val httpRequest = Request.Builder()
                .url(SPEECH_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(AppJson.encodeToString(OaiSpeechRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            withRetry(
                attempts = 3,
                onRetry = { attempt, error -> Log.w(TAG, "OpenAI TTS piece ${index + 1} failed (attempt $attempt): ${error.message}") },
            ) {
                val bytes = http.callForBytes(SERVICE, httpRequest)
                if (bytes.size < MIN_AUDIO_BYTES) {
                    throw PipelineException(
                        userTitle = "Voice generation failed",
                        userMessage = "OpenAI returned an empty audio file. Retrying usually fixes this.",
                        retryable = true,
                    )
                }
                bytes
            }
        }

        withContext(Dispatchers.IO) { writeWav(audioParts, outFile) }
        return TtsOutput(file = outFile, type = AudioFileType.WAV, wordTimings = null)
    }

    private fun writeWav(parts: List<ByteArray>, outFile: File) {
        outFile.absoluteFile.parentFile?.mkdirs()
        val parsed = parts.map { WavFiles.parse(it) }
        val first = parsed.firstOrNull()
        if (first == null || parsed.any { it == null }) {
            if (parts.size == 1) {
                // Not a RIFF file we understand: keep the bytes as they are.
                outFile.writeBytes(parts.first())
                return
            }
            throw PipelineException(
                userTitle = "Voice generation failed",
                userMessage = "OpenAI returned audio in an unexpected format.",
                retryable = true,
            )
        }
        val pcmParts = parsed.filterNotNull()
        val mismatched = pcmParts.any {
            it.sampleRate != first.sampleRate || it.channels != first.channels || it.bitsPerSample != first.bitsPerSample
        }
        if (mismatched) {
            throw PipelineException(
                userTitle = "Voice generation failed",
                userMessage = "OpenAI returned audio pieces that do not match. Retrying usually fixes this.",
                retryable = true,
            )
        }
        WavFiles.write(outFile, first, pcmParts.map { it.data })
    }

    private companion object {
        const val TAG = "OpenAiTtsEngine"
        const val SERVICE = "OpenAI TTS"
        const val SPEECH_URL = "https://api.openai.com/v1/audio/speech"
        const val DEFAULT_MODEL = "gpt-4o-mini-tts"
        const val DEFAULT_VOICE = "onyx"
        /** API limit is 4096 characters per request. */
        const val MAX_INPUT_CHARS = 4_000
        const val MIN_AUDIO_BYTES = 64
    }
}

@Serializable
private data class OaiSpeechRequest(
    val model: String,
    val voice: String,
    val input: String,
    @SerialName("response_format") val responseFormat: String = "wav",
    val instructions: String? = null,
    val speed: Float? = null,
)
