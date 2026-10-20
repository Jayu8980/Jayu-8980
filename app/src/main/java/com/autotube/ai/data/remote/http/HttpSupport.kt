package com.autotube.ai.data.remote.http

import com.autotube.ai.core.common.ApiException
import com.autotube.ai.core.common.MissingApiKeyException
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Content type of every JSON request body. */
val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

/** Error bodies are kept short: they end up in logs and on the error screen. */
private const val MAX_ERROR_BODY_CHARS = 2_000

/**
 * Suspends until the call completes. Cancelling the coroutine cancels the HTTP call.
 * The caller owns (and must close) the returned [Response].
 */
suspend fun Call.await(): Response {
    val call = this
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }
}

/** Executes [request] and returns the body text. Non-2xx responses become [ApiException]. */
suspend fun OkHttpClient.callForString(service: String, request: Request): String {
    val client = this
    return withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(service, response.code, text.take(MAX_ERROR_BODY_CHARS))
            }
            text
        }
    }
}

/** Executes [request] and returns the raw body bytes. Non-2xx responses become [ApiException]. */
suspend fun OkHttpClient.callForBytes(service: String, request: Request): ByteArray {
    val client = this
    return withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw ApiException(service, response.code, text.take(MAX_ERROR_BODY_CHARS))
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }
}

/** The trimmed key, or [MissingApiKeyException] when it is not set. Read at call time: keys can change. */
fun SettingsRepository.requireApiKey(key: ApiKey): String =
    apiKey(key)?.trim()?.takeIf { it.isNotEmpty() } ?: throw MissingApiKeyException(key.label)

/** True when [key] is present and the user has not forced offline mode. */
fun SettingsRepository.isRemoteUsable(key: ApiKey): Boolean =
    !current().forceOffline && !apiKey(key).isNullOrBlank()

/** Serializes a JSON tree into a request body. */
fun JsonElement.toJsonBody(): RequestBody = toString().toRequestBody(JSON_MEDIA_TYPE)

fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

/** Lenient base64 decoding (ignores line breaks). */
fun String.decodeBase64(): ByteArray = Base64.getMimeDecoder().decode(this)

/**
 * Decodes an API response; a shape mismatch becomes a retryable [PipelineException] with a
 * message a non-technical user can read.
 */
fun <T> Json.decodeApi(service: String, deserializer: DeserializationStrategy<T>, raw: String): T =
    try {
        decodeFromString(deserializer, raw)
    } catch (e: IllegalArgumentException) {
        throw PipelineException(
            userTitle = "$service answer could not be read",
            userMessage = "$service sent a response the app did not understand. Retrying usually fixes this.",
            retryable = true,
            cause = e,
        )
    }

/** Parses [raw] into a JSON tree; malformed JSON becomes a retryable [PipelineException]. */
fun Json.parseApi(service: String, raw: String): JsonElement =
    try {
        parseToJsonElement(raw)
    } catch (e: IllegalArgumentException) {
        throw PipelineException(
            userTitle = "$service answer could not be read",
            userMessage = "$service sent a response the app did not understand. Retrying usually fixes this.",
            retryable = true,
            cause = e,
        )
    }

/** Keeps the start of [text] within [maxChars], cutting at a word boundary when possible. */
fun truncateAtWord(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.substring(0, maxChars)
    val lastSpace = cut.lastIndexOf(' ')
    return if (lastSpace > maxChars / 2) cut.substring(0, lastSpace) else cut
}
