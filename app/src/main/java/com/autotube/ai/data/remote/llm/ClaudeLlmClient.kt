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
 * Claude via the raw Messages API (`POST /v1/messages`).
 *
 * - One user turn: image blocks first, then the text block. Never prefills an assistant turn and
 *   never sends sampling parameters or a `thinking` field (current models reject them).
 * - Claude 5-family models get `output_config.effort` (default "medium"); a JSON schema, when given,
 *   goes into `output_config.format`.
 * - Models with server-side refusal fallback get `fallbacks: "default"` plus the beta header.
 * - Only `text` blocks are returned; thinking / fallback marker blocks are skipped.
 */
@Singleton
class ClaudeLlmClient @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : LlmClient {

    override val displayName: String = "Claude"
    override val supportsVision: Boolean = true

    /** True when an Anthropic key is stored. */
    fun isConfigured(): Boolean = settingsRepository.hasApiKey(ApiKey.ANTHROPIC)

    override suspend fun complete(request: LlmRequest): String {
        val apiKey = settingsRepository.requireApiKey(ApiKey.ANTHROPIC)
        val model = settingsRepository.current().claudeModel.trim().ifEmpty { DEFAULT_MODEL }
        val serverFallback = model in SERVER_FALLBACK_MODELS

        val httpRequest = Request.Builder()
            .url(MESSAGES_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .apply { if (serverFallback) header("anthropic-beta", SERVER_FALLBACK_BETA) }
            .post(buildBody(model, request, serverFallback).toJsonBody())
            .build()

        val response = withRetry(
            attempts = 4,
            onRetry = { attempt, error -> Log.w(TAG, "Claude call failed (attempt $attempt), retrying: ${error.message}") },
        ) {
            val raw = http.callForString(SERVICE, httpRequest)
            AppJson.decodeApi(SERVICE, ClaudeMessageResponse.serializer(), raw)
        }

        when (response.stopReason) {
            "refusal" -> {
                val category = response.stopDetails?.category
                Log.w(TAG, "Claude refused the request (category=${category ?: "none"})")
                throw PipelineException(
                    userTitle = "AI declined",
                    userMessage = "Claude declined to answer this request. Try a different niche or wording, " +
                        "or switch the AI provider in Settings.",
                    retryable = false,
                )
            }
            "max_tokens" -> Log.w(TAG, "Claude hit max_tokens (${request.maxTokens}); returning the partial answer")
            else -> Unit
        }

        val text = response.content
            .filter { it.type == "text" }
            .joinToString(separator = "") { it.text.orEmpty() }
            .trim()
        if (text.isEmpty()) {
            throw PipelineException(
                userTitle = "Empty AI answer",
                userMessage = "Claude returned no text (stop reason: ${response.stopReason ?: "unknown"}). Retrying usually fixes this.",
                retryable = true,
            )
        }
        return text
    }

    private fun buildBody(model: String, request: LlmRequest, serverFallback: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens.coerceIn(1, MAX_OUTPUT_TOKENS))
        val system = systemPrompt(request)
        if (system.isNotEmpty()) put("system", system)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    request.images.forEach { image ->
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", normalizeImageMime(image.mimeType))
                                put("data", image.bytes.toBase64())
                            }
                        }
                    }
                    addJsonObject {
                        put("type", "text")
                        put("text", request.user)
                    }
                }
            }
        }
        val supportsEffort = EFFORT_MODEL_REGEX.containsMatchIn(model)
        val schema = request.jsonSchema
        if (supportsEffort || schema != null) {
            putJsonObject("output_config") {
                if (supportsEffort) put("effort", normalizeEffort(request.effort))
                if (schema != null) {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        put("schema", schema)
                    }
                }
            }
        }
        if (serverFallback) put("fallbacks", "default")
    }

    private fun systemPrompt(request: LlmRequest): String {
        val base = request.system.trim()
        if (!request.json) return base
        return if (base.isEmpty()) JSON_ONLY_INSTRUCTION else "$base\n\n$JSON_ONLY_INSTRUCTION"
    }

    private fun normalizeEffort(effort: String?): String {
        val value = effort?.trim()?.lowercase()
        return if (value != null && value in ALLOWED_EFFORTS) value else DEFAULT_EFFORT
    }

    private fun normalizeImageMime(mime: String): String {
        val value = mime.trim().lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
        return if (value in SUPPORTED_IMAGE_TYPES) value else "image/jpeg"
    }

    private companion object {
        const val TAG = "ClaudeLlmClient"
        const val SERVICE = "Claude"
        const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_EFFORT = "medium"
        const val MAX_OUTPUT_TOKENS = 128_000
        const val SERVER_FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val JSON_ONLY_INSTRUCTION = "Respond with only valid JSON, no prose, no code fences."

        val EFFORT_MODEL_REGEX = Regex("^claude-(opus|sonnet|fable|haiku)-5")
        val SERVER_FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1")
        val ALLOWED_EFFORTS = setOf("low", "medium", "high", "xhigh", "max")
        val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    }
}

@Serializable
private data class ClaudeMessageResponse(
    val model: String? = null,
    val content: List<ClaudeContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_details") val stopDetails: ClaudeStopDetails? = null,
)

@Serializable
private data class ClaudeContentBlock(
    val type: String = "",
    val text: String? = null,
)

@Serializable
private data class ClaudeStopDetails(
    val category: String? = null,
)
