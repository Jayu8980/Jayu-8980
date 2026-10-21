package com.autotube.ai.data.remote.aivideo

import android.os.SystemClock
import android.util.Log
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AspectRatio
import com.autotube.ai.core.model.TextToVideoProviderType
import com.autotube.ai.data.remote.http.callForString
import com.autotube.ai.data.remote.http.parseApi
import com.autotube.ai.data.remote.http.toJsonBody
import com.autotube.ai.domain.Downloader
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.TextToVideoClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text-to-video through a user-provided HTTP endpoint (Settings > custom video endpoint).
 *
 * Contract the endpoint must follow:
 * - Request: `POST <endpoint>` with JSON `{"prompt": String, "aspect_ratio": "16:9" | "9:16", "duration_sec": Int}`
 *   and, when a Custom video key is saved, header `Authorization: Bearer <key>`.
 * - Response (2xx JSON), one of:
 *   - `{"video_url": "https://..."}` or `{"url": "https://..."}`: the finished MP4;
 *   - `{"status_url": "https://..."}`: a job; the app polls `GET status_url` (same auth) every 5 s for
 *     up to 10 minutes until the JSON contains `video_url` or `url`. A `status` of `failed`, `error`
 *     or `canceled` (optionally with an `error` message) stops polling.
 * - Relative URLs are resolved against the endpoint. The auth header is only sent to the endpoint's host.
 */
@Singleton
class CustomTextToVideoClient @Inject constructor(
    private val http: OkHttpClient,
    private val downloader: Downloader,
    private val settingsRepository: SettingsRepository,
) : TextToVideoClient {

    override val type: TextToVideoProviderType = TextToVideoProviderType.CUSTOM

    override suspend fun isAvailable(): Boolean =
        !settingsRepository.current().forceOffline && endpoint() != null

    override suspend fun generate(
        prompt: String,
        aspect: AspectRatio,
        durationSec: Int,
        outFile: File,
        onProgress: (Float) -> Unit,
    ): File? {
        if (settingsRepository.current().forceOffline) return null
        val endpoint = endpoint() ?: return null
        val key = settingsRepository.apiKey(ApiKey.CUSTOM_VIDEO)?.trim()?.takeIf { it.isNotEmpty() }
        val body = buildJsonObject {
            put("prompt", prompt.replace(Regex("\\s+"), " ").trim())
            put("aspect_ratio", aspect.label)
            put("duration_sec", durationSec.coerceAtLeast(1))
        }
        val startedAt = SystemClock.elapsedRealtime()
        onProgress(0f)

        val createRequest = Request.Builder()
            .url(endpoint)
            .withAuth(key, endpoint, endpoint)
            .post(body.toJsonBody())
            .build()
        var json = withRetry(
            attempts = 3,
            onRetry = { attempt, error -> Log.w(TAG, "Custom video request failed (attempt $attempt): ${error.message}") },
        ) {
            asObject(http.callForString(SERVICE, createRequest))
        }

        var videoUrl = json.string("video_url") ?: json.string("url")
        if (videoUrl == null) {
            val statusUrl = json.string("status_url")?.let { endpoint.resolve(it) }
                ?: throw PipelineException(
                    userTitle = "Custom video API answer not understood",
                    userMessage = "The custom endpoint must return video_url, url or status_url. Check its address in Settings.",
                    retryable = false,
                )
            while (videoUrl == null) {
                checkFailed(json)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed > TIMEOUT_MS) {
                    throw PipelineException(
                        userTitle = "AI video timed out",
                        userMessage = "The custom video service did not finish within 10 minutes.",
                        retryable = true,
                    )
                }
                onProgress((elapsed.toFloat() / EXPECTED_GENERATION_MS).coerceIn(0f, 0.85f))
                delay(POLL_INTERVAL_MS)
                val pollRequest = Request.Builder()
                    .url(statusUrl)
                    .withAuth(key, statusUrl, endpoint)
                    .get()
                    .build()
                json = withRetry(
                    attempts = 4,
                    onRetry = { attempt, error -> Log.w(TAG, "Custom video poll failed (attempt $attempt): ${error.message}") },
                ) {
                    asObject(http.callForString(SERVICE, pollRequest))
                }
                videoUrl = json.string("video_url") ?: json.string("url")
            }
        }

        val finalUrl = videoUrl ?: throw PipelineException(
            userTitle = "Custom video API answer not understood",
            userMessage = "The custom endpoint returned no video link.",
            retryable = false,
        )
        val resolvedVideo = endpoint.resolve(finalUrl)
            ?: throw PipelineException(
                userTitle = "Custom video API answer not understood",
                userMessage = "The custom endpoint returned an invalid video link.",
                retryable = false,
            )
        val headers = if (key != null && resolvedVideo.host == endpoint.host) {
            mapOf("Authorization" to "Bearer $key")
        } else {
            emptyMap()
        }
        downloader.download(resolvedVideo.toString(), outFile, headers) { p -> onProgress(0.9f + 0.1f * p) }
        onProgress(1f)
        return outFile
    }

    private fun endpoint(): HttpUrl? {
        val url = settingsRepository.current().customVideoEndpoint.trim().toHttpUrlOrNull() ?: return null
        return url.takeIf { it.scheme == "https" || it.scheme == "http" }
    }

    private fun Request.Builder.withAuth(key: String?, target: HttpUrl, endpoint: HttpUrl): Request.Builder =
        if (key != null && target.host == endpoint.host) header("Authorization", "Bearer $key") else this

    private fun asObject(raw: String): JsonObject =
        AppJson.parseApi(SERVICE, raw) as? JsonObject
            ?: throw PipelineException(
                userTitle = "Custom video API answer not understood",
                userMessage = "The custom endpoint did not return a JSON object.",
                retryable = false,
            )

    private fun checkFailed(json: JsonObject) {
        val status = json.string("status")?.lowercase() ?: return
        if (status in FAILED_STATUSES) {
            val reason = json.string("error") ?: json.string("message") ?: status
            throw PipelineException(
                userTitle = "AI video failed",
                userMessage = "The custom video service reported: ${reason.take(200)}",
                retryable = false,
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "CustomTextToVideo"
        const val SERVICE = "Custom video API"
        const val POLL_INTERVAL_MS = 5_000L
        const val TIMEOUT_MS = 10L * 60 * 1000
        const val EXPECTED_GENERATION_MS = 3f * 60 * 1000
        val FAILED_STATUSES = setOf("failed", "error", "canceled", "cancelled")
    }
}
