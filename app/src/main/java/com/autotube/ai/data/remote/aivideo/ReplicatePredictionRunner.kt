package com.autotube.ai.data.remote.aivideo

import android.os.SystemClock
import android.util.Log
import com.autotube.ai.core.common.ApiException
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.data.remote.http.callForString
import com.autotube.ai.data.remote.http.decodeApi
import com.autotube.ai.data.remote.http.isRemoteUsable
import com.autotube.ai.data.remote.http.requireApiKey
import com.autotube.ai.data.remote.http.toJsonBody
import com.autotube.ai.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a Replicate prediction end to end and returns its output URLs.
 *
 * Model strings are `owner/name` (official models, `POST /v1/models/{owner}/{name}/predictions`) or
 * `owner/name:version` (`POST /v1/predictions`). The create call asks the server to wait up to 60 s
 * (`Prefer: wait=60`); unfinished predictions are polled every 5 s for up to 10 minutes. A 422
 * (input rejected by the model's schema) is retried once with [run]'s minimal fallback input.
 * Timed-out or cancelled-by-us predictions are cancelled on Replicate so they stop billing.
 */
@Singleton
class ReplicatePredictionRunner @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) {

    /** Parsed `owner/name[:version]`. */
    data class ModelRef(val owner: String, val name: String, val version: String?)

    fun parseModel(model: String): ModelRef? {
        val match = MODEL_REGEX.matchEntire(model.trim()) ?: return null
        val version = match.groupValues[3].takeIf { it.isNotEmpty() }
        return ModelRef(owner = match.groupValues[1], name = match.groupValues[2], version = version)
    }

    /** Token present, not forced offline, and [model] looks like `owner/name[:version]`. */
    fun isConfigured(model: String): Boolean =
        settingsRepository.isRemoteUsable(ApiKey.REPLICATE) && parseModel(model) != null

    /**
     * Creates the prediction, waits for it and returns every http(s) URL found in its output, in order.
     * [expectedMs] only shapes the progress estimate (0..0.95 while waiting).
     */
    suspend fun run(
        model: String,
        input: JsonObject,
        fallbackInput: JsonObject?,
        expectedMs: Long,
        onProgress: (Float) -> Unit = {},
    ): List<String> {
        val token = settingsRepository.requireApiKey(ApiKey.REPLICATE)
        val ref = parseModel(model) ?: throw PipelineException(
            userTitle = "Replicate model not set",
            userMessage = "Enter the Replicate model in Settings as owner/name (for example minimax/video-01).",
            retryable = false,
        )
        val startedAt = SystemClock.elapsedRealtime()
        onProgress(0f)

        var prediction = try {
            create(token, ref, input)
        } catch (e: ApiException) {
            if (e.code == 422 && fallbackInput != null) {
                Log.w(TAG, "Replicate rejected the input for $model (422); retrying with the minimal input")
                create(token, ref, fallbackInput)
            } else {
                throw e
            }
        }

        try {
            while (prediction.status !in TERMINAL_STATUSES) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed > TIMEOUT_MS) {
                    cancelQuietly(token, prediction)
                    throw PipelineException(
                        userTitle = "AI generation timed out",
                        userMessage = "Replicate did not finish within 10 minutes. It will be retried or another visual source used.",
                        retryable = true,
                    )
                }
                onProgress((elapsed.toFloat() / expectedMs.coerceAtLeast(1L)).coerceIn(0f, 0.95f))
                delay(POLL_INTERVAL_MS)
                prediction = poll(token, prediction)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cancelQuietly(token, prediction) }
            throw e
        }

        return when (prediction.status) {
            "succeeded" -> {
                val urls = mutableListOf<String>()
                collectUrls(prediction.output, urls)
                if (urls.isEmpty()) {
                    throw PipelineException(
                        userTitle = "AI generation returned nothing",
                        userMessage = "Replicate finished but returned no file. Retrying usually fixes this.",
                        retryable = true,
                    )
                }
                onProgress(1f)
                urls
            }
            "canceled", "aborted" -> throw PipelineException(
                userTitle = "AI generation cancelled",
                userMessage = "The Replicate job was cancelled before it finished.",
                retryable = true,
            )
            else -> throw PipelineException(
                userTitle = "AI generation failed",
                userMessage = "Replicate could not create it: ${errorText(prediction.error).ifEmpty { "unknown error" }.take(200)}",
                retryable = false,
            )
        }
    }

    private suspend fun create(token: String, ref: ModelRef, input: JsonObject): ReplicatePrediction {
        val url: String
        val body: JsonObject
        if (ref.version != null) {
            url = "$API_BASE/predictions"
            body = buildJsonObject {
                put("version", ref.version)
                put("input", input)
            }
        } else {
            url = "$API_BASE/models/${ref.owner}/${ref.name}/predictions"
            body = buildJsonObject { put("input", input) }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Prefer", "wait=60")
            .post(body.toJsonBody())
            .build()
        return withRetry(
            attempts = 3,
            onRetry = { attempt, error -> Log.w(TAG, "Replicate create failed (attempt $attempt): ${error.message}") },
        ) {
            AppJson.decodeApi(SERVICE, ReplicatePrediction.serializer(), http.callForString(SERVICE, request))
        }
    }

    private suspend fun poll(token: String, prediction: ReplicatePrediction): ReplicatePrediction {
        val url = trustedUrl(prediction.urls?.get) ?: "$API_BASE/predictions/${prediction.id}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return withRetry(
            attempts = 4,
            onRetry = { attempt, error -> Log.w(TAG, "Replicate poll failed (attempt $attempt): ${error.message}") },
        ) {
            AppJson.decodeApi(SERVICE, ReplicatePrediction.serializer(), http.callForString(SERVICE, request))
        }
    }

    private suspend fun cancelQuietly(token: String, prediction: ReplicatePrediction) {
        if (prediction.id.isEmpty() || prediction.status in TERMINAL_STATUSES) return
        val url = trustedUrl(prediction.urls?.cancel) ?: "$API_BASE/predictions/${prediction.id}/cancel"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        try {
            http.callForString(SERVICE, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel Replicate prediction ${prediction.id}: ${e.message}")
        }
    }

    /** The token is only ever sent to api.replicate.com. */
    private fun trustedUrl(url: String?): String? {
        val parsed = url?.toHttpUrlOrNull() ?: return null
        return if (parsed.isHttps && parsed.host == API_HOST) parsed.toString() else null
    }

    private fun collectUrls(element: JsonElement?, out: MutableList<String>) {
        when (element) {
            null, JsonNull -> Unit
            is JsonPrimitive -> {
                val value = element.content
                if (element.isString && (value.startsWith("https://") || value.startsWith("http://"))) out += value
            }
            is JsonArray -> element.forEach { collectUrls(it, out) }
            is JsonObject -> element.values.forEach { collectUrls(it, out) }
        }
    }

    private fun errorText(error: JsonElement?): String = when (error) {
        null, JsonNull -> ""
        is JsonPrimitive -> error.content
        else -> error.toString()
    }

    private companion object {
        const val TAG = "ReplicateRunner"
        const val SERVICE = "Replicate"
        const val API_HOST = "api.replicate.com"
        const val API_BASE = "https://api.replicate.com/v1"
        const val POLL_INTERVAL_MS = 5_000L
        const val TIMEOUT_MS = 10L * 60 * 1000
        val TERMINAL_STATUSES = setOf("succeeded", "failed", "canceled", "aborted")
        val MODEL_REGEX = Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?::([A-Za-z0-9]+))?$")
    }
}

@Serializable
private data class ReplicatePrediction(
    val id: String = "",
    val status: String = "",
    val output: JsonElement? = null,
    val error: JsonElement? = null,
    val urls: ReplicateUrls? = null,
)

@Serializable
private data class ReplicateUrls(
    val get: String? = null,
    val cancel: String? = null,
)
