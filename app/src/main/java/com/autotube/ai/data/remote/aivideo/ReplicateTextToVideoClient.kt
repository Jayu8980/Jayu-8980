package com.autotube.ai.data.remote.aivideo

import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.AspectRatio
import com.autotube.ai.core.model.TextToVideoProviderType
import com.autotube.ai.domain.Downloader
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.TextToVideoClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text-to-video on Replicate with the model from settings (`owner/name`, e.g. minimax/video-01).
 * Sends `{prompt, aspect_ratio}`; models whose schema rejects `aspect_ratio` (HTTP 422) are retried
 * with just `{prompt}`. The first MP4 in the output is downloaded to the requested file.
 * Returns null when no token/model is configured or offline mode is forced.
 */
@Singleton
class ReplicateTextToVideoClient @Inject constructor(
    private val runner: ReplicatePredictionRunner,
    private val downloader: Downloader,
    private val settingsRepository: SettingsRepository,
) : TextToVideoClient {

    override val type: TextToVideoProviderType = TextToVideoProviderType.REPLICATE

    override suspend fun isAvailable(): Boolean = runner.isConfigured(currentModel())

    override suspend fun generate(
        prompt: String,
        aspect: AspectRatio,
        durationSec: Int,
        outFile: File,
        onProgress: (Float) -> Unit,
    ): File? {
        val model = currentModel()
        if (!runner.isConfigured(model)) return null
        val cleanPrompt = prompt.replace(Regex("\\s+"), " ").trim().take(MAX_PROMPT_CHARS)
        if (cleanPrompt.isEmpty()) return null

        val input = buildJsonObject {
            put("prompt", cleanPrompt)
            put("aspect_ratio", aspect.label)
        }
        val minimalInput = buildJsonObject { put("prompt", cleanPrompt) }
        val urls = runner.run(
            model = model,
            input = input,
            fallbackInput = minimalInput,
            expectedMs = EXPECTED_GENERATION_MS,
            onProgress = { p -> onProgress(p * GENERATION_SHARE) },
        )
        val videoUrl = urls.firstOrNull { it.substringBefore('?').endsWith(".mp4", ignoreCase = true) }
            ?: urls.firstOrNull { it.substringBefore('?').let { path -> path.endsWith(".mov", true) || path.endsWith(".webm", true) } }
            ?: urls.firstOrNull()
            ?: throw PipelineException(
                userTitle = "AI video missing",
                userMessage = "Replicate finished but returned no video file. Retrying usually fixes this.",
                retryable = true,
            )
        downloader.download(videoUrl, outFile) { p -> onProgress(GENERATION_SHARE + (1f - GENERATION_SHARE) * p) }
        onProgress(1f)
        return outFile
    }

    private fun currentModel(): String = settingsRepository.current().replicateVideoModel.trim()

    private companion object {
        const val MAX_PROMPT_CHARS = 1_500
        const val EXPECTED_GENERATION_MS = 4L * 60 * 1000
        const val GENERATION_SHARE = 0.9f
    }
}
