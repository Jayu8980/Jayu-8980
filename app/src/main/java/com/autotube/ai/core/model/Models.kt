package com.autotube.ai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the user asked for on the Home screen. */
@Serializable
data class VideoRequest(
    val niche: String,
    val format: VideoFormat,
    val targetDurationSec: Int,
    val language: AppLanguage,
    /** Optional extra guidance typed by the user (tone, audience...). */
    val notes: String = "",
) {
    val targetDurationMs: Long get() = targetDurationSec * 1000L
    val isShorts: Boolean get() = format == VideoFormat.SHORTS
}

/** A topic idea produced by research. */
@Serializable
data class TopicCandidate(
    val title: String,
    val angle: String = "",
    /** 0..100, higher is better. */
    val score: Int = 50,
    val reason: String = "",
    val sourceVideoTitles: List<String> = emptyList(),
    val avgViewsPerDay: Long = 0,
)

/** One scene of the script. Field names follow the JSON contract given to the LLM. */
@Serializable
data class Scene(
    @SerialName("scene_number") val sceneNumber: Int,
    val role: SceneRole = SceneRole.MAIN,
    @SerialName("narration_text") val narrationText: String,
    @SerialName("visual_description") val visualDescription: String = "",
    @SerialName("search_keywords") val searchKeywords: List<String> = emptyList(),
    @SerialName("on_screen_text") val onScreenText: String = "",
    val emotion: Emotion = Emotion.CALM,
    /** 1..10 */
    val intensity: Int = 5,
    val priority: ScenePriority = ScenePriority.ESSENTIAL,
    /** Words to emphasise in voice and highlight in captions. */
    @SerialName("key_words") val keyWords: List<String> = emptyList(),
    /** Optional section title used for chapters / section-change transitions. */
    @SerialName("section_title") val sectionTitle: String = "",
) {
    val wordCount: Int get() = narrationText.split(Regex("\\s+")).count { it.isNotBlank() }
}

@Serializable
data class Script(
    val title: String,
    val language: AppLanguage,
    val scenes: List<Scene>,
    /** Editing style the writer suggests; the editor may override. */
    val style: EditStyle = EditStyle.DOCUMENTARY,
) {
    val totalWords: Int get() = scenes.sumOf { it.wordCount }
}

/** A word with timing in milliseconds, relative to the start of its scene audio file. */
@Serializable
data class WordTiming(
    val word: String,
    val startMs: Long,
    val endMs: Long,
)

/** Voiceover for one scene. [words] must be valid for the final (post-processed) [filePath]. */
@Serializable
data class SceneAudio(
    val sceneNumber: Int,
    /** 16-bit PCM WAV, mono, 48 kHz after post-processing. */
    val filePath: String,
    val durationMs: Long,
    val speed: Float = 1f,
    val words: List<WordTiming> = emptyList(),
    /** True when [words] came from STT/TTS alignment rather than an estimate. */
    val wordsAccurate: Boolean = false,
    /** Long silences (start, end) in ms inside the file, after trimming. Useful for cut points. */
    val pausesMs: List<LongPair> = emptyList(),
)

@Serializable
data class LongPair(val first: Long, val second: Long)

/** Where a scene sits on the final video timeline (milliseconds, absolute). */
@Serializable
data class SceneTiming(
    val sceneNumber: Int,
    /** Start of the scene's voice audio on the final timeline. */
    val audioStartMs: Long,
    val audioEndMs: Long,
    /** Silence after this scene's audio before the next scene's audio. */
    val gapAfterMs: Long,
) {
    val durationMs: Long get() = audioEndMs - audioStartMs
}

/** Voice track layout: scene audio files placed back-to-back with natural gaps. */
@Serializable
data class VoiceTimeline(
    val scenes: List<SceneTiming>,
    /** Leading silence before the first word. */
    val leadInMs: Long,
    /** Tail after the last word (outro / music fade). */
    val tailMs: Long,
    val totalMs: Long,
) {
    fun timingOf(sceneNumber: Int): SceneTiming? = scenes.firstOrNull { it.sceneNumber == sceneNumber }
}

/** A word on the final timeline for captions. */
@Serializable
data class CaptionWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val sceneNumber: Int,
    val isKeyword: Boolean = false,
    val emoji: String? = null,
)

/** A group of 1-4 words displayed together; the active word is highlighted. */
@Serializable
data class CaptionChunk(
    val words: List<CaptionWord>,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class CaptionTrack(
    val chunks: List<CaptionChunk>,
    val style: CaptionStyle,
) {
    val words: List<CaptionWord> get() = chunks.flatMap { it.words }
}

/** A visual file on the device that can be placed on the timeline. */
@Serializable
data class VisualAsset(
    /** Unique id, e.g. "pexels_video:12345", "ai_image:scene3_1", "local:scene2_0". Used to avoid repeats. */
    val id: String,
    val sceneNumber: Int,
    val source: VisualSource,
    val localPath: String,
    val isVideo: Boolean,
    /** For videos, the usable duration; for images 0. */
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val shotType: ShotType = ShotType.MEDIUM,
    /** 0..10 from the vision check; -1 when not scored. */
    val matchScore: Int = -1,
    val query: String = "",
    val attribution: String = "",
    /** Small JPEG used by the UI and the vision check. */
    val previewPath: String? = null,
)

/** Result of mixing voice, music, ambience and SFX. */
@Serializable
data class AudioMixResult(
    /** Final mixed 16-bit PCM WAV, stereo 48 kHz, loudness-normalized. */
    val mixPath: String,
    val durationMs: Long,
    val integratedLufs: Float,
    val truePeakDbfs: Float,
    /** Samples that hit full scale before limiting (for the QC clipping check). */
    val clippedSamples: Int,
    /** Voice-only track on the same timeline (used by QC caption sync). */
    val voiceTrackPath: String? = null,
)

@Serializable
data class RenderResult(
    val outputPath: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val usedFallbackMuxing: Boolean = false,
)

@Serializable
enum class QcIssueType {
    BLACK_FRAMES, AUDIO_CLIPPING, CAPTION_SYNC, REPEATED_CLIP, TEXT_OVERFLOW, VISUAL_MISMATCH, PACING, LENGTH,
}

@Serializable
enum class QcSeverity { INFO, WARNING, ERROR }

@Serializable
data class QcIssue(
    val type: QcIssueType,
    val severity: QcSeverity,
    val message: String,
    val sceneNumber: Int? = null,
    val atMs: Long? = null,
)

@Serializable
data class QcReport(
    val pass: Int,
    val issues: List<QcIssue>,
    /** 1..10 from the LLM reviewer (or heuristic when no LLM). */
    val humanFeelScore: Int,
    val pacingScore: Int,
    val notes: String = "",
    val fixes: List<EditFix> = emptyList(),
) {
    val needsFix: Boolean get() = fixes.isNotEmpty()
}

/** A concrete correction the QC reviewer asks the editor to apply before re-export. */
@Serializable
data class EditFix(
    val action: EditFixAction,
    val sceneNumber: Int? = null,
    val shotIndex: Int? = null,
    val reason: String = "",
)

@Serializable
enum class EditFixAction {
    @SerialName("replace_visual") REPLACE_VISUAL,
    @SerialName("shorten_shots") SHORTEN_SHOTS,
    @SerialName("lengthen_shots") LENGTHEN_SHOTS,
    @SerialName("add_punch_in") ADD_PUNCH_IN,
    @SerialName("add_broll") ADD_BROLL,
    @SerialName("add_text_pop") ADD_TEXT_POP,
    @SerialName("simplify_transitions") SIMPLIFY_TRANSITIONS,
    @SerialName("add_pattern_interrupt") ADD_PATTERN_INTERRUPT,
    @SerialName("fix_caption_overflow") FIX_CAPTION_OVERFLOW,
    @SerialName("lower_music") LOWER_MUSIC,
}

@Serializable
data class SeoPackage(
    val title: String,
    val description: String,
    /** Exactly 15 tags when possible. */
    val tags: List<String>,
    val hashtags: List<String>,
    /** "0:00 Intro" style chapter lines, already included in [description]. */
    val chapters: List<String> = emptyList(),
    val thumbnailText: String = "",
)

@Serializable
data class UploadOptions(
    val mode: UploadMode,
    /** Epoch millis for [UploadMode.SCHEDULE]. */
    val publishAtMillis: Long? = null,
    val madeForKids: Boolean = false,
    val categoryId: String = "22",
) {
    val privacy: PrivacyStatus
        get() = when (mode) {
            UploadMode.PUBLISH_NOW -> PrivacyStatus.PUBLIC
            UploadMode.SCHEDULE -> PrivacyStatus.PRIVATE // YouTube requires private + publishAt
            UploadMode.PRIVATE_REVIEW -> PrivacyStatus.PRIVATE
        }
}
