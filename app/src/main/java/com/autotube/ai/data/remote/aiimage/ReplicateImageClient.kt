package com.autotube.ai.data.remote.aiimage

import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.AspectRatio
import com.autotube.ai.core.model.ImageGenProviderType
import com.autotube.ai.data.remote.aivideo.ReplicatePredictionRunner
import com.autotube.ai.domain.Downloader
import com.autotube.ai.domain.ImageGenClient
import com.autotube.ai.domain.SettingsRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Image generation on Replicate with the model from settings (default black-forest-labs/flux-schnell).
 * Sends `{prompt, aspect_ratio, output_format: "jpg"}`, falling back to `{prompt}` when the model's
 * schema rejects those fields (HTTP 422). Polling and timeouts are shared with AI video.
 * Returns null when no token/model is configured or offline mode is forced.
 */
@Singleton
class ReplicateImageClient @Inject constructor(
    private val runner: ReplicatePredictionRunner,
    private val downloader: Downloader,
    private val settingsRepository: SettingsRepository,
) : ImageGenClient {

    override val type: ImageGenProviderType = ImageGenProviderType.REPLICATE

    override suspend fun isAvailable(): Boolean = runner.isConfigured(currentModel())

    override suspend fun generate(prompt: String, aspect: AspectRatio, outFile: File): File? {
        val model = currentModel()
        if (!runner.isConfigured(model)) return null
        val cleanPrompt = prompt.replace(Regex("\\s+"), " ").trim().take(MAX_PROMPT_CHARS)
        if (cleanPrompt.isEmpty()) return null

        val input = buildJsonObject {
            put("prompt", cleanPrompt)
            put("aspect_ratio", aspect.label)
            put("output_format", "jpg")
        }
        val minimalInput = buildJsonObject { put("prompt", cleanPrompt) }
        val urls = runner.run(
            model = model,
            input = input,
            fallbackInput = minimalInput,
            expectedMs = EXPECTED_GENERATION_MS,
        )
        val imageUrl = urls.firstOrNull { url ->
            val path = url.substringBefore('?').lowercase()
            IMAGE_EXTENSIONS.any { path.endsWith(it) }
        } ?: urls.firstOrNull()
            ?: throw PipelineException(
                userTitle = "AI image missing",
                userMessage = "Replicate finished but returned no image. Retrying usually fixes this.",
                retryable = true,
            )
        downloader.download(imageUrl, outFile)
        return outFile
    }

    private fun currentModel(): String = settingsRepository.current().replicateImageModel.trim()

    private companion object {
        const val MAX_PROMPT_CHARS = 2_000
        const val EXPECTED_GENERATION_MS = 20_000L
        val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")
    }
}
