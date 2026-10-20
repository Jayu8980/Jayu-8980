package com.autotube.ai.data.remote.aiimage

import android.util.Log
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AspectRatio
import com.autotube.ai.core.model.ImageGenProviderType
import com.autotube.ai.data.remote.http.callForString
import com.autotube.ai.data.remote.http.decodeApi
import com.autotube.ai.data.remote.http.decodeBase64
import com.autotube.ai.data.remote.http.isRemoteUsable
import com.autotube.ai.data.remote.http.requireApiKey
import com.autotube.ai.data.remote.http.toJsonBody
import com.autotube.ai.domain.Downloader
import com.autotube.ai.domain.ImageGenClient
import com.autotube.ai.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI image generation (`POST /v1/images/generations`, one image).
 * Size follows the aspect: gpt-image models 1536x1024 / 1024x1536 (JPEG output), dall-e-3
 * 1792x1024 / 1024x1792, dall-e-2 1024x1024. Handles both `b64_json` and `url` answers.
 * Returns null when no OpenAI key is set or offline mode is forced.
 */
@Singleton
class OpenAiImageClient @Inject constructor(
    private val http: OkHttpClient,
    private val downloader: Downloader,
    private val settingsRepository: SettingsRepository,
) : ImageGenClient {

    override val type: ImageGenProviderType = ImageGenProviderType.OPENAI

    override suspend fun isAvailable(): Boolean = settingsRepository.isRemoteUsable(ApiKey.OPENAI)

    override suspend fun generate(prompt: String, aspect: AspectRatio, outFile: File): File? {
        if (!isAvailable()) return null
        val apiKey = settingsRepository.requireApiKey(ApiKey.OPENAI)
        val model = settingsRepository.current().openAiImageModel.trim().ifEmpty { DEFAULT_MODEL }
        val m = model.lowercase()
        val isDalle3 = m.startsWith("dall-e-3")
        val isDalle2 = m.startsWith("dall-e-2")
        val portrait = aspect == AspectRatio.PORTRAIT_9_16
        val size = when {
            isDalle2 -> "1024x1024"
            isDalle3 -> if (portrait) "1024x1792" else "1792x1024"
            else -> if (portrait) "1024x1536" else "1536x1024"
        }
        val maxPromptChars = when {
            isDalle2 -> 1_000
            isDalle3 -> 4_000
            else -> 32_000
        }
        val cleanPrompt = prompt.replace(Regex("\\s+"), " ").trim().take(maxPromptChars)
        if (cleanPrompt.isEmpty()) return null

        val body = buildJsonObject {
            put("model", model)
            put("prompt", cleanPrompt)
            put("n", 1)
            put("size", size)
            if (!isDalle2 && !isDalle3) put("output_format", "jpeg")
        }
        val request = Request.Builder()
            .url(GENERATIONS_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toJsonBody())
            .build()

        val response = withRetry(
            attempts = 3,
            onRetry = { attempt, error -> Log.w(TAG, "OpenAI image failed (attempt $attempt): ${error.message}") },
        ) {
            AppJson.decodeApi(SERVICE, OaiImageResponse.serializer(), http.callForString(SERVICE, request))
        }
        val image = response.data.firstOrNull()
            ?: throw PipelineException(
                userTitle = "AI image missing",
                userMessage = "OpenAI returned no image. Retrying usually fixes this.",
                retryable = true,
            )

        val base64 = image.b64Json?.takeIf { it.isNotBlank() }
        val url = image.url?.takeIf { it.isNotBlank() }
        when {
            base64 != null -> {
                val bytes = try {
                    base64.decodeBase64()
                } catch (e: IllegalArgumentException) {
                    throw PipelineException(
                        userTitle = "AI image unreadable",
                        userMessage = "OpenAI returned an image the app could not read. Retrying usually fixes this.",
                        retryable = true,
                        cause = e,
                    )
                }
                withContext(Dispatchers.IO) {
                    outFile.absoluteFile.parentFile?.mkdirs()
                    outFile.writeBytes(bytes)
                }
            }
            url != null -> downloader.download(url, outFile)
            else -> throw PipelineException(
                userTitle = "AI image missing",
                userMessage = "OpenAI returned no image data. Retrying usually fixes this.",
                retryable = true,
            )
        }
        return outFile
    }

    private companion object {
        const val TAG = "OpenAiImageClient"
        const val SERVICE = "OpenAI Images"
        const val GENERATIONS_URL = "https://api.openai.com/v1/images/generations"
        const val DEFAULT_MODEL = "gpt-image-1"
    }
}

@Serializable
private data class OaiImageResponse(
    val data: List<OaiImageData> = emptyList(),
)

@Serializable
private data class OaiImageData(
    @SerialName("b64_json") val b64Json: String? = null,
    val url: String? = null,
)
