package com.autotube.ai.domain

import android.app.Activity
import android.app.PendingIntent
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.extractJson
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.AspectRatio
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.ImageGenProviderType
import com.autotube.ai.core.model.Orientation
import com.autotube.ai.core.model.SeoPackage
import com.autotube.ai.core.model.TextToVideoProviderType
import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.core.model.UploadOptions
import com.autotube.ai.core.model.VisualSource
import com.autotube.ai.core.model.WordTiming
import dagger.MapKey
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.io.File

// ---------------------------------------------------------------- LLM

class LlmImage(val bytes: ByteArray, val mimeType: String = "image/jpeg")

data class LlmRequest(
    val system: String,
    val user: String,
    val images: List<LlmImage> = emptyList(),
    val maxTokens: Int = 16_000,
    /** Ask for a JSON-only answer. */
    val json: Boolean = true,
    /** Optional JSON schema; Claude uses output_config.format, OpenAI uses response_format json_schema. */
    val jsonSchema: JsonObject? = null,
    /** Claude effort hint: "low" | "medium" | "high". Ignored by other providers. */
    val effort: String? = null,
)

interface LlmClient {
    val displayName: String
    val supportsVision: Boolean
    /** Returns the model's text answer (thinking blocks removed). Throws on refusal/HTTP error. */
    suspend fun complete(request: LlmRequest): String
}

/** Chooses the LLM from settings + available keys, falling back Claude/OpenAI -> on-device -> null. */
interface LlmProvider {
    /** Null when no LLM is usable (offline templates must be used). */
    suspend fun text(): LlmClient?
    /** Null when no vision-capable LLM is usable (vision check is then skipped). */
    suspend fun vision(): LlmClient?
}

/**
 * Calls the LLM and decodes the JSON answer into [T]. On a parse failure it asks once more with the
 * parser error appended, so the model can correct itself.
 */
suspend inline fun <reified T> LlmClient.completeJson(request: LlmRequest, attempts: Int = 2): T {
    var req = request
    var lastError: Throwable? = null
    repeat(attempts) {
        val raw = complete(req)
        try {
            return AppJson.decodeFromString<T>(extractJson(raw))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            req = request.copy(
                user = request.user + "\n\nYour previous answer was not valid JSON for the required shape " +
                    "(error: ${e.message?.take(300)}). Reply again with ONLY the corrected JSON.",
            )
        }
    }
    throw PipelineException(
        userTitle = "AI answer could not be read",
        userMessage = "${displayName} returned malformed JSON twice. Retrying usually fixes this.",
        retryable = true,
        cause = lastError,
    )
}

// ---------------------------------------------------------------- TTS / STT

data class TtsRequest(
    /** Plain narration text. */
    val text: String,
    val language: AppLanguage,
    val emotion: Emotion,
    /** 1..10 */
    val intensity: Int,
    /** 0.9..1.1 (length fine-tuning); engines map it to their own range. */
    val speed: Float = 1f,
    /** Words to emphasise. */
    val keyWords: List<String> = emptyList(),
    /** Provider-specific voice id/name; null = settings default. */
    val voice: String? = null,
)

enum class AudioFileType { MP3, WAV, PCM_S16LE_24K }

data class TtsOutput(
    val file: File,
    val type: AudioFileType,
    /** Word timings relative to the start of [file], when the engine provides them. */
    val wordTimings: List<WordTiming>? = null,
)

interface TtsEngine {
    val type: TtsProviderType
    /** True when configured (key present / engine installed). */
    suspend fun isAvailable(): Boolean
    suspend fun synthesize(request: TtsRequest, outFile: File): TtsOutput
}

@MapKey
annotation class TtsProviderKey(val value: TtsProviderType)

/** Remote speech-to-text with word timestamps (OpenAI Whisper). */
interface SpeechToTextEngine {
    suspend fun isAvailable(): Boolean
    suspend fun transcribeWords(audioFile: File, language: AppLanguage, prompt: String? = null): List<WordTiming>
}

// ---------------------------------------------------------------- Visual sources

data class StockCandidate(
    val id: String,
    val source: VisualSource,
    val downloadUrl: String,
    val previewUrl: String?,
    val width: Int,
    val height: Int,
    val durationSec: Int = 0,
    val tags: String = "",
    val attribution: String = "",
)

interface StockMediaClient {
    val name: String
    suspend fun isAvailable(): Boolean
    suspend fun searchVideos(query: String, orientation: Orientation, perPage: Int = 10): List<StockCandidate>
    suspend fun searchPhotos(query: String, orientation: Orientation, perPage: Int = 10): List<StockCandidate>
}

/** Text-to-video generation. Returns null when the provider is not configured. */
interface TextToVideoClient {
    val type: TextToVideoProviderType
    suspend fun isAvailable(): Boolean
    suspend fun generate(prompt: String, aspect: AspectRatio, durationSec: Int, outFile: File, onProgress: (Float) -> Unit = {}): File?
}

@MapKey
annotation class TextToVideoProviderKey(val value: TextToVideoProviderType)

interface ImageGenClient {
    val type: ImageGenProviderType
    suspend fun isAvailable(): Boolean
    suspend fun generate(prompt: String, aspect: AspectRatio, outFile: File): File?
}

@MapKey
annotation class ImageGenProviderKey(val value: ImageGenProviderType)

// ---------------------------------------------------------------- YouTube

data class YtVideoStat(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val publishedAtMillis: Long,
    val viewCount: Long,
    val likeCount: Long,
    val commentCount: Long,
    val durationSec: Int,
    val tags: List<String> = emptyList(),
)

interface YouTubeDataClient {
    suspend fun isAvailable(): Boolean
    /** search.list (order=viewCount, recent) + videos.list(statistics, contentDetails). */
    suspend fun searchPopular(query: String, language: AppLanguage, regionCode: String, publishedAfterMillis: Long, shortsOnly: Boolean, maxResults: Int = 25): List<YtVideoStat>
}

sealed interface AuthResult {
    data class Authorized(val accessToken: String, val email: String?) : AuthResult
    /** User consent is needed: launch this from an Activity (IntentSenderRequest) and pass the result back. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthResult
    data class Failed(val message: String) : AuthResult
}

/** Google authorization for the youtube.upload scope (Identity AuthorizationClient). */
interface YouTubeAuth {
    /** Silent when consent was already granted; otherwise returns [AuthResult.NeedsConsent]. */
    suspend fun authorize(): AuthResult
    /** Call with the Intent returned by the consent screen. */
    suspend fun handleConsentResult(data: android.content.Intent?): AuthResult
    suspend fun signOut(activity: Activity?)
}

interface YouTubeUploader {
    /**
     * Resumable upload (uploadType=resumable) in chunks; resumes after 308/5xx/network errors.
     * Sets the thumbnail afterwards when given (ignored if the channel is not verified). Returns the video id.
     */
    suspend fun upload(
        videoFile: File,
        thumbnail: File?,
        seo: SeoPackage,
        options: UploadOptions,
        languageCode: String,
        accessToken: suspend () -> String,
        onProgress: (Float) -> Unit,
    ): String
}
