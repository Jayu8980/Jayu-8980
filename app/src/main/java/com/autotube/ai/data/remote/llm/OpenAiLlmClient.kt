package com.autotube.ai.data.remote.llm

import android.util.Log
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.data.remote.http.callForString
import com.autotube.ai.data.remote.http.decodeApi
import com.autotube.ai.data.remote.http.requireApiKey
import com.autotube.ai.data.remote.http.toBase64
import com.autotube.ai.data.remote.http.toJsonBody
import com.autotube.ai.domain.LlmClient
import com.autotube.ai.domain.LlmRequest
import com.autotube.ai.domain.SettingsRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI Chat Completions (`POST /v1/chat/completions`) with the model from settings.
 * JSON answers use `response_format` (`json_schema` when a schema is given, else `json_object`).
 * Images are sent as base64 data URLs when the model supports vision.
 */
@Singleton
class OpenAiLlmClient @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : LlmClient {

    override val displayName: String = "OpenAI"

    /** Evaluated against the current model, which can change while the app runs. */
    override val supportsVision: Boolean
        get() = modelSupportsVision(currentModel())

    fun isConfigured(): Boolean = settingsRepository.hasApiKey(ApiKey.OPENAI)

    override suspend fun complete(request: LlmRequest): String {
        val apiKey = settingsRepository.requireApiKey(ApiKey.OPENAI)
        val model = currentModel()
        val httpRequest = Request.Builder()
            .url(CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(buildBody(model, request).toJsonBody())
            .build()

        val response = withRetry(
            attempts = 4,
            onRetry = { attempt, error -> Log.w(TAG, "OpenAI call failed (attempt $attempt), retrying: ${error.message}") },
        ) {
            val raw = http.callForString(SERVICE, httpRequest)
            AppJson.decodeApi(SERVICE, OaiChatResponse.serializer(), raw)
        }

        val choice = response.choices.firstOrNull()
            ?: throw PipelineException(
                userTitle = "Empty AI answer",
                userMessage = "OpenAI returned no answer. Retrying usually fixes this.",
                retryable = true,
            )
        val message = choice.message
        val content = message?.content?.trim().orEmpty()
        if (content.isEmpty() && !message?.refusal.isNullOrBlank()) {
            throw PipelineException(
                userTitle = "AI declined",
                userMessage = "OpenAI declined to answer this request. Try a different niche or wording, " +
                    "or switch the AI provider in Settings.",
                retryable = false,
            )
        }
        if (choice.finishReason == "length") {
            Log.w(TAG, "OpenAI hit max_completion_tokens (${request.maxTokens}); returning the partial answer")
        }
        if (content.isEmpty()) {
            throw PipelineException(
                userTitle = "Empty AI answer",
                userMessage = "OpenAI returned no text (finish reason: ${choice.finishReason ?: "unknown"}). Retrying usually fixes this.",
                retryable = true,
            )
        }
        return content
    }

    private fun currentModel(): String = settingsRepository.current().openAiModel.trim().ifEmpty { DEFAULT_MODEL }

    private fun buildBody(model: String, request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            val system = systemPrompt(request)
            if (system.isNotEmpty()) {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
            }
            addJsonObject {
                put("role", "user")
                val images = if (modelSupportsVision(model)) request.images else emptyList()
                if (images.isEmpty()) {
                    put("content", request.user)
                } else {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", request.user)
                        }
                        images.forEach { image ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${normalizeImageMime(image.mimeType)};base64,${image.bytes.toBase64()}")
                                }
                            }
                        }
                    }
                }
            }
        }
        put("max_completion_tokens", maxCompletionTokens(model, request.maxTokens))
        val schema = request.jsonSchema
        when {
            schema != null -> putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "response")
                    put("schema", schema)
                }
            }
            request.json -> putJsonObject("response_format") { put("type", "json_object") }
        }
    }

    /** json_object mode requires the word "JSON" in the messages; the instruction guarantees it. */
    private fun systemPrompt(request: LlmRequest): String {
        val base = request.system.trim()
        if (!request.json) return base
        return if (base.isEmpty()) JSON_ONLY_INSTRUCTION else "$base\n\n$JSON_ONLY_INSTRUCTION"
    }

    private fun maxCompletionTokens(model: String, requested: Int): Int {
        val m = model.lowercase()
        val cap = when {
            m.startsWith("gpt-4o") || m.startsWith("gpt-3.5") -> 16_384
            m.startsWith("gpt-4.1") -> 32_768
            m == "gpt-4" || m.startsWith("gpt-4-") -> 4_096
            else -> 100_000
        }
        return requested.coerceIn(1, cap)
    }

    private fun normalizeImageMime(mime: String): String {
        val value = mime.trim().lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
        return if (value in SUPPORTED_IMAGE_TYPES) value else "image/jpeg"
    }

    private companion object {
        const val TAG = "OpenAiLlmClient"
        const val SERVICE = "OpenAI"
        const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-4o"
        const val JSON_ONLY_INSTRUCTION = "Respond with only valid JSON, no prose, no code fences."
        val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        val O_SERIES = Regex("^o\\d")
        val TEXT_ONLY_O_SERIES = setOf("o1-mini", "o1-preview", "o3-mini")

        fun modelSupportsVision(model: String): Boolean {
            val m = model.trim().lowercase()
            return when {
                m.startsWith("gpt-4o") -> !m.contains("audio") && !m.contains("tts") && !m.contains("transcribe")
                m.startsWith("gpt-4.1") || m.startsWith("gpt-5") -> true
                O_SERIES.containsMatchIn(m) -> TEXT_ONLY_O_SERIES.none { m.startsWith(it) }
                else -> false
            }
        }
    }
}

@Serializable
private data class OaiChatResponse(
    val choices: List<OaiChoice> = emptyList(),
)

@Serializable
private data class OaiChoice(
    val message: OaiMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class OaiMessage(
    val content: String? = null,
    val refusal: String? = null,
)
