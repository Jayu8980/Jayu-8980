package com.autotube.ai.data.remote.stt

import android.util.Log
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.WordTiming
import com.autotube.ai.data.remote.http.callForString
import com.autotube.ai.data.remote.http.decodeApi
import com.autotube.ai.data.remote.http.isRemoteUsable
import com.autotube.ai.data.remote.http.requireApiKey
import com.autotube.ai.data.remote.http.truncateAtWord
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.SpeechToTextEngine
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * OpenAI Whisper transcription with word timestamps
 * (`POST /v1/audio/transcriptions`, whisper-1, verbose_json, word granularity).
 * The narration text can be passed as `prompt` to help spelling of names and terms.
 */
@Singleton
class WhisperSttEngine @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : SpeechToTextEngine {

    override suspend fun isAvailable(): Boolean = settingsRepository.isRemoteUsable(ApiKey.OPENAI)

    override suspend fun transcribeWords(audioFile: File, language: AppLanguage, prompt: String?): List<WordTiming> {
        val apiKey = settingsRepository.requireApiKey(ApiKey.OPENAI)
        if (!audioFile.isFile || audioFile.length() == 0L) {
            throw PipelineException(
                userTitle = "Voice file missing",
                userMessage = "The voiceover file for captions could not be found. Regenerating the voice fixes this.",
                retryable = false,
            )
        }
        if (audioFile.length() > MAX_UPLOAD_BYTES) {
            throw PipelineException(
                userTitle = "Audio too long for Whisper",
                userMessage = "This voiceover is larger than Whisper's 25 MB limit; on-device caption timing will be used instead.",
                retryable = false,
            )
        }

        val extension = audioFile.extension.lowercase().ifEmpty { "wav" }
        val fileBody = audioFile.asRequestBody(mediaTypeFor(extension).toMediaType())
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.$extension", fileBody)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("language", language.code)
        trimPrompt(prompt)?.let { form.addFormDataPart("prompt", it) }

        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(form.build())
            .build()

        val response = withRetry(
            attempts = 3,
            onRetry = { attempt, error -> Log.w(TAG, "Whisper call failed (attempt $attempt): ${error.message}") },
        ) {
            val raw = http.callForString(SERVICE, request)
            AppJson.decodeApi(SERVICE, WhisperVerboseResponse.serializer(), raw)
        }

        return response.words
            .mapNotNull { word ->
                val text = word.word.trim()
                if (text.isEmpty()) return@mapNotNull null
                val startMs = (word.start * 1000).roundToLong().coerceAtLeast(0L)
                val endMs = (word.end * 1000).roundToLong().coerceAtLeast(startMs)
                WordTiming(word = text, startMs = startMs, endMs = endMs)
            }
            .sortedBy { it.startMs }
    }

    private fun mediaTypeFor(extension: String): String = when (extension) {
        "mp3", "mpga", "mpeg" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        else -> "audio/wav"
    }

    /** Whisper reads only the last ~224 tokens of the prompt; Devanagari needs far more tokens per char. */
    private fun trimPrompt(prompt: String?): String? {
        val clean = prompt?.replace(Regex("\\s+"), " ")?.trim()
        if (clean.isNullOrEmpty()) return null
        val nonAscii = clean.count { it.code >= 0x80 }
        val limit = if (nonAscii > clean.length / 3) MAX_PROMPT_CHARS_NON_LATIN else MAX_PROMPT_CHARS_LATIN
        return truncateAtWord(clean, limit)
    }

    private companion object {
        const val TAG = "WhisperSttEngine"
        const val SERVICE = "OpenAI Whisper"
        const val TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        const val MODEL = "whisper-1"
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
        const val MAX_PROMPT_CHARS_LATIN = 800
        const val MAX_PROMPT_CHARS_NON_LATIN = 300
    }
}

@Serializable
private data class WhisperVerboseResponse(
    val text: String = "",
    val words: List<WhisperWord> = emptyList(),
)

@Serializable
private data class WhisperWord(
    val word: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
)
