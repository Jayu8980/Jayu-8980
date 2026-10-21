package com.autotube.ai.pipeline.visuals

import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.ShotType
import com.autotube.ai.domain.LlmClient
import com.autotube.ai.domain.LlmImage
import com.autotube.ai.domain.LlmRequest
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.completeJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Result of the "does this picture match the narration?" check. */
data class VisionVerdict(
    /** 0..10 */
    val score: Int,
    val shotType: ShotType?,
    val reason: String,
)

@Serializable
internal data class VisionVerdictDto(
    val score: Int = 0,
    @SerialName("shot_type") val shotType: String? = null,
    val reason: String = "",
)

/**
 * Sends a preview JPEG plus the scene's narration and visual description to a vision LLM and asks
 * for {score 0-10, shot_type, reason}. Returns null when the model cannot be used (the caller then
 * accepts the visual without a score).
 */
@Singleton
class VisionScorer @Inject constructor() {

    suspend fun score(client: LlmClient, preview: File, scene: Scene, log: StepLogger): VisionVerdict? {
        val bytes = withContext(Dispatchers.IO) {
            if (preview.isFile) preview.readBytes() else null
        } ?: return null
        val request = LlmRequest(
            system = SYSTEM,
            user = buildString {
                appendLine("Narration of this scene: \"${scene.narrationText.take(700)}\"")
                if (scene.visualDescription.isNotBlank()) appendLine("Intended visual: \"${scene.visualDescription.take(400)}\"")
                if (scene.searchKeywords.isNotEmpty()) appendLine("Keywords: ${scene.searchKeywords.take(6).joinToString(", ")}")
                appendLine("Mood: ${scene.emotion.wireName}")
                appendLine()
                append("Rate how well the attached frame fits this narration as B-roll in a YouTube video. ")
                append("Reply with JSON only: {\"score\": 0-10, \"shot_type\": \"wide\"|\"medium\"|\"close_up\", \"reason\": \"short\"}. ")
                append("Score below 5 if the subject is unrelated, contains watermarks/large text, or looks low quality.")
            },
            images = listOf(LlmImage(bytes, "image/jpeg")),
            maxTokens = 400,
            json = true,
            effort = "low",
        )
        return try {
            val dto = client.completeJson<VisionVerdictDto>(request, attempts = 2)
            VisionVerdict(
                score = dto.score.coerceIn(0, 10),
                shotType = ShotClassifier.parse(dto.shotType),
                reason = dto.reason.take(200),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Vision check failed for scene ${scene.sceneNumber}: ${e.message?.take(160)}")
            null
        }
    }

    private companion object {
        const val SYSTEM = "You are a meticulous video editor checking stock footage. " +
            "You judge whether an image matches the spoken narration and classify its framing. Answer with JSON only."
    }
}
