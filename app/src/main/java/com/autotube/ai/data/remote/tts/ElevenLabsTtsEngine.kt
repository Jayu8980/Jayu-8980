package com.autotube.ai.data.remote.tts

import android.util.Log
import com.autotube.ai.core.common.ApiException
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.core.model.WordTiming
import com.autotube.ai.data.remote.http.JSON_MEDIA_TYPE
import com.autotube.ai.data.remote.http.callForString
import com.autotube.ai.data.remote.http.decodeApi
import com.autotube.ai.data.remote.http.decodeBase64
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * ElevenLabs text-to-speech with character timestamps
 * (`POST /v1/text-to-speech/{voiceId}/with-timestamps`, MP3 44.1 kHz 128 kbps).
 *
 * Stability/style follow the scene emotion and intensity; speed is clamped to ElevenLabs' 0.7-1.2.
 * Pauses are written as punctuation so the alignment stays clean. Word timings come from grouping
 * consecutive non-whitespace characters of the alignment, so they work for Devanagari too.
 */
@Singleton
class ElevenLabsTtsEngine @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : TtsEngine {

    override val type: TtsProviderType = TtsProviderType.ELEVENLABS

    override suspend fun isAvailable(): Boolean = settingsRepository.isRemoteUsable(ApiKey.ELEVENLABS)

    override suspend fun synthesize(request: TtsRequest, outFile: File): TtsOutput {
        val apiKey = settingsRepository.requireApiKey(ApiKey.ELEVENLABS)
        val settings = settingsRepository.current()
        val modelId = settings.elevenLabsModel.trim().ifEmpty { DEFAULT_MODEL }
        val voiceId = request.voice?.trim()?.takeIf { it.isNotEmpty() }
            ?: settings.elevenLabsVoiceId.trim().ifEmpty { DEFAULT_VOICE }
        val text = VoiceDirection.elevenLabsText(request.text, request.emotion, request.intensity, request.language)
        if (text.isEmpty()) {
            throw PipelineException(
                userTitle = "Nothing to narrate",
                userMessage = "A scene has no narration text, so no voice could be made for it.",
                retryable = false,
            )
        }

        val (stability, style) = VoiceDirection.elevenLabsTuning(request.emotion, request.intensity, modelId)
        val voiceSettings = ElevenVoiceSettings(
            stability = stability,
            similarityBoost = 0.75f,
            style = style,
            useSpeakerBoost = true,
            speed = request.speed.coerceIn(0.7f, 1.2f),
        )
        val languageCode = if (supportsLanguageCode(modelId)) request.language.code else null
        val url = "$API_BASE/text-to-speech".toHttpUrl().newBuilder()
            .addPathSegment(voiceId)
            .addPathSegment("with-timestamps")
            .addQueryParameter("output_format", OUTPUT_FORMAT)
            .build()

        val isV3 = modelId.contains("v3", ignoreCase = true)
        val maxChars = if (isV3) MAX_CHARS_V3 else MAX_CHARS
        // Request stitching (previous/next text) keeps prosody continuous; v3 models do not accept it.
        val stitch = !isV3
        val pieces = TextChunker.split(text, maxChars)
        val audioParts = ArrayList<ByteArray>(pieces.size)
        val words = mutableListOf<WordTiming>()
        var offsetMs = 0L

        pieces.forEachIndexed { index, piece ->
            val body = ElevenSpeechRequest(
                text = piece,
                modelId = modelId,
                languageCode = languageCode,
                voiceSettings = voiceSettings,
                previousText = if (stitch) pieces.getOrNull(index - 1)?.takeLast(CONTEXT_CHARS) else null,
                nextText = if (stitch) pieces.getOrNull(index + 1)?.take(CONTEXT_CHARS) else null,
            )
            val httpRequest = Request.Builder()
                .url(url)
                .header("xi-api-key", apiKey)
                .header("Accept", "application/json")
                .post(AppJson.encodeToString(ElevenSpeechRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = withRetry(
                attempts = 3,
                onRetry = { attempt, error -> Log.w(TAG, "ElevenLabs piece ${index + 1} failed (attempt $attempt): ${error.message}") },
            ) {
                val raw = try {
                    http.callForString(SERVICE, httpRequest)
                } catch (e: ApiException) {
                    throw friendlyError(e)
                }
                AppJson.decodeApi(SERVICE, ElevenTimestampResponse.serializer(), raw)
            }

            val audio = try {
                response.audioBase64.decodeBase64()
            } catch (e: IllegalArgumentException) {
                throw PipelineException(
                    userTitle = "Voice generation failed",
                    userMessage = "ElevenLabs returned unreadable audio. Retrying usually fixes this.",
                    retryable = true,
                    cause = e,
                )
            }
            if (audio.isEmpty()) {
                throw PipelineException(
                    userTitle = "Voice generation failed",
                    userMessage = "ElevenLabs returned an empty audio file. Retrying usually fixes this.",
                    retryable = true,
                )
            }
            audioParts += audio

            val alignment = response.alignment ?: response.normalizedAlignment
            if (alignment != null) {
                words += charactersToWords(alignment.characters, alignment.starts, alignment.ends, offsetMs)
            }
            val lastCharEndMs = alignment?.ends?.lastOrNull()?.let { (it * 1000).roundToLong() } ?: 0L
            offsetMs += maxOf(estimateMp3DurationMs(audio.size), lastCharEndMs)
        }

        withContext(Dispatchers.IO) {
            outFile.absoluteFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { out -> audioParts.forEach { out.write(it) } }
        }
        return TtsOutput(file = outFile, type = AudioFileType.MP3, wordTimings = words.takeIf { it.isNotEmpty() })
    }

    /** CBR 128 kbps: 16 bytes per millisecond. */
    private fun estimateMp3DurationMs(bytes: Int): Long = bytes / BYTES_PER_MS

    /** Only some models accept language enforcement; others reject the field. */
    private fun supportsLanguageCode(modelId: String): Boolean {
        val m = modelId.lowercase()
        return m.contains("turbo_v2_5") || m.contains("flash_v2_5")
    }

    private fun friendlyError(e: ApiException): PipelineException {
        val body = e.body.lowercase()
        return when {
            body.contains("quota_exceeded") -> PipelineException(
                userTitle = "ElevenLabs credits used up",
                userMessage = "Your ElevenLabs character quota is used up. Top up, or pick OpenAI or the on-device voice in Settings.",
                retryable = false,
                cause = e,
            )
            body.contains("voice_not_found") -> PipelineException(
                userTitle = "ElevenLabs voice not found",
                userMessage = "The ElevenLabs voice id in Settings does not exist on your account. Choose another voice.",
                retryable = false,
                cause = e,
            )
            else -> e
        }
    }

    private companion object {
        const val TAG = "ElevenLabsTtsEngine"
        const val SERVICE = "ElevenLabs"
        const val API_BASE = "https://api.elevenlabs.io/v1"
        const val OUTPUT_FORMAT = "mp3_44100_128"
        const val DEFAULT_MODEL = "eleven_multilingual_v2"
        const val DEFAULT_VOICE = "21m00Tcm4TlvDq8ikWAM"
        const val MAX_CHARS = 4_500
        const val MAX_CHARS_V3 = 2_500
        const val CONTEXT_CHARS = 300
        const val BYTES_PER_MS = 16L
    }
}

@Serializable
private data class ElevenSpeechRequest(
    val text: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("voice_settings") val voiceSettings: ElevenVoiceSettings,
    @SerialName("previous_text") val previousText: String? = null,
    @SerialName("next_text") val nextText: String? = null,
)

@Serializable
private data class ElevenVoiceSettings(
    val stability: Float,
    @SerialName("similarity_boost") val similarityBoost: Float,
    val style: Float,
    @SerialName("use_speaker_boost") val useSpeakerBoost: Boolean,
    val speed: Float,
)

@Serializable
private data class ElevenTimestampResponse(
    @SerialName("audio_base64") val audioBase64: String = "",
    val alignment: ElevenAlignment? = null,
    @SerialName("normalized_alignment") val normalizedAlignment: ElevenAlignment? = null,
)

@Serializable
private data class ElevenAlignment(
    val characters: List<String> = emptyList(),
    @SerialName("character_start_times_seconds") val starts: List<Double> = emptyList(),
    @SerialName("character_end_times_seconds") val ends: List<Double> = emptyList(),
)
