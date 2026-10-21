package com.autotube.ai.pipeline.visuals

import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.ShotType
import com.autotube.ai.core.model.VideoFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds cinematic prompts for text-to-video and image generation: subject from the scene's
 * visual_description, a camera move (slow dolly in / orbit / handheld tracking), lighting matched to
 * the emotion, a lens matched to the shot type, and the fixed quality suffix.
 */
@Singleton
class CinematicPromptBuilder @Inject constructor() {

    fun build(scene: Scene, slot: Int, shotType: ShotType, format: VideoFormat, forVideo: Boolean): String {
        val parts = ArrayList<String>()
        parts += subject(scene)
        if (slot > 0) parts += VARIATIONS[slot % VARIATIONS.size]
        parts += if (forVideo) camera(scene, slot) else angle(slot)
        parts += lighting(scene.emotion)
        parts += lens(shotType)
        parts += if (format == VideoFormat.SHORTS) "vertical 9:16 composition, subject centered" else "16:9 cinematic framing"
        parts += SUFFIX
        return parts.filter { it.isNotBlank() }.joinToString(", ")
    }

    /** English-friendly subject: prefers a Latin visual description, then keywords, then narration. */
    fun subject(scene: Scene): String {
        val description = clean(scene.visualDescription)
        val keywords = scene.searchKeywords.map { clean(it) }.filter { it.isNotBlank() }
        val subject = when {
            description.isNotBlank() && isLatin(description) -> description
            keywords.isNotEmpty() && keywords.all { isLatin(it) } -> keywords.take(4).joinToString(", ")
            description.isNotBlank() -> description
            keywords.isNotEmpty() -> keywords.take(4).joinToString(", ")
            else -> clean(scene.narrationText).split(Regex("\\s+")).take(20).joinToString(" ")
        }
        return subject.take(MAX_SUBJECT_CHARS)
    }

    private fun camera(scene: Scene, slot: Int): String {
        val options = when {
            scene.intensity >= 7 || scene.emotion == Emotion.EXCITING || scene.emotion == Emotion.FUNNY ->
                listOf("handheld tracking shot following the action", "quick dolly push-in", "slow orbit around the subject")
            scene.emotion == Emotion.SUSPENSE || scene.emotion == Emotion.DRAMATIC ->
                listOf("slow dolly in", "slow orbit around the subject", "handheld tracking shot, tense")
            scene.emotion == Emotion.INSPIRING || scene.emotion == Emotion.HAPPY ->
                listOf("slow orbit around the subject", "slow crane-like rise", "slow dolly in")
            else -> listOf("slow dolly in", "gentle slow pan", "slow orbit around the subject")
        }
        return options[slot % options.size]
    }

    private fun angle(slot: Int): String = ANGLES[slot % ANGLES.size]

    private fun lighting(emotion: Emotion): String = when (emotion) {
        Emotion.HAPPY, Emotion.FUNNY -> "warm golden hour sunlight, cheerful colors"
        Emotion.INSPIRING -> "golden hour backlight, soft lens flare, hopeful mood"
        Emotion.SUSPENSE -> "moody low-key lighting, deep shadows, cool tones"
        Emotion.DRAMATIC -> "dramatic low-key lighting, strong contrast, volumetric light"
        Emotion.SAD -> "overcast soft blue light, muted colors"
        Emotion.CALM -> "soft natural daylight, gentle tones"
        Emotion.EXCITING -> "vivid high-contrast light, saturated colors"
    }

    private fun lens(shotType: ShotType): String = when (shotType) {
        ShotType.WIDE -> "24mm wide-angle lens, wide establishing shot"
        ShotType.MEDIUM -> "35mm lens, medium shot"
        ShotType.CLOSE_UP -> "85mm lens, close-up, shallow depth of field"
    }

    private fun clean(text: String): String =
        text.replace(Regex("[\"“”]"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.', ',')

    private fun isLatin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        return letters.count { it.code < 0x250 } * 2 >= letters.length
    }

    private companion object {
        const val MAX_SUBJECT_CHARS = 280
        const val SUFFIX = "photorealistic, natural motion, no text, no watermark"
        val VARIATIONS = listOf("", "different angle", "detail view", "alternate perspective")
        val ANGLES = listOf("eye-level angle", "low angle", "high angle", "over-the-shoulder angle")
    }
}

/** Keyword heuristics for WIDE / MEDIUM / CLOSE_UP when no vision model labels the shot. */
object ShotClassifier {
    private val WIDE = listOf(
        "aerial", "drone", "landscape", "city", "skyline", "panorama", "mountain", "ocean", "sea", "horizon",
        "crowd", "street", "forest", "desert", "valley", "galaxy", "space", "earth", "sky", "coast", "river",
        "building", "stadium", "field", "wide", "establishing", "village", "highway",
    )
    private val CLOSE = listOf(
        "face", "hand", "hands", "detail", "macro", "close", "closeup", "close-up", "eye", "eyes", "finger",
        "texture", "coin", "lips", "portrait", "drop", "petal", "insect", "screen", "keyboard", "chip", "cell",
    )

    fun classify(text: String): ShotType {
        val tokens = text.lowercase().split(Regex("[^\\p{L}\\p{N}-]+")).filter { it.isNotBlank() }.toSet()
        val close = CLOSE.count { it in tokens }
        val wide = WIDE.count { it in tokens }
        return when {
            close > wide -> ShotType.CLOSE_UP
            wide > close -> ShotType.WIDE
            else -> ShotType.MEDIUM
        }
    }

    fun parse(value: String?): ShotType? = when (value?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_')) {
        "wide", "wide_shot", "establishing" -> ShotType.WIDE
        "medium", "medium_shot", "mid" -> ShotType.MEDIUM
        "close_up", "closeup", "close", "macro", "detail" -> ShotType.CLOSE_UP
        else -> null
    }
}
